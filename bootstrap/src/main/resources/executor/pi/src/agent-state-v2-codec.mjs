import { createHash } from "node:crypto";

export const AGENT_STATE_V2_PROTOCOL = "rd-agent-state/v2";
export const JSON_MAX_SAFE_INTEGER = 9_007_199_254_740_991;

const BUDGET_AVAILABILITY = new Set(["UNKNOWN", "AVAILABLE", "UNAVAILABLE"]);

/** RFC 8785-compatible canonical JSON for the integer-only state v2 schema. */
export function canonicalizeStateV2(value) {
  return canonical(value, "$" );
}

export function hashStateV2(value) {
  return `sha256:${createHash("sha256").update(canonicalizeStateV2(value), "utf8").digest("hex")}`;
}

export function decodeAndVerifyStateV2(json, expectedHash) {
  if (typeof json !== "string" || json.trim().length === 0) {
    throw new Error("state JSON must not be blank");
  }
  let value;
  try {
    value = JSON.parse(json);
  } catch (error) {
    throw new Error(`invalid state JSON: ${String(error.message ?? error)}`);
  }
  if (!isPlainObject(value)) {
    throw new Error("state JSON must be an object");
  }
  validateStateContract(value);
  const canonicalJson = canonicalizeStateV2(value);
  const actualHash = `sha256:${createHash("sha256").update(canonicalJson, "utf8").digest("hex")}`;
  if (actualHash !== String(expectedHash ?? "").toLowerCase()) {
    throw new Error("state hash mismatch");
  }
  if (canonicalJson !== json) {
    throw new Error("state JSON must use canonical bytes");
  }
  return value;
}

function validateStateContract(value) {
  if (value.protocol !== AGENT_STATE_V2_PROTOCOL) {
    throw new Error(`state protocol must be ${AGENT_STATE_V2_PROTOCOL}`);
  }
  if (value.budget === undefined || value.budget === null) return;
  if (!isPlainObject(value.budget)) {
    throw new Error("state budget must be an object");
  }
  if (!BUDGET_AVAILABILITY.has(value.budget.availability)) {
    throw new Error(`unsupported budget availability: ${String(value.budget.availability ?? "")}`);
  }
}

function canonical(value, path) {
  if (value === null) return "null";
  if (typeof value === "string") {
    validateUnicode(value, path);
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) {
      throw new Error(`${path} number exceeds JSON safe integer range or is not an integer`);
    }
    return String(value);
  }
  if (typeof value === "boolean") return String(value);
  if (Array.isArray(value)) {
    return `[${value.map((item, index) => canonical(item, `${path}[${index}]`)).join(",")}]`;
  }
  if (isPlainObject(value)) {
    return `{${Object.keys(value).sort().map((key) => {
      validateUnicode(key, `${path}.<key>`);
      return `${JSON.stringify(key)}:${canonical(value[key], `${path}.${key}`)}`;
    }).join(",")}}`;
  }
  throw new Error(`${path} contains an unsupported JSON value`);
}

function validateUnicode(value, path) {
  for (let index = 0; index < value.length; index += 1) {
    const current = value.charCodeAt(index);
    if (current >= 0xD800 && current <= 0xDBFF) {
      const next = value.charCodeAt(index + 1);
      if (!(next >= 0xDC00 && next <= 0xDFFF)) {
        throw new Error(`${path} contains an illegal surrogate`);
      }
      index += 1;
    } else if (current >= 0xDC00 && current <= 0xDFFF) {
      throw new Error(`${path} contains an illegal surrogate`);
    }
  }
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
