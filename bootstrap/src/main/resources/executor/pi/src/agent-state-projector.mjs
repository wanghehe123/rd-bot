import { appendFile, mkdir, writeFile, rename } from "node:fs/promises";
import { dirname, join } from "node:path";

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

export function stateOutputPaths(outputPath = "/work/output") {
  return {
    events: join(outputPath, "agent-state-events.jsonl"),
    latest: join(outputPath, "agent-state-latest.json"),
  };
}

export class AgentStateProjector {
  #identity;
  #paths;
  #maxInjectedStateBytes;
  #writeChain = Promise.resolve();
  #sequence = 0;
  #snapshot;
  #injectionBlocker = null;

  constructor({ identity, outputPath = "/work/output", maxInjectedStateBytes = 8192 }) {
    this.#identity = validateIdentity(identity);
    this.#paths = stateOutputPaths(outputPath);
    this.#maxInjectedStateBytes = positiveInteger(maxInjectedStateBytes, 8192);
    this.#snapshot = createInitialSnapshot(this.#identity);
  }

  get sequence() {
    return this.#sequence;
  }

  get snapshot() {
    return structuredClone(this.#snapshot);
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
    const decision = evaluateAction(this.#snapshot, action, this.#sequence);
    return this.#enqueue(async () => {
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
        this.#snapshot.generatedAt = new Date().toISOString();
        await this.#writeLatest();
      }
      return decision;
    });
  }

  async updateFromToolResult({ toolName, isError, error, fingerprint }) {
    return this.#enqueue(async () => {
      const recentErrors = [...(this.#snapshot.recentErrors ?? [])];
      if (isError) {
        recentErrors.unshift({
          toolName,
          error: boundedText(error, 512),
          fingerprint: fingerprint ?? "",
          at: new Date().toISOString(),
        });
      }
      this.#snapshot = {
        ...this.#snapshot,
        recentErrors: recentErrors.slice(0, 8),
        toolCounts: incrementToolCount(this.#snapshot.toolCounts, toolName, !isError),
      };
      await this.#appendEvent({
        eventType: "TOOL_RESULT_RECORDED",
        payload: { toolName, isError: Boolean(isError), fingerprint: fingerprint ?? "" },
      });
      await this.#writeLatest();
    });
  }

  buildInjectionMessage() {
    const bounded = boundSnapshotForInjection(this.#snapshot, this.#maxInjectedStateBytes);
    if (!bounded.ok) {
      this.#injectionBlocker = bounded.reason;
      throw new Error(`STATE_INJECTION_BLOCKED: ${bounded.reason}`);
    }
    const json = JSON.stringify(bounded.snapshot);
    const text = `<rd-agent-state protocol="${AGENT_STATE_PROTOCOL}" sequence="${this.#sequence}">\n${json}\n</rd-agent-state>`;
    return {
      role: "custom",
      customType: STATE_CUSTOM_TYPE,
      display: false,
      content: [{ type: "text", text }],
    };
  }

  getInjectionText() {
    return this.buildInjectionMessage().content[0].text;
  }

  #enqueue(task) {
    this.#writeChain = this.#writeChain.then(task);
    return this.#writeChain;
  }

  async #appendEvent({ eventType, payload }) {
    const line = JSON.stringify({
      protocol: AGENT_STATE_PROTOCOL,
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
    await writeFile(temporary, `${JSON.stringify(this.#snapshot)}\n`, { encoding: "utf8", mode: 0o600 });
    await rename(temporary, this.#paths.latest);
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
  const normalized = [];
  for (const item of todos) {
    if (!item || typeof item !== "object") return reject("each todo must be an object");
    const id = requireString(item.id, "todo.id");
    if (ids.has(id)) return reject(`duplicate todo id: ${id}`);
    ids.add(id);
    const status = requireString(item.status, "todo.status").toUpperCase();
    if (!TODO_STATUSES.has(status)) return reject(`invalid todo status: ${status}`);
    normalized.push({
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
  const todo = (snapshot.todos ?? []).find((item) => item.id === todoId);
  if (!todo) return reject(`unknown todo id: ${todoId}`);
  const allowed = TODO_TRANSITIONS[todo.status] ?? new Set();
  if (!allowed.has(targetStatus)) {
    return reject(`illegal transition ${todo.status} -> ${targetStatus}`);
  }
  if (targetStatus === "DONE") {
    const evidence = stringArray(action.evidenceRefs);
    if (evidence.length === 0) return reject("DONE requires at least one evidence reference");
    todo.evidenceRefs = evidence;
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
