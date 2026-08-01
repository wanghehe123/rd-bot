import test from "node:test";
import assert from "node:assert/strict";

import {
  ToolRetryGuard,
  argsHash,
  canonicalJson,
  classifyError,
  errorFingerprint,
  redactValue,
} from "../src/tool-fingerprint.mjs";

test("canonicalJson sorts keys and redacts sensitive values", () => {
  const hashOne = argsHash("bash", { token: "secret", path: "/work/repo/a.mjs" }, { stageRunId: "s1" });
  const hashTwo = argsHash("bash", { path: "/work/repo/a.mjs", token: "other" }, { stageRunId: "s1" });
  assert.equal(hashOne, hashTwo);
  const redacted = redactValue({ apiKey: "abc", command: "node app.mjs" });
  assert.equal(redacted.apiKey, "[redacted]");
  assert.equal(canonicalJson({ b: 1, a: 2 }), '{"a":2,"b":1}');
});

test("classifyError maps deterministic and transient failures", () => {
  assert.equal(classifyError(new Error("permission denied")), "PERMISSION");
  assert.equal(classifyError(new Error("rate limit exceeded")), "RATE_LIMIT");
  assert.equal(classifyError(new Error("stale write conflict")), "STALE_WRITE");
});

test("retry guard blocks deterministic same-args failures", () => {
  const guard = new ToolRetryGuard({ stageRunId: "stage-1", maxTransientRetries: 2 });
  const args = { command: "npm test" };
  const first = guard.recordResult({
    toolName: "bash",
    args,
    error: new Error("permission denied"),
    isError: true,
  });
  assert.ok(first.fingerprint);
  const blocked = guard.checkBeforeCall({ toolName: "bash", args });
  assert.equal(blocked.blocked, true);
  assert.match(blocked.reason, /TOOL_BLOCKED/);
});

test("errorFingerprint is stable for equivalent errors", () => {
  const hash = argsHash("read", { path: "src/a.mjs" }, { stageRunId: "stage-1" });
  const one = errorFingerprint({
    stageRunId: "stage-1",
    toolName: "read",
    argsHash: hash,
    errorCategory: "PERMISSION",
    errorMessage: "permission denied",
  });
  const two = errorFingerprint({
    stageRunId: "stage-1",
    toolName: "read",
    argsHash: hash,
    errorCategory: "PERMISSION",
    errorMessage: "permission   denied",
  });
  assert.equal(one, two);
});

test("transient failures use bounded backoff before blocking", () => {
  const guard = new ToolRetryGuard({ stageRunId: "stage-1", maxTransientRetries: 1 });
  const args = { command: "curl https://example.test" };
  guard.recordResult({
    toolName: "bash",
    args,
    error: new Error("ECONNRESET"),
    isError: true,
  });
  const firstCheck = guard.checkBeforeCall({ toolName: "bash", args });
  assert.equal(firstCheck.blocked, true);
  assert.match(firstCheck.reason, /backoff/i);
  guard.recordResult({
    toolName: "bash",
    args,
    error: new Error("ECONNRESET"),
    isError: true,
  });
  const secondCheck = guard.checkBeforeCall({ toolName: "bash", args });
  assert.equal(secondCheck.blocked, true);
});
