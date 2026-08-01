import { createHash } from "node:crypto";

export const ERROR_CATEGORIES = Object.freeze([
  "DETERMINISTIC_ARGUMENT",
  "PATH_NOT_FOUND",
  "PERMISSION",
  "PROTOCOL_VALIDATION",
  "TRANSIENT_NETWORK",
  "RATE_LIMIT",
  "PROVIDER_UNAVAILABLE",
  "TIMEOUT",
  "STALE_WRITE",
  "LEASE_CONFLICT",
  "DESTRUCTIVE_REQUIRES_APPROVAL",
  "UNKNOWN",
]);

const SENSITIVE_KEY = /(password|secret|token|api[_-]?key|authorization|credential)/i;
const TRANSIENT_PATTERNS = [
  /ECONNRESET/i,
  /ETIMEDOUT/i,
  /ENOTFOUND/i,
  /rate.?limit/i,
  /429/,
  /503/,
  /provider unavailable/i,
  /timeout/i,
];
const DETERMINISTIC_PATTERNS = [
  /invalid argument/i,
  /validation/i,
  /not found/i,
  /permission denied/i,
  /EACCES/i,
  /ENOENT/i,
  /protocol/i,
];
const STALE_PATTERNS = [/stale/i, /lease/i, /conflict/i, /cas/i];
const DESTRUCTIVE_PATTERNS = [/destructive/i, /requires approval/i, /approval required/i];

export function canonicalJson(value) {
  return JSON.stringify(normalizeValue(value));
}

export function redactValue(value, depth = 0) {
  if (depth > 12) return "[truncated]";
  if (value == null || typeof value !== "object") return value;
  if (Array.isArray(value)) return value.map((item) => redactValue(item, depth + 1));
  const out = {};
  for (const key of Object.keys(value).sort()) {
    const raw = value[key];
    if (SENSITIVE_KEY.test(key)) {
      out[key] = "[redacted]";
      continue;
    }
    if (typeof raw === "string" && raw.includes("/work/output/private")) {
      out[key] = "[redacted-path]";
      continue;
    }
    out[key] = redactValue(raw, depth + 1);
  }
  return out;
}

export function argsHash(toolName, args, context = {}) {
  const payload = canonicalJson({
    stageRunId: context.stageRunId ?? "",
    toolName,
    args: redactValue(normalizeArgs(args)),
  });
  return sha256(payload);
}

export function errorFingerprint({ stageRunId, toolName, argsHash: hash, errorCategory, errorMessage }) {
  const normalizedMessage = normalizeErrorMessage(errorMessage);
  const payload = [
    stageRunId ?? "",
    toolName ?? "",
    hash ?? "",
    errorCategory ?? "UNKNOWN",
    sha256(normalizedMessage),
  ].join("|");
  return sha256(payload);
}

export function classifyError(error) {
  const message = normalizeErrorMessage(error);
  if (DESTRUCTIVE_PATTERNS.some((pattern) => pattern.test(message))) {
    return "DESTRUCTIVE_REQUIRES_APPROVAL";
  }
  if (STALE_PATTERNS.some((pattern) => pattern.test(message))) {
    if (/lease/i.test(message)) return "LEASE_CONFLICT";
    return "STALE_WRITE";
  }
  if (/rate.?limit|429/i.test(message)) return "RATE_LIMIT";
  if (/provider unavailable|503/i.test(message)) return "PROVIDER_UNAVAILABLE";
  if (/timeout|ETIMEDOUT/i.test(message)) return "TIMEOUT";
  if (TRANSIENT_PATTERNS.some((pattern) => pattern.test(message))) return "TRANSIENT_NETWORK";
  if (/permission denied|EACCES/i.test(message)) return "PERMISSION";
  if (/ENOENT|not found/i.test(message)) return "PATH_NOT_FOUND";
  if (/invalid argument/i.test(message)) return "DETERMINISTIC_ARGUMENT";
  if (DETERMINISTIC_PATTERNS.some((pattern) => pattern.test(message))) return "PROTOCOL_VALIDATION";
  return "UNKNOWN";
}

export function isDeterministicCategory(category) {
  return [
    "DETERMINISTIC_ARGUMENT",
    "PATH_NOT_FOUND",
    "PERMISSION",
    "PROTOCOL_VALIDATION",
  ].includes(category);
}

export function isTransientCategory(category) {
  return [
    "TRANSIENT_NETWORK",
    "RATE_LIMIT",
    "PROVIDER_UNAVAILABLE",
    "TIMEOUT",
  ].includes(category);
}

export function isStaleCategory(category) {
  return ["STALE_WRITE", "LEASE_CONFLICT"].includes(category);
}

export class ToolRetryGuard {
  #stageRunId;
  #failures = new Map();
  #blocked = new Set();
  #transientAttempts = new Map();
  #maxTransientRetries;

  constructor({ stageRunId, maxTransientRetries = 3 } = {}) {
    this.#stageRunId = stageRunId ?? "";
    this.#maxTransientRetries = positiveInteger(maxTransientRetries, 3);
  }

  recordResult({ toolName, args, error, isError }) {
    if (!isError) {
      const key = failureKey(toolName, argsHash(toolName, args, { stageRunId: this.#stageRunId }));
      this.#failures.delete(key);
      this.#blocked.delete(key);
      this.#transientAttempts.delete(key);
      return null;
    }
    const hash = argsHash(toolName, args, { stageRunId: this.#stageRunId });
    const key = failureKey(toolName, hash);
    const category = classifyError(error);
    const fingerprint = errorFingerprint({
      stageRunId: this.#stageRunId,
      toolName,
      argsHash: hash,
      errorCategory: category,
      errorMessage: error,
    });
    const prior = this.#failures.get(key);
    const count = (prior?.count ?? 0) + 1;
    const record = { toolName, argsHash: hash, category, fingerprint, count, error: normalizeErrorMessage(error), blockedAt: Date.now() };
    this.#failures.set(key, record);
    if (isDeterministicCategory(category) && count >= 1) {
      this.#blocked.add(key);
    }
    if (isTransientCategory(category)) {
      const transientCount = (this.#transientAttempts.get(key) ?? 0) + 1;
      this.#transientAttempts.set(key, transientCount);
      if (transientCount > this.#maxTransientRetries) {
        this.#blocked.add(key);
      }
    }
    if (isStaleCategory(category)) {
      this.#blocked.add(key);
    }
    if (category === "DESTRUCTIVE_REQUIRES_APPROVAL") {
      this.#blocked.add(key);
    }
    return record;
  }

  checkBeforeCall({ toolName, args }) {
    const hash = argsHash(toolName, args, { stageRunId: this.#stageRunId });
    const key = failureKey(toolName, hash);
    if (!this.#blocked.has(key)) {
      const transientCount = this.#transientAttempts.get(key) ?? 0;
      if (transientCount > 0) {
        const backoffMs = Math.min(30_000, 250 * (2 ** (transientCount - 1)));
        const prior = this.#failures.get(key);
        const elapsed = Date.now() - (prior?.blockedAt ?? 0);
        if (elapsed < backoffMs) {
          return {
            blocked: true,
            reason: `TOOL_BLOCKED: transient backoff ${backoffMs}ms for ${toolName}`,
            category: prior?.category ?? "TRANSIENT_NETWORK",
            fingerprint: prior?.fingerprint,
          };
        }
      }
      return { blocked: false };
    }
    const prior = this.#failures.get(key);
    return {
      blocked: true,
      reason: `TOOL_BLOCKED: repeated ${prior?.category ?? "UNKNOWN"} failure for ${toolName}`,
      category: prior?.category ?? "UNKNOWN",
      fingerprint: prior?.fingerprint,
    };
  }
}

function failureKey(toolName, hash) {
  return `${toolName}|${hash}`;
}

function normalizeArgs(args) {
  if (args == null) return {};
  if (typeof args !== "object" || Array.isArray(args)) return { value: args };
  return args;
}

function normalizeValue(value) {
  if (value == null || typeof value !== "object") return value;
  if (Array.isArray(value)) return value.map((item) => normalizeValue(item));
  const out = {};
  for (const key of Object.keys(value).sort()) {
    out[key] = normalizeValue(value[key]);
  }
  return out;
}

function normalizeErrorMessage(error) {
  const message = error instanceof Error ? error.message : String(error ?? "");
  return message.replace(/\s+/g, " ").trim().slice(0, 4096);
}

function sha256(value) {
  return createHash("sha256").update(String(value), "utf8").digest("hex");
}

function positiveInteger(value, fallback) {
  return Number.isInteger(value) && value > 0 ? value : fallback;
}
