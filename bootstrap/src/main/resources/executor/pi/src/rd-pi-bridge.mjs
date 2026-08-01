import { pathToFileURL } from "node:url";
import { appendFile, mkdir, readFile, writeFile, rename, access } from "node:fs/promises";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { dirname, join, resolve } from "node:path";

import { Type } from "typebox";
import {
  createAgentSession,
  defineTool,
  ModelRuntime,
  resolveCliModel,
  SessionManager,
  SettingsManager,
} from "@earendil-works/pi-coding-agent";

import {
  EVENT_TYPES,
  MAX_LINE_BYTES,
  boundedText,
  parseJsonLine,
  redact,
  validateRequest,
} from "./protocol.mjs";
import { EventNormalizer } from "./event-normalizer.mjs";
import {
  contextFileMetadata,
  createApprovedResourceLoader,
  loadResourceManifest,
} from "./resource-loader.mjs";
import { validateResult, validateRoleResult, writeResultAtomically } from "./result-tool.mjs";
import { AgentStateProjector } from "./agent-state-projector.mjs";
import { createAgentStateTools, STATE_TOOL_NAMES } from "./agent-state-tools.mjs";
import { createDynamicStateExtension } from "./context-state-injection.mjs";

const RESULT_TOOL_NAME = "rd_submit_result";
const DEFAULT_OUTPUT_PATH = "/work/output";
const DEFAULT_MAX_RAW_EVENT_BYTES = 16 * 1024 * 1024;
const DEFAULT_BASH_COMMAND_TIMEOUT_MILLIS = 15 * 60 * 1000;
const FAILURE_RESULT = {
  status: "FAILED",
  summary: "Pi bridge failed before a structured agent result was accepted",
  failureCategory: "PI_BRIDGE_PROTOCOL",
};

/** Run one immutable request. The only stdout contract is normalized LF JSONL. */
export async function run(options = {}) {
  let request;
  try {
    request = await readValidatedRequest(options.requestPath ?? "/work/input/request.json");
  } catch (error) {
    console.error(`[rd-pi-bridge] invalid request: ${safeError(error)}`);
    return 2;
  }

  const paths = outputPaths(request.outputPath);
  await mkdir(paths.output, { recursive: true });
  await mkdir(paths.private, { recursive: true, mode: 0o700 });
  await mkdir(paths.session, { recursive: true, mode: 0o700 });
  const context = {
    stageRunId: request.stageRunId,
    taskId: request.taskId,
    role: request.role,
    runtimeType: "PI",
    snapshotId: request.snapshotId,
    provider: request.provider,
    model: request.model,
  };
  const sink = new EventSink(paths, context, maxRawEventBytes());
  const startedAt = new Date().toISOString();
  let session;
  let unsubscribe;
  let settled = false;
  let resultAccepted = false;
  let protocolSucceeded = false;
  let failure;
  let verifiedManifest;
  let sessionFile;
  let contextFiles = [];
  let stateProjector;

  try {
    await sink.lifecycle("RUNTIME_READY", {
      runtime: "PI",
      provider: request.provider,
      model: request.model,
      snapshotId: request.snapshotId,
    });

    verifiedManifest = await loadResourceManifest(request.resourceManifestPath);
    const settingsManager = safeSettingsManager();
    const dynamicState = resolveDynamicStateConfig(request);
    if (dynamicState.enabled) {
      assertStateToolsPermitted(request.toolPolicy);
    }
    const observability = createObservabilityExtension(sink, context, bashCommandTimeoutMillis(), {
      maxAgentTurns: request.maxAgentTurns,
      maxTotalTokens: request.maxTotalTokens,
    });
    const budgetControls = observability.budgetControls;
    const extensionFactories = [observability];
    let stateTools = [];
    if (dynamicState.enabled) {
      stateProjector = new AgentStateProjector({
        identity: {
          taskId: request.taskId,
          stageRunId: request.stageRunId,
          role: request.role,
          attemptNo: dynamicState.attemptNo,
        },
        outputPath: paths.output,
        maxInjectedStateBytes: dynamicState.maxInjectedStateBytes,
      });
      await stateProjector.initialize();
      stateTools = createAgentStateTools({ projector: stateProjector, sink });
      extensionFactories.push(createDynamicStateExtension({
        projector: stateProjector,
        sink,
        stageRunId: request.stageRunId,
      }));
    }
    const resourceLoader = createApprovedResourceLoader({
      cwd: request.repoPath,
      agentDir: "/work/pi-agent",
      verifiedManifest,
      settingsManager,
      extensionFactories,
    });
    // H1: resource discovery happens once, before session creation. The bridge
    // is one-shot except for a single bounded result-recovery prompt issued when
    // the session settles without submitting the structured result.
    await resourceLoader.reload();
    const extensionsResult = resourceLoader.getExtensions();
    if (extensionsResult.errors.length > 0) {
      for (const diagnostic of extensionsResult.errors) {
        await sink.lifecycle("EXTENSION_FAILED", {
          path: diagnostic.path,
          error: diagnostic.error,
        });
      }
      throw new Error("selected Pi extension failed to load");
    }
    contextFiles = contextFileMetadata(resourceLoader.getAgentsFiles().agentsFiles);
    await sink.lifecycle("RESOURCES_LOADED", {
      extensionSetId: verifiedManifest.manifest.extensionSetId,
      extensionSetVersion: verifiedManifest.manifest.extensionSetVersion,
      extensionPaths: verifiedManifest.extensionPaths,
      contextFiles,
      extensionCount: extensionsResult.extensions.length,
    });
    await writeRuntimeContextManifest({
      request,
      paths,
      contextFiles,
      inputManifestHash: request.inputManifestHash ?? "",
      contextPolicyHash: request.contextPolicy?.policyHash ?? "",
    });

    const modelRuntime = await configureModelRuntime(request, paths.private);
    const resolvedModel = resolveCliModel({
      cliProvider: request.provider,
      cliModel: request.model,
      modelRuntime,
    });
    if (!resolvedModel.model) {
      throw new Error(resolvedModel.error ?? `model was not found: ${request.provider}/${request.model}`);
    }
    const toolNames = resolveToolNames(request.toolPolicy, dynamicState);
    const resultTool = createResultTool({
      resultPath: paths.result,
      sink,
      context,
      stateProjector,
      onAccepted: () => {
        resultAccepted = true;
      },
    });
    const sessionManager = SessionManager.create(request.repoPath, paths.session);
    const created = await createAgentSession({
      cwd: request.repoPath,
      agentDir: "/work/pi-agent",
      modelRuntime,
      model: resolvedModel.model,
      thinkingLevel: request.thinkingLevel,
      tools: toolNames,
      customTools: [resultTool, ...stateTools],
      resourceLoader,
      sessionManager,
      settingsManager,
      sessionStartEvent: { type: "session_start", reason: "startup" },
    });
    session = created.session;
    unsubscribe = session.subscribe((event) => {
      if (event?.type === "agent_settled") settled = true;
      void sink.ingest(event);
    });

    const prompt = executionPrompt(request);
    await session.prompt(prompt);
    await session.waitForIdle();
    await sink.flush();
    if (budgetControls?.isExceeded?.() && !resultAccepted) {
      await acceptBridgeFailureResult(paths, sink, {
        ...FAILURE_RESULT,
        failureCategory: "BUDGET_EXCEEDED",
        summary: "Agent stopped after exhausting the coding-benchmark turn/token budget",
        errorMessage: boundedText(budgetControls.reason(), 4096),
      }, () => {
        resultAccepted = true;
      }, stateProjector);
    } else if (settled && !resultAccepted) {
      // Sessions can settle without the result tool (for example a length-stopped
      // turn with no tool call). Issue exactly one recovery prompt before failing.
      await safeLifecycle(sink, "PROTOCOL_ERROR", {
        category: "PI_RESULT_RECOVERY",
        error: "session settled without rd_submit_result; issuing one recovery prompt",
      });
      settled = false;
      if (budgetControls) budgetControls.ignoreBudget = true;
      await session.prompt(
        `Your session ended without submitting the structured result. Call ${RESULT_TOOL_NAME} now, exactly once, with the COMPLETE role protocol JSON object required by your instructions (all required fields, not just status and summary). Do not run any other tool, do not repeat prior work, and do not print file contents.`,
      );
      await session.waitForIdle();
      await sink.flush();
    }
    if (!resultAccepted) {
      if (!settled) throw new Error("Pi session became idle without agent_settled");
      throw new Error("Pi session settled without rd_submit_result");
    }
    const result = validateResult(JSON.parse(await readFile(paths.result, "utf8")));
    await ensureDeliveryArtifacts(request, paths, result, sink);
    sessionFile = session.sessionManager.getSessionFile();
    await sink.lifecycle("ARTIFACT_WRITTEN", {
      artifact: "result.json",
      path: paths.result,
      status: result.status,
    });
    if (sessionFile) {
      await sink.lifecycle("ARTIFACT_WRITTEN", {
        artifact: "pi-session.jsonl",
        path: sessionFile,
      });
    }
    if (stateProjector) {
      await flushStateArtifacts(stateProjector, paths, sink);
    }
    protocolSucceeded = true;
  } catch (error) {
    failure = error;
    const budgetHit = String(safeError(error)).includes("BUDGET_EXCEEDED");
    await safeLifecycle(sink, "PROTOCOL_ERROR", {
      category: budgetHit ? "BUDGET_EXCEEDED" : "PI_BRIDGE_PROTOCOL",
      error: safeError(error),
    });
    if (!resultAccepted) {
      try {
        await acceptBridgeFailureResult(paths, sink, {
          ...FAILURE_RESULT,
          failureCategory: budgetHit ? "BUDGET_EXCEEDED" : FAILURE_RESULT.failureCategory,
          summary: budgetHit
            ? "Agent stopped after exhausting the coding-benchmark turn/token budget"
            : FAILURE_RESULT.summary,
          errorMessage: boundedText(safeError(error), 4096),
        }, () => {
          resultAccepted = true;
        }, stateProjector);
        try {
          await ensureDeliveryArtifacts(
            request,
            paths,
            JSON.parse(await readFile(paths.result, "utf8")),
            sink,
          );
        } catch (artifactError) {
          console.error(`[rd-pi-bridge] failed to materialize delivery artifacts: ${safeError(artifactError)}`);
        }
      } catch (resultError) {
        console.error(`[rd-pi-bridge] failed to write failure result: ${safeError(resultError)}`);
      }
    }
  } finally {
    if (stateProjector) {
      try {
        if (stateProjector.snapshot.resultStatus === "PENDING") {
          await stateProjector.projectTerminalResult({
            status: "FAILED",
            reason: protocolSucceeded
              ? "runtime stopped before terminal result projection"
              : boundedText(safeError(failure), 512),
          });
        }
        await flushStateArtifacts(stateProjector, paths, sink);
      } catch (flushError) {
        console.error(`[rd-pi-bridge] failed to flush agent state artifacts: ${safeError(flushError)}`);
      }
    }
    if (unsubscribe) unsubscribe();
    if (session) session.dispose();
    await safeLifecycle(sink, "RUNTIME_STOPPED", {
      runtime: "PI",
      settled,
      resultAccepted,
      status: protocolSucceeded ? "COMPLETED" : "FAILED",
    });
    await safeWriteJson(paths.runtimeMeta, {
      protocol: "rd-pi-runtime-meta/v1",
      runtime: "PI",
      snapshotId: request.snapshotId,
      stageRunId: request.stageRunId,
      taskId: request.taskId,
      role: request.role,
      provider: request.provider,
      model: request.model,
      extensionSetId: verifiedManifest?.manifest.extensionSetId ?? "",
      extensionSetVersion: verifiedManifest?.manifest.extensionSetVersion ?? 0,
      contextFiles,
      sessionFile: sessionFile ?? "",
      startedAt,
      finishedAt: new Date().toISOString(),
      settled,
      resultAccepted,
      status: protocolSucceeded ? "COMPLETED" : "FAILED",
      rawEvents: sink.rawEventStats(),
      failure: failure ? boundedText(safeError(failure), 4096) : "",
    });
    try {
      await sink.flush();
    } catch (error) {
      console.error(`[rd-pi-bridge] output flush failed: ${safeError(error)}`);
      protocolSucceeded = false;
    }
  }
  return (protocolSucceeded || resultAccepted) ? 0 : 1;
}

async function acceptBridgeFailureResult(paths, sink, result, onAccepted, stateProjector) {
  await writeResultAtomically(paths.result, result);
  onAccepted();
  if (stateProjector) {
    await stateProjector.projectTerminalResult({
      status: result.status ?? "FAILED",
      reason: result.summary ?? result.errorMessage ?? "",
    });
  }
  await safeLifecycle(sink, "RESULT_SUBMITTED", {
    status: result.status,
    summary: boundedText(result.summary, 4096),
    source: "bridge-budget-or-protocol",
  });
}

export function executionPrompt(request) {
  const role = request.role;
  const common = [
    "This is a one-shot RD-Bot execution.",
    `You must finish by calling ${RESULT_TOOL_NAME} exactly once with the complete role protocol JSON object required by your instructions; submissions missing required fields are rejected and must be resubmitted.`,
    "Do not write result.json yourself; the bridge accepts results only through the tool.",
    "Use SUCCESS only when the requested work and acceptance checks are complete. Use FAILED, NEED_INFO, or UNSAFE when they are not.",
  ];
  const roleInstructions = roleArtifactInstructions(role, request);
  return `${request.prompt}\n\n` + [...common, ...roleInstructions].join("\n");
}

function roleArtifactInstructions(role, request = {}) {
  const patchPath = typeof request.patchArtifactPath === "string" && request.patchArtifactPath.trim()
    ? request.patchArtifactPath.trim()
    : "/work/output/patch.diff";
  switch (role) {
    case "CODING_AGENT":
      return [
        "Reuse the existing dependency tree and /work/cache. Never delete node_modules or package-lock.json; only install a genuinely missing dependency once, using the existing lockfile, and preserve the first failure diagnostic instead of repeating cleanup and install cycles.",
        "For Next.js delivery verification, use npm run build && npm run start; do not use npm run dev as the acceptance server.",
        "Every HTTP probe must set a request timeout of 30 seconds or less. Every bash call receives a hard deadline; when a probe or command times out, stop the temporary server, preserve its log, and submit FAILED rather than waiting indefinitely.",
        `Before submitting a SUCCESS result, write the unified git diff (including untracked new files; prefer git add -A then git diff --cached --binary) to ${patchPath} and a concise test log to /work/output/test.log.`,
      ];
    case "REQUIREMENT_REVIEWER":
    case "SOLUTION_ARCHITECT":
      return [
        "Before submitting a SUCCESS result, write the downstream handoff markdown to /work/output/handoff/next.md.",
        `Your submitted result JSON must also satisfy the ${role} role protocol; include every protocol field at the top level of the result object alongside status and summary.`,
      ];
    case "QA_AGENT":
      return [
        "Run the acceptance and regression checks yourself and record real evidence; do not fabricate test output.",
        "Your submitted result JSON must satisfy the QA_AGENT role protocol: include the QA report fields and one evidence entry per acceptance criterion at the top level of the result object alongside status and summary.",
        "For QA the result status MUST be one of PASSED, FAILED, or SKIPPED (not SUCCESS): use PASSED when every acceptance criterion is verified, FAILED otherwise, SKIPPED only when validation cannot run.",
      ];
    default:
      return [];
  }
}

const execFileAsync = promisify(execFile);

async function fileExists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

/** Deterministic, role-aware fallback so a SUCCESS result always ships its required artifacts. */
async function ensureDeliveryArtifacts(request, paths, result, sink) {
  if (result.status !== "SUCCESS") return;
  switch (request.role) {
    case "CODING_AGENT":
      await ensureCodingArtifacts(request, paths, result, sink);
      return;
    case "REQUIREMENT_REVIEWER":
    case "SOLUTION_ARCHITECT":
      await ensureHandoffArtifact(request, paths, result, sink);
      return;
    case "QA_AGENT":
      // QA evidence must be real; the bridge never fabricates it. The Java-side
      // QaEvidenceBundleValidator fails closed when evidence is missing.
      return;
    default:
      return;
  }
}

async function ensureCodingArtifacts(request, paths, result, sink) {
  const configured = typeof request.patchArtifactPath === "string" ? request.patchArtifactPath.trim() : "";
  const patchPath = configured || join(paths.output, "patch.diff");
  const artifactName = configured ? configured.slice("/work/output/".length) : "patch.diff";
  if (!(await fileExists(patchPath))) {
    try {
      const diff = await materializeRepoDiff(request.repoPath);
      await writeFile(patchPath, diff, "utf8");
      await sink.lifecycle("ARTIFACT_WRITTEN", {
        artifact: artifactName,
        path: patchPath,
        source: "bridge-git-diff",
      });
    } catch (error) {
      console.error(`[rd-pi-bridge] failed to materialize ${artifactName}: ${safeError(error)}`);
    }
  }
  // Keep legacy patch.diff populated when the coding-benchmark path is used, so
  // older host tooling that still looks for patch.diff keeps working.
  const legacyPatch = join(paths.output, "patch.diff");
  if (patchPath !== legacyPatch && (await fileExists(patchPath)) && !(await fileExists(legacyPatch))) {
    try {
      const content = await readFile(patchPath, "utf8");
      await writeFile(legacyPatch, content, "utf8");
    } catch (error) {
      console.error(`[rd-pi-bridge] failed to mirror patch.diff: ${safeError(error)}`);
    }
  }
  const testLogPath = join(paths.output, "test.log");
  if (!(await fileExists(testLogPath))) {
    const lines = [
      `testStatus: ${result.testStatus ?? ""}`,
      `testCommands: ${(result.testCommands ?? []).join(" && ")}`,
      `summary: ${result.summary ?? ""}`,
      "note: generated by rd-pi-bridge from the structured result; the agent did not write test.log directly.",
    ];
    try {
      await writeFile(testLogPath, lines.join("\n") + "\n", "utf8");
      await sink.lifecycle("ARTIFACT_WRITTEN", { artifact: "test.log", path: testLogPath, source: "bridge-result-metadata" });
    } catch (error) {
      console.error(`[rd-pi-bridge] failed to materialize test.log: ${safeError(error)}`);
    }
  }
}

async function materializeRepoDiff(repoPath) {
  const { mkdtemp, rm } = await import("node:fs/promises");
  const { tmpdir } = await import("node:os");
  const indexDir = await mkdtemp(join(tmpdir(), "rd-pi-index-"));
  const indexFile = join(indexDir, "index");
  const env = { ...process.env, GIT_INDEX_FILE: indexFile };
  try {
    await execFileAsync("git", ["-C", repoPath, "read-tree", "HEAD"], { env, maxBuffer: 32 * 1024 * 1024 });
    await execFileAsync(
      "git",
      [
        "-C",
        repoPath,
        "add",
        "-A",
        "--",
        ".",
        ":(exclude)node_modules",
        ":(exclude)target",
        ":(exclude)build",
        ":(exclude).gradle",
        ":(exclude)dist",
      ],
      { env, maxBuffer: 32 * 1024 * 1024 },
    );
    const { stdout } = await execFileAsync(
      "git",
      ["-C", repoPath, "diff", "--cached", "--binary", "HEAD"],
      { env, maxBuffer: 32 * 1024 * 1024 },
    );
    return stdout;
  } finally {
    await rm(indexDir, { recursive: true, force: true });
  }
}

async function ensureHandoffArtifact(request, paths, result, sink) {
  const handoffPath = join(paths.output, "handoff", "next.md");
  if (await fileExists(handoffPath)) return;
  const lines = [
    `# ${request.role} handoff`,
    "",
    `stageRunId: ${request.stageRunId}`,
    `taskId: ${request.taskId}`,
    "",
    "## Summary",
    "",
    String(result.summary ?? "").trim() || "(no summary provided)",
    "",
    "note: generated by rd-pi-bridge from the structured result; the agent did not write handoff/next.md directly.",
  ];
  try {
    await mkdir(dirname(handoffPath), { recursive: true });
    await writeFile(handoffPath, lines.join("\n") + "\n", "utf8");
    await sink.lifecycle("ARTIFACT_WRITTEN", { artifact: "handoff/next.md", path: handoffPath, source: "bridge-result-metadata" });
  } catch (error) {
    console.error(`[rd-pi-bridge] failed to materialize handoff/next.md: ${safeError(error)}`);
  }
}

function outputPaths(outputPath) {
  const output = resolve(outputPath || DEFAULT_OUTPUT_PATH);
  const privatePath = join(output, "private");
  return {
    output,
    private: privatePath,
    session: join(privatePath, "session"),
    rawEvents: join(privatePath, "pi-raw-events.jsonl"),
    runtimeMeta: join(output, "runtime-meta.json"),
    runtimeContextManifest: join(output, "runtime-context-manifest.json"),
    events: join(output, "agent-events.jsonl"),
    result: join(output, "result.json"),
  };
}

/** Writes the audit-only observed runtime context manifest after resource discovery. */
export async function writeRuntimeContextManifest({
  request,
  paths,
  contextFiles,
  inputManifestHash = "",
  contextPolicyHash = "",
}) {
  const repoRoot = resolve(request.repoPath || "/work/repo");
  const observedFiles = (contextFiles ?? []).map((file, index) => {
    const absolutePath = resolve(file.path ?? "");
    const relativePath = absolutePath.startsWith(`${repoRoot}/`)
      ? absolutePath.slice(repoRoot.length + 1)
      : (file.path ?? "");
    const contentHash = file.sha256
      ? (String(file.sha256).startsWith("sha256:") ? String(file.sha256) : `sha256:${file.sha256}`)
      : "";
    return {
      path: relativePath.replace(/\\/g, "/"),
      contentHash,
      bytes: file.bytes ?? 0,
      loadOrder: index + 1,
      scope: "REPO",
      trustDecision: "LOADED",
      rejectReason: "",
    };
  });
  const totalBytes = observedFiles.reduce((sum, file) => sum + (file.bytes ?? 0), 0);
  const manifest = {
    schemaVersion: 1,
    protocol: "rd-runtime-context-manifest/v1",
    taskId: request.taskId,
    stageRunId: request.stageRunId,
    role: request.role,
    attemptNo: request.attemptNo ?? 1,
    mode: "LEGACY_OBSERVE_ONLY",
    runtime: "PI",
    provider: request.provider ?? "",
    model: request.model ?? "",
    executionProfileSnapshotId: request.snapshotId ?? "",
    inputManifestHash: inputManifestHash || "",
    contextPolicyHash: contextPolicyHash || "",
    generatedAt: new Date().toISOString(),
    observedFiles,
    totalFiles: observedFiles.length,
    totalBytes,
    effectiveContextHash: "",
    status: "OBSERVED",
  };
  await safeWriteJson(paths.runtimeContextManifest, manifest);
  return manifest;
}

async function readValidatedRequest(path) {
  const content = await readFile(path, "utf8");
  const parsed = parseJsonLine(content.trim(), MAX_LINE_BYTES);
  return validateRequest(parsed);
}

function safeSettingsManager() {
  return SettingsManager.inMemory(
    {
      compaction: { enabled: false },
      retry: { enabled: true, maxRetries: 2 },
      enableAnalytics: false,
      packages: [],
      extensions: [],
      skills: [],
      prompts: [],
      themes: [],
    },
    { projectTrusted: false },
  );
}

async function configureModelRuntime(request, privatePath) {
  const runtime = await ModelRuntime.create({
    authPath: join(privatePath, "auth.json"),
    modelsPath: null,
    allowModelNetwork: false,
  });
  if (request.baseUrl || request.api) {
    if (!request.baseUrl || !request.api) {
      throw new Error("custom Pi provider requires both api and baseUrl");
    }
    runtime.registerProvider(request.provider, {
      name: request.provider,
      baseUrl: request.baseUrl,
      api: request.api,
      // Bearer-style gateways reject the default x-api-key header; opt in per provider profile.
      authHeader: request.authHeader === true,
      models: [{
        id: request.model,
        name: request.model,
        reasoning: request.reasoning !== false,
        input: ["text"],
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
        contextWindow: positiveNumber(request.contextWindow, 200000),
        maxTokens: positiveNumber(request.maxTokens, 16384),
      }],
    });
  }
  if (request.credentialEnvironmentVariable) {
    const value = process.env[request.credentialEnvironmentVariable];
    if (!value) {
      throw new Error(`credential environment variable is missing: ${request.credentialEnvironmentVariable}`);
    }
    await runtime.setRuntimeApiKey(request.provider, value);
  }
  return runtime;
}

export function resolveDynamicStateConfig(request) {
  const policy = request?.policy && typeof request.policy === "object" ? request.policy : {};
  const enabled = request?.dynamicStateEnabled === true || policy.dynamicStateEnabled === true;
  const maxInjectedStateBytes = positiveSafeInteger(
    request?.maxInjectedStateBytes ?? policy.maxInjectedStateBytes,
    8192,
  );
  const attemptNo = positiveSafeInteger(request?.attemptNo ?? policy.attemptNo, 1);
  return { enabled, maxInjectedStateBytes, attemptNo };
}

function assertStateToolsPermitted(policy) {
  const value = policy && typeof policy === "object" ? policy : {};
  const hostAllow = stringSet(value.hostAllow, []);
  const requested = stringSet(value.allow, [...hostAllow]);
  const denied = stringSet(value.deny, []);
  for (const toolName of STATE_TOOL_NAMES) {
    if (!hostAllow.has(toolName) || !requested.has(toolName) || denied.has(toolName)) {
      throw new Error(`${toolName} is not permitted by the frozen tool policy (dynamicStateEnabled)`);
    }
  }
}

function resolveToolNames(policy, dynamicState = { enabled: false }) {
  const value = policy && typeof policy === "object" ? policy : {};
  const defaultHost = ["read", "bash", "edit", "write", RESULT_TOOL_NAME];
  if (dynamicState.enabled) defaultHost.push(...STATE_TOOL_NAMES);
  const hostAllow = stringSet(value.hostAllow, defaultHost);
  const requested = stringSet(value.allow, [...hostAllow]);
  const denied = stringSet(value.deny, []);
  const active = [...requested].filter((name) => hostAllow.has(name) && !denied.has(name));
  if (!active.includes(RESULT_TOOL_NAME)) {
    throw new Error(`${RESULT_TOOL_NAME} is not permitted by the frozen tool policy`);
  }
  if (dynamicState.enabled) {
    for (const toolName of STATE_TOOL_NAMES) {
      if (!active.includes(toolName)) {
        throw new Error(`${toolName} is not permitted by the frozen tool policy`);
      }
    }
  }
  return active;
}

async function flushStateArtifacts(projector, paths, sink) {
  await projector.flush();
  await safeLifecycle(sink, "ARTIFACT_WRITTEN", {
    artifact: "agent-state-events.jsonl",
    path: join(paths.output, "agent-state-events.jsonl"),
  });
  await safeLifecycle(sink, "ARTIFACT_WRITTEN", {
    artifact: "agent-state-latest.json",
    path: join(paths.output, "agent-state-latest.json"),
  });
}

function stringSet(value, fallback) {
  const values = Array.isArray(value) ? value : fallback;
  return new Set(values.filter((item) => typeof item === "string" && item.trim() !== ""));
}

function createResultTool({ resultPath, sink, context, onAccepted, stateProjector }) {
  return defineTool({
    name: RESULT_TOOL_NAME,
    label: "Submit RD result",
    description: "Submit the final structured RD-Bot execution result. Call once after the work is complete.",
    promptSnippet: "Submit the required structured execution result.",
    parameters: Type.Object({ result: Type.Any() }),
    executionMode: "sequential",
    async execute(_toolCallId, params) {
      try {
        const result = validateResult(params?.result);
        // Reject protocol violations while the agent can still fix them in-session;
        // the host-side validator runs after the container exits and offers no retry.
        const roleErrors = validateRoleResult(context.role, result);
        if (roleErrors.length > 0) {
          throw new Error(`role protocol violations: ${roleErrors.join("; ")}. Fix every listed field and call ${RESULT_TOOL_NAME} again with the complete result.`);
        }
        await writeResultAtomically(resultPath, result);
        onAccepted();
        if (stateProjector) {
          await stateProjector.projectTerminalResult({
            status: result.status,
            reason: boundedText(result.summary, 512),
          });
        }
        await sink.lifecycle("RESULT_SUBMITTED", {
          status: result.status,
          summary: boundedText(result.summary, 4096),
        });
        return {
          content: [{ type: "text", text: "Structured result accepted. Stop and do not submit another result." }],
          details: { status: result.status },
          terminate: true,
        };
      } catch (error) {
        await sink.lifecycle("RESULT_REJECTED", {
          error: safeError(error),
        });
        throw error;
      }
    },
  });
}

export function createObservabilityExtension(sink, context, commandTimeoutMillis = bashCommandTimeoutMillis(), budget = {}) {
  const boundedTimeout = positiveSafeInteger(commandTimeoutMillis, DEFAULT_BASH_COMMAND_TIMEOUT_MILLIS);
  const maxAgentTurns = positiveSafeInteger(
    budget.maxAgentTurns ?? process.env.RD_PI_MAX_AGENT_TURNS,
    0,
  );
  const maxTotalTokens = positiveSafeInteger(
    budget.maxTotalTokens ?? process.env.RD_PI_MAX_TOTAL_TOKENS,
    0,
  );
  let providerTurns = 0;
  let cumulativeUsage = 0;
  let budgetWarned = false;
  let budgetExceeded = false;
  const controls = {
    ignoreBudget: false,
    isExceeded: () => budgetExceeded,
    reason: () => (budgetExceeded
      ? `BUDGET_EXCEEDED: maxAgentTurns=${maxAgentTurns} turns=${providerTurns} cumulativeUsage=${cumulativeUsage}`
      : ""),
  };
  return {
    name: "rd-observability",
    hidden: true,
    budgetControls: controls,
    factory(pi) {
      // Pi's built-in bash tool accepts a millisecond timeout. Mutating the tool
      // input here enforces a host-owned cap even when the model omits timeout.
      pi.on("tool_call", (event) => {
        if (event?.toolName === "bash" && event.input && typeof event.input === "object") {
          const command = typeof event.input.command === "string" ? event.input.command : "";
          if (command.includes("/work/output/private")) {
            throw new Error("TOOL_BLOCKED: /work/output/private is not readable by the agent");
          }
          const requested = positiveSafeInteger(event.input.timeout, boundedTimeout);
          event.input.timeout = Math.min(requested, boundedTimeout);
        }
      });
      pi.on("before_provider_request", (event) => {
        if (controls.ignoreBudget) {
          return sink.lifecycle("PROVIDER_REQUESTED", {
            provider: event.model?.provider,
            model: event.model?.id,
            turn: providerTurns,
            recovery: true,
          });
        }
        providerTurns += 1;
        if (maxAgentTurns > 0 && providerTurns > maxAgentTurns) {
          budgetExceeded = true;
          throw new Error(`BUDGET_EXCEEDED: maxAgentTurns=${maxAgentTurns}`);
        }
        if (maxAgentTurns > 0 && !budgetWarned && providerTurns === Math.max(1, maxAgentTurns - 3)) {
          budgetWarned = true;
          void sink.lifecycle("PROTOCOL_ERROR", {
            category: "BUDGET_WARNING",
            error: `approaching maxAgentTurns=${maxAgentTurns}; submit result immediately`,
          });
        }
        return sink.lifecycle("PROVIDER_REQUESTED", {
          provider: event.model?.provider,
          model: event.model?.id,
          turn: providerTurns,
        });
      });
      pi.on("after_provider_response", (event) => {
        cumulativeUsage += usageTokenTotal(event);
        if (!controls.ignoreBudget && maxTotalTokens > 0 && cumulativeUsage > maxTotalTokens) {
          budgetExceeded = true;
          throw new Error(`BUDGET_EXCEEDED: maxTotalTokens=${maxTotalTokens} cumulativeUsage=${cumulativeUsage}`);
        }
        return sink.lifecycle("PROVIDER_RESPONDED", {
          status: event.status,
          cumulativeUsage,
        });
      });
    },
  };
}

function usageTokenTotal(event) {
  const candidates = [
    event?.usage,
    event?.response?.usage,
    event?.message?.usage,
    event?.result?.usage,
  ];
  let total = 0;
  for (const usage of candidates) {
    if (!usage || typeof usage !== "object") continue;
    for (const key of ["input", "output", "cacheRead", "cacheWrite", "input_tokens", "output_tokens", "prompt_tokens", "completion_tokens"]) {
      const value = usage[key];
      if (typeof value === "number" && Number.isFinite(value) && value > 0) {
        total += value;
      }
    }
  }
  return total;
}

export class EventSink {
  #paths;
  #context;
  #normalizer = new EventNormalizer();
  #writeChain = Promise.resolve();
  #rawEventMaxBytes;
  #rawEventBytes = 0;
  #rawEventTruncated = false;

  constructor(paths, context, rawEventMaxBytes = maxRawEventBytes()) {
    this.#paths = paths;
    this.#context = context;
    this.#rawEventMaxBytes = positiveSafeInteger(rawEventMaxBytes, DEFAULT_MAX_RAW_EVENT_BYTES);
  }

  lifecycle(eventType, payload = {}) {
    if (!EVENT_TYPES.includes(eventType)) throw new Error(`unsupported normalized event type: ${eventType}`);
    return this.#enqueue(this.#normalizer.lifecycle(eventType, this.#context, payload));
  }

  ingest(rawEvent) {
    const normalized = this.#normalizer.next(rawEvent, this.#context);
    const rawLine = boundedJson(redact(rawEvent));
    this.#enqueueRaw(rawLine);
    if (normalized) return this.#enqueue(normalized);
    return this.#writeChain;
  }

  async flush() {
    await this.#writeChain;
  }

  rawEventStats() {
    return {
      maxBytes: this.#rawEventMaxBytes,
      writtenBytes: this.#rawEventBytes,
      truncated: this.#rawEventTruncated,
    };
  }

  #enqueue(event) {
    const line = boundedJson(event);
    this.#writeChain = this.#writeChain.then(async () => {
      await appendFile(this.#paths.events, `${line}\n`, "utf8");
      await writeStdout(`${line}\n`);
    });
    return this.#writeChain;
  }

  #enqueueRaw(line) {
    const payload = `${line}\n`;
    const bytes = Buffer.byteLength(payload, "utf8");
    if (this.#rawEventTruncated || this.#rawEventBytes + bytes > this.#rawEventMaxBytes) {
      this.#rawEventTruncated = true;
      return;
    }
    this.#rawEventBytes += bytes;
    this.#writeChain = this.#writeChain.then(() => appendFile(this.#paths.rawEvents, payload, "utf8"));
  }
}

async function safeLifecycle(sink, eventType, payload) {
  try {
    await sink.lifecycle(eventType, payload);
  } catch (error) {
    console.error(`[rd-pi-bridge] failed to emit ${eventType}: ${safeError(error)}`);
  }
}

async function safeWriteJson(path, value) {
  try {
    await mkdir(dirname(path), { recursive: true });
    const temporary = `${path}.tmp-${process.pid}`;
    await writeFile(temporary, `${JSON.stringify(value)}\n`, { encoding: "utf8", mode: 0o600 });
    await rename(temporary, path);
  } catch (error) {
    console.error(`[rd-pi-bridge] failed to write runtime metadata: ${safeError(error)}`);
  }
}

function boundedJson(value) {
  let serialized;
  try {
    serialized = JSON.stringify(value);
  } catch (error) {
    serialized = JSON.stringify({ protocol: "rd-agent-event/v1", eventType: "PROTOCOL_ERROR", payload: { error: safeError(error) } });
  }
  if (Buffer.byteLength(serialized, "utf8") <= MAX_LINE_BYTES) return serialized;
  return JSON.stringify({
    protocol: "rd-agent-event/v1",
    eventType: "PROTOCOL_ERROR",
    payload: { error: "event exceeded the maximum JSONL line size", truncated: true },
  });
}

async function writeStdout(line) {
  if (process.stdout.write(line)) return;
  await new Promise((resolvePromise) => process.stdout.once("drain", resolvePromise));
}

function safeError(error) {
  return boundedText(error instanceof Error ? error.message : String(error), 4096);
}

function positiveNumber(value, fallback) {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : fallback;
}

function maxRawEventBytes() {
  return positiveSafeInteger(process.env.RD_PI_MAX_RAW_EVENT_BYTES, DEFAULT_MAX_RAW_EVENT_BYTES);
}

function bashCommandTimeoutMillis() {
  return positiveSafeInteger(
    process.env.RD_PI_BASH_COMMAND_TIMEOUT_MILLIS,
    DEFAULT_BASH_COMMAND_TIMEOUT_MILLIS,
  );
}

function positiveSafeInteger(value, fallback) {
  if (typeof value === "number") {
    return Number.isSafeInteger(value) && value > 0 ? value : fallback;
  }
  if (typeof value !== "string" || !/^\d+$/.test(value)) return fallback;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  run().then((exitCode) => {
    process.exitCode = exitCode;
  }).catch((error) => {
    console.error(`[rd-pi-bridge] fatal error: ${safeError(error)}`);
    process.exitCode = 1;
  });
}
