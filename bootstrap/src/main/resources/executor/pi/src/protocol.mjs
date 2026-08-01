export const REQUEST_PROTOCOL = "rd-pi-request/v1";
export const EVENT_PROTOCOL = "rd-agent-event/v1";
export const MAX_LINE_BYTES = 256 * 1024;
export const MAX_TEXT_BYTES = 64 * 1024;

export const EVENT_TYPES = Object.freeze([
  "RUNTIME_READY",
  "AGENT_STARTED",
  "AGENT_SETTLED",
  "RUNTIME_STOPPED",
  "TURN_STARTED",
  "TURN_COMPLETED",
  "ASSISTANT_TEXT_DELTA",
  "ASSISTANT_TEXT_COMPLETED",
  "TOOL_STARTED",
  "TOOL_PROGRESS",
  "TOOL_COMPLETED",
  "TOOL_BLOCKED",
  "PROVIDER_REQUESTED",
  "PROVIDER_RESPONDED",
  "PROVIDER_RETRYING",
  "COMPACTION_STARTED",
  "COMPACTION_COMPLETED",
  "RESOURCES_LOADED",
  "EXTENSION_FAILED",
  "USAGE_UPDATED",
  "RESULT_SUBMITTED",
  "RESULT_REJECTED",
  "ARTIFACT_WRITTEN",
  "STATE_ACTION_RECORDED",
  "TOOL_FINGERPRINT_RECORDED",
  "PROTOCOL_ERROR",
]);

const ROLES = new Set([
  "REQUIREMENT_REVIEWER",
  "SOLUTION_ARCHITECT",
  "CODING_AGENT",
  "QA_AGENT",
]);

export function validateRequest(request) {
  if (!request || typeof request !== "object" || Array.isArray(request)) {
    throw new Error("request must be a JSON object");
  }
  requireString(request.protocol, "protocol");
  if (request.protocol !== REQUEST_PROTOCOL) {
    throw new Error(`unsupported request protocol: ${request.protocol}`);
  }
  for (const field of [
    "snapshotId",
    "stageRunId",
    "taskId",
    "role",
    "prompt",
    "provider",
    "model",
    "repoPath",
    "inputPath",
    "outputPath",
    "resourceManifestPath",
  ]) {
    requireString(request[field], field);
  }
  if (!ROLES.has(request.role)) {
    throw new Error(`unsupported role: ${request.role}`);
  }
  if (request.repoPath !== "/work/repo" || request.inputPath !== "/work/input"
      || request.outputPath !== "/work/output"
      || request.resourceManifestPath !== "/work/input/resource-manifest.json") {
    throw new Error("request paths must use the fixed /work contract");
  }
  if (request.credentialEnvironmentVariable !== undefined
      && !/^[A-Z_][A-Z0-9_]*$/.test(request.credentialEnvironmentVariable)) {
    throw new Error("credentialEnvironmentVariable must be an env name");
  }
  if (request.api !== undefined) requireString(request.api, "api");
  if (request.authHeader !== undefined && typeof request.authHeader !== "boolean") {
    throw new Error("authHeader must be a boolean");
  }
  if (request.applyCandidatePatch !== undefined && typeof request.applyCandidatePatch !== "boolean") {
    throw new Error("applyCandidatePatch must be a boolean");
  }
  if (request.patchArtifactPath !== undefined) {
    requireString(request.patchArtifactPath, "patchArtifactPath");
    if (!request.patchArtifactPath.startsWith("/work/output/")) {
      throw new Error("patchArtifactPath must stay under /work/output/");
    }
  }
  if (request.maxAgentTurns !== undefined) {
    if (!Number.isInteger(request.maxAgentTurns) || request.maxAgentTurns < 1) {
      throw new Error("maxAgentTurns must be a positive integer");
    }
  }
  if (request.maxTotalTokens !== undefined) {
    if (!Number.isInteger(request.maxTotalTokens) || request.maxTotalTokens < 1) {
      throw new Error("maxTotalTokens must be a positive integer");
    }
  }
  if (request.baseUrl !== undefined) {
    requireString(request.baseUrl, "baseUrl");
    let url;
    try {
      url = new URL(request.baseUrl);
    } catch (error) {
      throw new Error(`baseUrl must be a valid URL: ${error.message}`);
    }
    if (!url.hostname || !["http:", "https:"].includes(url.protocol)) {
      throw new Error("baseUrl must use http or https");
    }
  }
  if (Buffer.byteLength(request.prompt, "utf8") > MAX_TEXT_BYTES) {
    throw new Error("prompt exceeds the maximum allowed size");
  }
  if (!isObject(request.toolPolicy)) {
    throw new Error("toolPolicy must be an object");
  }
  if (request.dynamicStateEnabled !== undefined && typeof request.dynamicStateEnabled !== "boolean") {
    throw new Error("dynamicStateEnabled must be a boolean");
  }
  if (request.maxInjectedStateBytes !== undefined) {
    if (!Number.isInteger(request.maxInjectedStateBytes) || request.maxInjectedStateBytes < 1) {
      throw new Error("maxInjectedStateBytes must be a positive integer");
    }
  }
  if (request.attemptNo !== undefined) {
    if (!Number.isInteger(request.attemptNo) || request.attemptNo < 1) {
      throw new Error("attemptNo must be a positive integer");
    }
  }
  return request;
}

export function parseJsonLine(line, maxBytes = MAX_LINE_BYTES) {
  const text = String(line ?? "");
  if (Buffer.byteLength(text, "utf8") > maxBytes) {
    throw new Error("JSON line exceeds the maximum allowed size");
  }
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`invalid JSON line: ${error.message}`);
  }
}

export function normalizePiEvent(rawEvent, context, sourceSequence) {
  if (!rawEvent || typeof rawEvent !== "object") return null;
  const type = rawEvent.type;
  const mapping = {
    agent_start: "AGENT_STARTED",
    agent_settled: "AGENT_SETTLED",
    turn_start: "TURN_STARTED",
    turn_end: "TURN_COMPLETED",
    tool_execution_start: "TOOL_STARTED",
    tool_execution_update: "TOOL_PROGRESS",
    tool_execution_end: "TOOL_COMPLETED",
    compaction_start: "COMPACTION_STARTED",
    compaction_end: "COMPACTION_COMPLETED",
    auto_retry_start: "PROVIDER_RETRYING",
    auto_retry_end: "PROVIDER_RESPONDED",
  };
  let eventType = mapping[type];
  let payload = {};
  if (type === "message_update") {
    const assistantEvent = rawEvent.assistantMessageEvent;
    if (!assistantEvent || assistantEvent.type === "thinking_delta") return null;
    if (assistantEvent.type !== "text_delta") return null;
    eventType = "ASSISTANT_TEXT_DELTA";
    payload = { delta: boundedText(assistantEvent.delta) };
  } else if (type === "message_end") {
    eventType = "ASSISTANT_TEXT_COMPLETED";
    const message = rawEvent.message;
    payload = {
      provider: boundedText(message?.provider, 128),
      model: boundedText(message?.model, 256),
      stopReason: boundedText(message?.stopReason, 32),
      usage: usagePayload(message?.usage),
    };
  } else if (type === "turn_end") {
    payload = {
      toolResultCount: Array.isArray(rawEvent.toolResults) ? rawEvent.toolResults.length : 0,
      stopReason: boundedText(rawEvent.message?.stopReason, 32),
      usage: usagePayload(rawEvent.message?.usage),
    };
  } else if (type === "tool_execution_start") {
    payload = {
      toolName: boundedText(rawEvent.toolName, 256),
      toolCallId: boundedText(rawEvent.toolCallId ?? rawEvent.id, 256),
      displaySummary: toolDisplaySummary(rawEvent),
    };
  } else if (type === "tool_execution_update") {
    payload = {
      toolName: boundedText(rawEvent.toolName, 256),
      toolCallId: boundedText(rawEvent.toolCallId ?? rawEvent.id, 256),
    };
  } else if (type === "tool_execution_end") {
    payload = {
      toolName: boundedText(rawEvent.toolName, 256),
      toolCallId: boundedText(rawEvent.toolCallId ?? rawEvent.id, 256),
      isError: Boolean(rawEvent.isError),
    };
  } else if (type === "agent_end") {
    // agent_end may be followed by an SDK retry; only agent_settled is terminal.
    return rawEvent.willRetry ? null : null;
  } else if (type === "auto_retry_start") {
    payload = { attempt: rawEvent.attempt, maxAttempts: rawEvent.maxAttempts };
  } else if (type === "auto_retry_end") {
    payload = { attempt: rawEvent.attempt, success: Boolean(rawEvent.success) };
  } else if (type === "compaction_start" || type === "compaction_end") {
    payload = { reason: boundedText(rawEvent.reason, 64) };
  }
  if (!eventType || !EVENT_TYPES.includes(eventType)) return null;
  return {
    protocol: EVENT_PROTOCOL,
    eventType,
    sourceSequence,
    stageRunId: context.stageRunId,
    taskId: context.taskId,
    role: context.role,
    runtimeType: context.runtimeType ?? "PI",
    snapshotId: context.snapshotId ?? "",
    provider: context.provider ?? "",
    model: context.model ?? "",
    occurredAt: new Date().toISOString(),
    payload: redact(payload),
    redacted: true,
  };
}

export function boundedText(value, maxChars = MAX_TEXT_BYTES) {
  const text = value == null ? "" : String(value);
  if (text.length <= maxChars) return text;
  return `${text.slice(0, maxChars)}...[truncated]`;
}

function toolDisplaySummary(rawEvent) {
  const command = rawEvent?.args?.command;
  if (typeof command === "string" && command.trim()) {
    return redactDisplayText(command);
  }
  const path = rawEvent?.args?.path;
  if (typeof path === "string" && path.trim()) {
    return redactDisplayText(path);
  }
  return boundedText(rawEvent?.toolName, 256);
}

function redactDisplayText(value) {
  return boundedText(value, 2 * 1024)
    .replace(/\b([A-Z][A-Z0-9_]*(?:TOKEN|KEY|SECRET|PASSWORD|COOKIE|CREDENTIAL)[A-Z0-9_]*)\s*=\s*(?:"[^"]*"|'[^']*'|\S+)/gi, "$1=[REDACTED]")
    .replace(/((?:authorization|api[_-]?key|token|secret|password|cookie|credential)\s*[:=]\s*(?:bearer\s+)?)(?:"[^"]*"|'[^']*'|[^\s'"]+)/gi, "$1[REDACTED]")
    .replace(/([?&](?:api[_-]?key|token|secret|password|cookie|credential)=)[^&#\s]+/gi, "$1[REDACTED]")
    .replace(/(--(?:api[_-]?key|token|secret|password|cookie|credential)(?:=|\s+))(?:"[^"]*"|'[^']*'|\S+)/gi, "$1[REDACTED]");
}

export function redact(value) {
  if (Array.isArray(value)) return value.map(redact);
  if (typeof value === "string") return boundedText(value);
  if (typeof value === "number" || typeof value === "boolean" || value === null) return value;
  if (!isObject(value)) return boundedText(value);
  const output = {};
  for (const [key, raw] of Object.entries(value)) {
    if (/(api.?key|token|secret|password|authorization|cookie|credential)/i.test(key)) {
      output[key] = "[REDACTED]";
    } else {
      output[key] = redact(raw);
    }
  }
  return output;
}

function usagePayload(usage) {
  if (!usage || typeof usage !== "object") return {};
  return {
    input: numberOrZero(usage.input),
    output: numberOrZero(usage.output),
    cacheRead: numberOrZero(usage.cacheRead),
    cacheWrite: numberOrZero(usage.cacheWrite),
    totalTokens: numberOrZero(usage.totalTokens ?? usage.total),
    cost: {
      input: numberOrZero(usage.cost?.input),
      output: numberOrZero(usage.cost?.output),
      cacheRead: numberOrZero(usage.cost?.cacheRead),
      cacheWrite: numberOrZero(usage.cost?.cacheWrite),
      total: numberOrZero(usage.cost?.total),
    },
  };
}

function numberOrZero(value) {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

export function requireString(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${field} must be a non-empty string`);
  }
  return value;
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
