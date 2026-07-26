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
import { validateResult, writeResultAtomically } from "./result-tool.mjs";

const RESULT_TOOL_NAME = "rd_submit_result";
const DEFAULT_OUTPUT_PATH = "/work/output";
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
  const sink = new EventSink(paths, context);
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

  try {
    await sink.lifecycle("RUNTIME_READY", {
      runtime: "PI",
      provider: request.provider,
      model: request.model,
      snapshotId: request.snapshotId,
    });

    verifiedManifest = await loadResourceManifest(request.resourceManifestPath);
    const settingsManager = safeSettingsManager();
    const observability = createObservabilityExtension(sink, context);
    const resourceLoader = createApprovedResourceLoader({
      cwd: request.repoPath,
      agentDir: "/work/pi-agent",
      verifiedManifest,
      settingsManager,
      extensionFactories: [observability],
    });
    // H1: resource discovery happens once, before session creation. There is
    // intentionally no reload/steer/follow-up path in this one-shot bridge.
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

    const modelRuntime = await configureModelRuntime(request, paths.private);
    const resolvedModel = resolveCliModel({
      cliProvider: request.provider,
      cliModel: request.model,
      modelRuntime,
    });
    if (!resolvedModel.model) {
      throw new Error(resolvedModel.error ?? `model was not found: ${request.provider}/${request.model}`);
    }
    const toolNames = resolveToolNames(request.toolPolicy);
    const resultTool = createResultTool({
      resultPath: paths.result,
      sink,
      context,
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
      customTools: [resultTool],
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
    if (!settled) throw new Error("Pi session became idle without agent_settled");
    if (!resultAccepted) throw new Error("Pi session settled without rd_submit_result");
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
    protocolSucceeded = true;
  } catch (error) {
    failure = error;
    await safeLifecycle(sink, "PROTOCOL_ERROR", {
      category: "PI_BRIDGE_PROTOCOL",
      error: safeError(error),
    });
    if (!resultAccepted) {
      try {
        await writeResultAtomically(paths.result, {
          ...FAILURE_RESULT,
          errorMessage: boundedText(safeError(error), 4096),
        });
        await safeLifecycle(sink, "RESULT_REJECTED", {
          reason: "structured result was not accepted",
        });
      } catch (resultError) {
        console.error(`[rd-pi-bridge] failed to write failure result: ${safeError(resultError)}`);
      }
    }
  } finally {
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
      failure: failure ? boundedText(safeError(failure), 4096) : "",
    });
    try {
      await sink.flush();
    } catch (error) {
      console.error(`[rd-pi-bridge] output flush failed: ${safeError(error)}`);
      protocolSucceeded = false;
    }
  }
  return protocolSucceeded ? 0 : 1;
}

export function executionPrompt(request) {
  return `${request.prompt}\n\n` + [
    "This is a one-shot RD-Bot execution.",
    `You must finish by calling ${RESULT_TOOL_NAME} exactly once with a JSON object containing at least status and summary.`,
    "Do not write result.json yourself; the bridge accepts results only through the tool.",
    "Before submitting a SUCCESS result for coding work, write the unified git diff to /work/output/patch.diff and a concise test log to /work/output/test.log.",
    "Use SUCCESS only when the requested work and acceptance checks are complete. Use FAILED, NEED_INFO, or UNSAFE when they are not.",
  ].join("\n");
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

/** Deterministic fallback so a SUCCESS result always ships patch.diff and test.log. */
async function ensureDeliveryArtifacts(request, paths, result, sink) {
  if (result.status !== "SUCCESS") return;
  const patchPath = join(paths.output, "patch.diff");
  if (!(await fileExists(patchPath))) {
    try {
      const { stdout } = await execFileAsync("git", ["-C", request.repoPath, "diff"], {
        maxBuffer: 32 * 1024 * 1024,
      });
      await writeFile(patchPath, stdout, "utf8");
      await sink.lifecycle("ARTIFACT_WRITTEN", { artifact: "patch.diff", path: patchPath, source: "bridge-git-diff" });
    } catch (error) {
      console.error(`[rd-pi-bridge] failed to materialize patch.diff: ${safeError(error)}`);
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

function outputPaths(outputPath) {
  const output = resolve(outputPath || DEFAULT_OUTPUT_PATH);
  const privatePath = join(output, "private");
  return {
    output,
    private: privatePath,
    session: join(privatePath, "session"),
    rawEvents: join(privatePath, "pi-raw-events.jsonl"),
    runtimeMeta: join(output, "runtime-meta.json"),
    events: join(output, "agent-events.jsonl"),
    result: join(output, "result.json"),
  };
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

function resolveToolNames(policy) {
  const value = policy && typeof policy === "object" ? policy : {};
  const hostAllow = stringSet(value.hostAllow, ["read", "bash", "edit", "write", RESULT_TOOL_NAME]);
  const requested = stringSet(value.allow, [...hostAllow]);
  const denied = stringSet(value.deny, []);
  const active = [...requested].filter((name) => hostAllow.has(name) && !denied.has(name));
  if (!active.includes(RESULT_TOOL_NAME)) {
    throw new Error(`${RESULT_TOOL_NAME} is not permitted by the frozen tool policy`);
  }
  return active;
}

function stringSet(value, fallback) {
  const values = Array.isArray(value) ? value : fallback;
  return new Set(values.filter((item) => typeof item === "string" && item.trim() !== ""));
}

function createResultTool({ resultPath, sink, context, onAccepted }) {
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
        await writeResultAtomically(resultPath, result);
        onAccepted();
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

function createObservabilityExtension(sink, context) {
  return {
    name: "rd-observability",
    hidden: true,
    factory(pi) {
      pi.on("before_provider_request", (event) => sink.lifecycle("PROVIDER_REQUESTED", {
        provider: event.model?.provider,
        model: event.model?.id,
      }));
      pi.on("after_provider_response", (event) => sink.lifecycle("PROVIDER_RESPONDED", {
        status: event.status,
      }));
    },
  };
}

class EventSink {
  #paths;
  #context;
  #normalizer = new EventNormalizer();
  #writeChain = Promise.resolve();

  constructor(paths, context) {
    this.#paths = paths;
    this.#context = context;
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

  #enqueue(event) {
    const line = boundedJson(event);
    this.#writeChain = this.#writeChain.then(async () => {
      await appendFile(this.#paths.events, `${line}\n`, "utf8");
      await writeStdout(`${line}\n`);
    });
    return this.#writeChain;
  }

  #enqueueRaw(line) {
    this.#writeChain = this.#writeChain.then(() => appendFile(this.#paths.rawEvents, `${line}\n`, "utf8"));
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

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  run().then((exitCode) => {
    process.exitCode = exitCode;
  }).catch((error) => {
    console.error(`[rd-pi-bridge] fatal error: ${safeError(error)}`);
    process.exitCode = 1;
  });
}
