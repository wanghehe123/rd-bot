import { appendFile, mkdir, writeFile, rename } from "node:fs/promises";
import { createHash } from "node:crypto";
import { dirname, join } from "node:path";
import { canonicalizeStateV2, hashStateV2 } from "./agent-state-v2-codec.mjs";
import { redactDisplayText } from "./protocol.mjs";

export const AGENT_STATE_PROTOCOL = "rd-agent-state/v1";
export const STATE_CUSTOM_TYPE = "rd-agent-state";

const TODO_STATUSES = new Set(["PENDING", "IN_PROGRESS", "BLOCKED", "DONE", "CANCELLED"]);
const TODO_TRANSITIONS = {
  PENDING: new Set(["IN_PROGRESS", "BLOCKED", "CANCELLED"]),
  IN_PROGRESS: new Set(["DONE", "BLOCKED", "CANCELLED"]),
  BLOCKED: new Set(["IN_PROGRESS", "CANCELLED"]),
  DONE: new Set(),
  CANCELLED: new Set(),
};
const FACT_KINDS = new Set(["DECLARED", "OBSERVED", "INFERRED", "HISTORICAL"]);
const TERMINAL_RESULT_STATUSES = new Set([
  "SUCCESS",
  "FAILED",
  "NEED_INFO",
  "UNSAFE",
  "PASSED",
  "SKIPPED",
]);

export function stateOutputPaths(outputPath = "/work/output") {
  return {
    events: join(outputPath, "agent-state-events.jsonl"),
    latest: join(outputPath, "agent-state-latest.json"),
    effectiveContext: join(outputPath, "agent-effective-context-latest.json"),
  };
}

export class AgentStateProjector {
  #identity;
  #paths;
  #maxInjectedStateBytes;
  #protocol;
  #writeChain = Promise.resolve();
  #sequence = 0;
  #injectionSequence = 0;
  #snapshot;
  #injectionBlocker = null;
  #actionReceipts = new Map();
  #onSnapshotUpdated;

  constructor({
    identity,
    outputPath = "/work/output",
    maxInjectedStateBytes = 8192,
    initialState = null,
    onSnapshotUpdated = null,
  }) {
    this.#identity = validateIdentity(identity);
    this.#paths = stateOutputPaths(outputPath);
    this.#maxInjectedStateBytes = positiveInteger(maxInjectedStateBytes, 8192);
    if (initialState !== null && initialState !== undefined) {
      this.#snapshot = validateInitialState(initialState, this.#identity);
      this.#sequence = this.#snapshot.sequence;
      this.#protocol = this.#snapshot.protocol;
    } else {
      this.#snapshot = createInitialSnapshot(this.#identity);
      this.#protocol = AGENT_STATE_PROTOCOL;
    }
    this.#onSnapshotUpdated = typeof onSnapshotUpdated === "function" ? onSnapshotUpdated : null;
  }

  get sequence() {
    return this.#sequence;
  }

  get snapshot() {
    return structuredClone(this.#snapshot);
  }

  get injectionSequence() {
    return this.#injectionSequence;
  }

  get injectionBlocker() {
    return this.#injectionBlocker;
  }

  async initialize() {
    await mkdir(dirname(this.#paths.events), { recursive: true });
    await this.#enqueue(async () => {
      await this.#appendEvent({
        eventType: "STATE_INITIALIZED",
        payload: { snapshotSequence: this.#sequence },
      });
      await this.#writeLatest();
      await this.#emitSnapshotUpdated();
    });
  }

  async flush() {
    await this.#writeChain;
  }

  async recordLifecycle(eventType, payload = {}) {
    return this.#enqueue(async () => {
      await this.#appendEvent({ eventType, payload });
    });
  }

  async applyAction(action) {
    return this.#enqueue(async () => {
      const actionId = typeof action?.actionId === "string" ? action.actionId.trim() : "";
      const payloadHash = actionPayloadHash(action);
      const prior = actionId ? this.#actionReceipts.get(actionId) : null;
      if (prior) {
        if (prior.payloadHash === payloadHash) {
          await this.#appendEvent({
            eventType: "STATE_ACTION",
            payload: {
              actionId,
              tool: action.tool,
              expectedSequence: action.expectedSequence,
              clientSequence: action.clientSequence,
              decision: prior.decision.decision,
              reason: prior.decision.reason,
              replayed: true,
            },
          });
          return { ...structuredClone(prior.decision), replayed: true };
        }
        const conflict = reject("conflicting replay for actionId");
        await this.#appendEvent({
          eventType: "STATE_ACTION",
          payload: {
            actionId,
            tool: action.tool,
            expectedSequence: action.expectedSequence,
            clientSequence: action.clientSequence,
            decision: conflict.decision,
            reason: conflict.reason,
          },
        });
        return conflict;
      }
      let decision;
      try {
        if (this.#protocol === "rd-agent-state/v2") {
          validateV2Action(action);
        }
        decision = evaluateAction(this.#snapshot, action, this.#sequence);
        if (decision.decision === "ACCEPTED" && this.#protocol === "rd-agent-state/v2") {
          const candidate = structuredClone(decision.snapshot);
          candidate.sequence = this.#sequence + 1;
          candidate.generatedAtEpochMillis = Date.now();
          validateV2Candidate(candidate, this.#maxInjectedStateBytes);
          decision = accept(candidate);
        }
      } catch (error) {
        decision = reject(String(error?.message ?? error));
      }
      const event = {
        eventType: "STATE_ACTION",
        payload: {
          actionId: action.actionId,
          tool: action.tool,
          expectedSequence: action.expectedSequence,
          clientSequence: action.clientSequence,
          decision: decision.decision,
          reason: decision.reason,
        },
      };
      await this.#appendEvent(event);
      if (decision.decision === "ACCEPTED") {
        this.#sequence += 1;
        this.#snapshot = decision.snapshot;
        this.#snapshot.sequence = this.#sequence;
        if (this.#protocol === "rd-agent-state/v2") {
          this.#snapshot.generatedAtEpochMillis = Date.now();
        } else {
          this.#snapshot.generatedAt = new Date().toISOString();
        }
        await this.#writeLatest();
        await this.#emitSnapshotUpdated();
      }
      const result = { ...decision, sequence: this.#sequence };
      if (actionId) {
        this.#actionReceipts.set(actionId, { payloadHash, decision: structuredClone(result) });
      }
      return result;
    });
  }

  async updateFromToolResult({ toolName, isError, error, fingerprint }) {
    return this.#enqueue(async () => {
      const recentErrors = [...(this.#snapshot.recentErrors ?? [])];
      if (isError) {
        recentErrors.unshift({
          toolName,
          error: boundedText(redactDisplayText(String(error ?? "")), 512),
          fingerprint: fingerprint ?? "",
          at: new Date().toISOString(),
        });
      }
      this.#snapshot = {
        ...this.#snapshot,
        recentErrors: recentErrors.slice(0, 8),
        toolCounts: incrementToolCount(this.#snapshot.toolCounts, toolName, !isError),
      };
      this.#advanceSequence();
      await this.#appendEvent({
        eventType: "TOOL_RESULT_RECORDED",
        payload: { toolName, isError: Boolean(isError), fingerprint: fingerprint ?? "" },
      });
      await this.#writeLatest();
      await this.#emitSnapshotUpdated();
    });
  }

  async projectTerminalResult({ status, reason = "" } = {}) {
    const normalizedStatus = normalizeTerminalResultStatus(status);
    return this.#enqueue(async () => {
      if (this.#snapshot.resultStatus !== "PENDING") {
        return { projected: false, reason: "resultStatus already terminal" };
      }
      const boundedReason = boundedText(reason, 512);
      this.#snapshot = {
        ...this.#snapshot,
        resultStatus: normalizedStatus,
        blocker: boundedReason || this.#snapshot.blocker,
      };
      this.#advanceSequence();
      await this.#appendEvent({
        eventType: "STATE_RESULT_PROJECTED",
        payload: { status: normalizedStatus, reason: boundedReason },
      });
      await this.#writeLatest();
      await this.#emitSnapshotUpdated();
      return { projected: true, status: normalizedStatus, sequence: this.#sequence };
    });
  }

  async recordContextInjected({
    hash,
    bytes,
    injectionSequence,
    stateSequence,
    stateHash,
    promptHash,
    blockHash,
    injectedBlock,
    injectedAt,
    idempotencyKey,
  }) {
    return this.#enqueue(async () => {
      const payload = this.#protocol === "rd-agent-state/v2" ? {
        injectionSequence,
        stateSequence,
        stateHash,
        promptHash,
        blockHash,
        injectedBlock,
        injectedAt,
        idempotencyKey,
        bytes,
      } : {
        sequence: this.#sequence,
        hash: boundedText(hash, 128),
        bytes: positiveInteger(bytes, 0),
      };
      await this.#appendEvent({
        eventType: "STATE_CONTEXT_INJECTED",
        payload,
      });
      if (this.#protocol === "rd-agent-state/v2") {
        await this.#writeEffectiveContext(payload);
      }
    });
  }

  prepareInjection({ promptHash = null } = {}) {
    const bounded = boundSnapshotForInjection(this.#snapshot, this.#maxInjectedStateBytes);
    if (!bounded.ok) {
      this.#injectionBlocker = bounded.reason;
      throw new Error(`STATE_INJECTION_BLOCKED: ${bounded.reason}`);
    }
    const json = this.#protocol === "rd-agent-state/v2"
      ? canonicalizeStateV2(bounded.snapshot)
      : JSON.stringify(bounded.snapshot);
    const stateHash = this.#protocol === "rd-agent-state/v2"
      ? hashStateV2(bounded.snapshot)
      : `sha256:${sha256Hex(json)}`;
    const nextInjectionSequence = this.#injectionSequence + 1;
    if (this.#protocol === "rd-agent-state/v2" && promptHash !== null
        && !/^sha256:[a-f0-9]{64}$/.test(promptHash)) {
      throw new Error("STATE_INJECTION_BLOCKED: promptHash must be a lowercase SHA-256 hash");
    }
    const text = this.#protocol === "rd-agent-state/v2"
      ? `<rd-agent-state protocol="${this.#protocol}" state-sequence="${this.#sequence}" injection-sequence="${nextInjectionSequence}" state-hash="${stateHash}">\n${json}\n</rd-agent-state>`
      : `<rd-agent-state protocol="${this.#protocol}" sequence="${this.#sequence}">\n${json}\n</rd-agent-state>`;
    const bytes = Buffer.byteLength(text, "utf8");
    if (bytes > this.#maxInjectedStateBytes) {
      this.#injectionBlocker = `injected block exceeds maxInjectedStateBytes=${this.#maxInjectedStateBytes}`;
      throw new Error(`STATE_INJECTION_BLOCKED: ${this.#injectionBlocker}`);
    }
    const hash = sha256Hex(text);
    const blockHash = `sha256:${hash}`;
    const injectedAt = new Date().toISOString();
    const idempotencyKey = `sha256:${sha256Hex(`${this.#identity.stageRunId}:${nextInjectionSequence}:${blockHash}`)}`;
    this.#injectionSequence = nextInjectionSequence;
    return {
      sequence: this.#sequence,
      stateSequence: this.#sequence,
      injectionSequence: this.#injectionSequence,
      stateHash,
      promptHash,
      blockHash,
      injectedBlock: text,
      injectedAt,
      idempotencyKey,
      hash,
      bytes,
      text,
      message: {
        role: "custom",
        customType: STATE_CUSTOM_TYPE,
        display: false,
        content: [{ type: "text", text }],
      },
    };
  }

  buildInjectionMessage() {
    return this.prepareInjection().message;
  }

  getInjectionText() {
    return this.buildInjectionMessage().content[0].text;
  }

  #enqueue(task) {
    this.#writeChain = this.#writeChain.then(task);
    return this.#writeChain;
  }

  #advanceSequence() {
    this.#sequence += 1;
    this.#snapshot.sequence = this.#sequence;
    if (this.#protocol === "rd-agent-state/v2") {
      this.#snapshot.generatedAtEpochMillis = Date.now();
    } else {
      this.#snapshot.generatedAt = new Date().toISOString();
    }
  }

  async #emitSnapshotUpdated() {
    if (this.#protocol !== "rd-agent-state/v2" || !this.#onSnapshotUpdated) return;
    const stateHash = hashStateV2(this.#snapshot);
    const projectedAt = new Date().toISOString();
    const payload = {
      stateSequence: this.#sequence,
      stateHash,
      snapshot: structuredClone(this.#snapshot),
      projectedAt,
      idempotencyKey: `sha256:${sha256Hex(`${this.#identity.stageRunId}:${this.#sequence}:${stateHash}`)}`,
    };
    try {
      await this.#onSnapshotUpdated(payload);
    } catch {
      // observability must not roll back an already committed Agent snapshot
    }
  }

  async #appendEvent({ eventType, payload }) {
    const line = JSON.stringify({
      protocol: this.#protocol,
      eventType,
      sequence: this.#sequence,
      ...identityFields(this.#identity),
      at: new Date().toISOString(),
      payload,
    });
    await appendFile(this.#paths.events, `${line}\n`, "utf8");
  }

  async #writeLatest() {
    const temporary = `${this.#paths.latest}.tmp-${process.pid}`;
    const content = this.#protocol === "rd-agent-state/v2"
      ? canonicalizeStateV2(this.#snapshot)
      : `${JSON.stringify(this.#snapshot)}\n`;
    await writeFile(temporary, content, { encoding: "utf8", mode: 0o600 });
    await rename(temporary, this.#paths.latest);
  }

  async #writeEffectiveContext(payload) {
    const artifact = {
      protocol: "rd-agent-effective-context/v1",
      ...identityFields(this.#identity),
      injectionSequence: payload.injectionSequence,
      stateSequence: payload.stateSequence,
      stateHash: payload.stateHash,
      promptHash: payload.promptHash,
      blockHash: payload.blockHash,
      injectedBlock: payload.injectedBlock,
      injectedAt: payload.injectedAt,
      idempotencyKey: payload.idempotencyKey,
      bytes: payload.bytes,
      compositionOrder: ["PROMPT_SNAPSHOT", "AGENT_STATE_BLOCK"],
    };
    const temporary = `${this.#paths.effectiveContext}.tmp-${process.pid}`;
    await writeFile(temporary, canonicalizeStateV2(artifact), { encoding: "utf8", mode: 0o600 });
    await rename(temporary, this.#paths.effectiveContext);
  }
}

export function createInitialSnapshot(identity) {
  const now = new Date().toISOString();
  return {
    protocol: AGENT_STATE_PROTOCOL,
    sequence: 0,
    generatedAt: now,
    ...identityFields(identity),
    todos: [],
    facts: [],
    acceptance: [],
    recentErrors: [],
    toolCounts: {},
    blocker: null,
    resultStatus: "PENDING",
  };
}

function validateInitialState(initialState, identity) {
  if (!initialState || typeof initialState !== "object" || Array.isArray(initialState)) {
    throw new Error("initialState must be an object");
  }
  if (initialState.protocol !== "rd-agent-state/v2") {
    throw new Error("initialState protocol must be rd-agent-state/v2");
  }
  if (!Number.isSafeInteger(initialState.sequence) || initialState.sequence < 0) {
    throw new Error("initialState sequence must be a non-negative safe integer");
  }
  if (initialState.taskId !== identity.taskId
      || initialState.stageRunId !== identity.stageRunId
      || initialState.role !== identity.role
      || initialState.attemptNo !== identity.attemptNo) {
    throw new Error("initialState identity does not match projector identity");
  }
  return structuredClone(initialState);
}

export function evaluateAction(snapshot, action, currentSequence) {
  const base = structuredClone(snapshot);
  if (action.expectedSequence !== currentSequence) {
    return reject("stale expectedSequence");
  }
  if (!action.actionId || !action.tool) {
    return reject("actionId and tool are required");
  }

  switch (action.tool) {
    case "rd_todo_rewrite":
      return evaluateTodoRewrite(base, action);
    case "rd_todo_update_status":
      return evaluateTodoUpdate(base, action);
    case "rd_record_fact":
      return evaluateRecordFact(base, action);
    default:
      return reject(`unsupported state tool: ${action.tool}`);
  }
}

function evaluateTodoRewrite(snapshot, action) {
  const todos = Array.isArray(action.todos) ? action.todos : null;
  if (!todos || todos.length === 0) return reject("todos must be a non-empty array");
  const ids = new Set();
  const v2 = snapshot.protocol === "rd-agent-state/v2";
  const hostTodos = v2 ? (snapshot.todos ?? []).filter((todo) => todo.owner === "HOST") : [];
  if (v2) {
    const incoming = new Map(todos.map((todo) => [todo?.id ?? todo?.todoId, todo]));
    for (const hostTodo of hostTodos) {
      const hostId = hostTodo.todoId ?? hostTodo.id;
      const echoed = incoming.get(hostId);
      if (!echoed) return reject(`Host TODO must not be deleted: ${hostId}`);
      if (echoed.title !== hostTodo.title || String(echoed.status).toUpperCase() !== hostTodo.status) {
        return reject(`Host TODO is immutable except through status update: ${hostId}`);
      }
    }
  }
  const normalized = [];
  for (const item of todos) {
    if (!item || typeof item !== "object") return reject("each todo must be an object");
    const id = requireString(item.id ?? item.todoId, "todo.id");
    if (ids.has(id)) return reject(`duplicate todo id: ${id}`);
    ids.add(id);
    const hostTodo = hostTodos.find((todo) => (todo.todoId ?? todo.id) === id);
    if (hostTodo) {
      normalized.push(structuredClone(hostTodo));
      continue;
    }
    const status = requireString(item.status, "todo.status").toUpperCase();
    if (!TODO_STATUSES.has(status)) return reject(`invalid todo status: ${status}`);
    normalized.push(v2 ? {
      todoId: id,
      owner: "AGENT",
      kind: "AGENT_WORK",
      title: String(item.title ?? id),
      status,
      required: false,
      acceptanceCriteriaId: "",
      acceptanceContentHash: "",
      evidenceArtifactIds: [],
      acceptanceRefs: stringArray(item.acceptanceRefs),
      reason: String(action.reason ?? ""),
    } : {
      id,
      title: boundedText(item.title ?? id, 256),
      status,
      acceptanceRefs: stringArray(item.acceptanceRefs),
      reason: boundedText(action.reason ?? "", 512),
    });
  }
  snapshot.todos = normalized;
  return accept(snapshot);
}

function evaluateTodoUpdate(snapshot, action) {
  const todoId = requireString(action.todoId, "todoId");
  const targetStatus = requireString(action.targetStatus, "targetStatus").toUpperCase();
  if (!TODO_STATUSES.has(targetStatus)) return reject(`invalid target status: ${targetStatus}`);
  const todo = (snapshot.todos ?? []).find((item) => (item.todoId ?? item.id) === todoId);
  if (!todo) return reject(`unknown todo id: ${todoId}`);
  const allowed = TODO_TRANSITIONS[todo.status] ?? new Set();
  if (!allowed.has(targetStatus)) {
    return reject(`illegal transition ${todo.status} -> ${targetStatus}`);
  }
  if (todo.owner === "HOST" && todo.required && targetStatus === "CANCELLED") {
    return reject("required Host TODO cannot be cancelled");
  }
  if (targetStatus === "DONE") {
    const evidence = stringArray(action.evidenceRefs);
    if (evidence.length === 0) return reject("DONE requires at least one evidence reference");
    if (todo.owner === "HOST" && todo.acceptanceCriteriaId) {
      const prefix = `acceptance:${todo.acceptanceCriteriaId}:`;
      if (!evidence.some((reference) => reference.startsWith(prefix) && reference.length > prefix.length)) {
        return reject(`DONE requires evidence scoped to ${todo.acceptanceCriteriaId}`);
      }
    }
    if (snapshot.protocol === "rd-agent-state/v2") {
      todo.evidenceArtifactIds = evidence;
    } else {
      todo.evidenceRefs = evidence;
    }
  }
  if (targetStatus === "BLOCKED" && !boundedText(action.blockerReason ?? "", 512)) {
    return reject("BLOCKED requires blockerReason");
  }
  todo.status = targetStatus;
  if (targetStatus === "BLOCKED") {
    todo.blockerReason = boundedText(action.blockerReason, 512);
  }
  return accept(snapshot);
}

function evaluateRecordFact(snapshot, action) {
  const statement = boundedText(action.statement, 512);
  if (!statement) return reject("statement is required");
  const expectedKind = requireString(action.expectedKind ?? "INFERRED", "expectedKind").toUpperCase();
  if (!FACT_KINDS.has(expectedKind)) return reject(`invalid fact kind: ${expectedKind}`);
  const sourceArtifactId = typeof action.sourceArtifactId === "string" ? action.sourceArtifactId.trim() : "";
  const sourceTool = typeof action.sourceTool === "string" ? action.sourceTool.trim() : "";
  let kind = expectedKind;
  if (expectedKind === "OBSERVED") {
    if (!sourceArtifactId && !sourceTool) {
      kind = "INFERRED";
    }
  }
  snapshot.facts = [
    ...(snapshot.facts ?? []),
    {
      id: action.actionId,
      kind,
      statement,
      sourceArtifactId,
      sourceTool,
      freshnessPolicy: boundedText(action.freshnessPolicy ?? "ALWAYS_RECHECK", 64),
      recordedAt: new Date().toISOString(),
    },
  ].slice(-32);
  return accept(snapshot);
}

function validateV2Action(action) {
  if (!action || typeof action !== "object") throw new Error("state action must be an object");
  assertBoundedString(action.actionId, 128, "actionId");
  if (!Number.isSafeInteger(action.expectedSequence) || action.expectedSequence < 0) {
    throw new Error("expectedSequence must be a non-negative safe integer");
  }
  if (!Number.isSafeInteger(action.clientSequence) || action.clientSequence < 0) {
    throw new Error("clientSequence must be a non-negative safe integer");
  }
  if (action.tool === "rd_todo_rewrite") {
    assertBoundedString(action.reason, 512, "reason");
    if (!Array.isArray(action.todos) || action.todos.length > 64) {
      throw new Error("todos exceed protocol item limit");
    }
    action.todos.forEach((todo, index) => {
      assertBoundedString(todo?.id ?? todo?.todoId, 128, `todos[${index}].id`);
      assertBoundedString(todo?.title, 256, `todos[${index}].title`);
    });
  } else if (action.tool === "rd_todo_update_status") {
    assertBoundedString(action.todoId, 128, "todoId");
    if (action.blockerReason !== undefined) {
      assertBoundedString(action.blockerReason, 512, "blockerReason");
    }
  } else if (action.tool === "rd_record_fact") {
    assertBoundedString(action.statement, 512, "statement");
  }
  if (containsSensitiveValue(action)) {
    throw new Error("state action contains sensitive content");
  }
}

function validateV2Candidate(candidate, maxBytes) {
  if (containsSensitiveValue(candidate)) {
    throw new Error("state candidate contains sensitive content");
  }
  const canonical = canonicalizeStateV2(candidate);
  if (Buffer.byteLength(canonical, "utf8") > maxBytes) {
    throw new Error(`state candidate cannot fit injection limit ${maxBytes}`);
  }
}

function assertBoundedString(value, maxChars, field) {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`${field} must be a non-empty string`);
  }
  if (value.length > maxChars) {
    throw new Error(`${field} exceeds protocol limit ${maxChars}`);
  }
}

function containsSensitiveValue(value) {
  const serialized = JSON.stringify(value ?? "");
  return /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/i.test(serialized)
    || /\bAKIA[0-9A-Z]{16}\b/.test(serialized)
    || /\bsk-[A-Za-z0-9_-]{16,}\b/.test(serialized)
    || /\b(?:api[_-]?key|access[_-]?token|password)\s*[:=]\s*[^\s"}]+/i.test(serialized);
}

function actionPayloadHash(action) {
  const jsonSafe = JSON.parse(JSON.stringify(action ?? null));
  return sha256Hex(canonicalizeStateV2(jsonSafe));
}

function accept(snapshot) {
  return { decision: "ACCEPTED", reason: "", snapshot };
}

function reject(reason) {
  return { decision: "REJECTED", reason, snapshot: null };
}

function identityFields(identity) {
  return {
    taskId: identity.taskId,
    stageRunId: identity.stageRunId,
    role: identity.role,
    attemptNo: identity.attemptNo,
  };
}

function validateIdentity(identity) {
  if (!identity || typeof identity !== "object") throw new Error("identity is required");
  return {
    taskId: requireString(identity.taskId, "taskId"),
    stageRunId: requireString(identity.stageRunId, "stageRunId"),
    role: requireString(identity.role, "role"),
    attemptNo: positiveInteger(identity.attemptNo, 1),
  };
}

export function boundSnapshotForInjection(snapshot, maxBytes) {
  const pruned = structuredClone(snapshot);
  if (pruned.protocol === "rd-agent-state/v2") {
    const serialized = canonicalizeStateV2(pruned);
    return Buffer.byteLength(serialized, "utf8") <= maxBytes
      ? { ok: true, snapshot: pruned }
      : { ok: false, reason: `snapshot exceeds maxInjectedStateBytes=${maxBytes}` };
  }
  pruned.todos = (pruned.todos ?? []).filter((todo) => todo.status === "IN_PROGRESS" || todo.status === "BLOCKED")
    .concat((pruned.todos ?? []).filter((todo) => todo.status !== "IN_PROGRESS" && todo.status !== "BLOCKED").slice(-4));
  pruned.facts = (pruned.facts ?? []).slice(-12);
  pruned.recentErrors = (pruned.recentErrors ?? []).slice(0, 3);
  let serialized = JSON.stringify(pruned);
  if (Buffer.byteLength(serialized, "utf8") <= maxBytes) {
    return { ok: true, snapshot: pruned };
  }
  pruned.todos = (pruned.todos ?? []).filter((todo) => todo.status === "IN_PROGRESS" || todo.status === "BLOCKED");
  pruned.facts = (pruned.facts ?? []).filter((fact) => fact.kind === "OBSERVED").slice(-6);
  pruned.toolCounts = undefined;
  serialized = JSON.stringify(pruned);
  if (Buffer.byteLength(serialized, "utf8") <= maxBytes) {
    return { ok: true, snapshot: pruned };
  }
  return {
    ok: false,
    reason: `snapshot exceeds maxInjectedStateBytes=${maxBytes}`,
  };
}

function incrementToolCount(counts, toolName, success) {
  const next = { ...(counts ?? {}) };
  const key = success ? `${toolName}:success` : `${toolName}:error`;
  next[key] = (next[key] ?? 0) + 1;
  return next;
}

function requireString(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${field} must be a non-empty string`);
  }
  return value.trim();
}

function stringArray(value) {
  if (!Array.isArray(value)) return [];
  return value.filter((item) => typeof item === "string" && item.trim() !== "").map((item) => item.trim());
}

function boundedText(value, maxChars) {
  const text = String(value ?? "");
  return text.length <= maxChars ? text : `${text.slice(0, maxChars)}…`;
}

function positiveInteger(value, fallback) {
  return Number.isInteger(value) && value > 0 ? value : fallback;
}

function normalizeTerminalResultStatus(status) {
  const normalized = String(status ?? "FAILED").trim().toUpperCase();
  if (!TERMINAL_RESULT_STATUSES.has(normalized)) {
    throw new Error(`unsupported terminal result status: ${status}`);
  }
  return normalized;
}

function sha256Hex(value) {
  return createHash("sha256").update(String(value), "utf8").digest("hex");
}
