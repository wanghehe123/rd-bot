import assert from "node:assert/strict";
import test from "node:test";

import {
  runtimeEventDetail,
  runtimeEventLabel,
  snapshotFields
} from "../src/pages/admin/project/agentRuntimePresentation.ts";

test("formats normalized runtime events without exposing the raw payload", () => {
  assert.equal(runtimeEventLabel({ eventType: "TOOL_STARTED", payload: { toolName: "bash" } }), "工具开始");
  assert.equal(
    runtimeEventDetail({ eventType: "TOOL_STARTED", payload: { toolName: "bash" } }),
    "bash"
  );
  assert.equal(
    runtimeEventDetail({ eventType: "PROTOCOL_ERROR", payload: { error: "invalid event" } }),
    "invalid event"
  );
});

test("extracts safe operational fields from an immutable snapshot", () => {
  const fields = snapshotFields({
    runtimeType: "PI",
    providerProfileId: "provider-a",
    providerModelId: "model-a",
    providerProtocol: "OPENAI_COMPATIBLE",
    toolPolicyId: "coding",
    toolPolicyVersion: 3,
    extensionSetId: "coding-tools",
    extensionSetVersion: 2,
    resolvedFrom: "TASK_OVERRIDE",
    credentialEnvironmentVariable: "PI_API_KEY",
    secret: "must not be shown"
  });
  assert.deepEqual(fields, {
    runtime: "PI",
    provider: "provider-a",
    model: "model-a",
    protocol: "OPENAI_COMPATIBLE",
    toolPolicy: "coding@3",
    extensionSet: "coding-tools@2",
    resolvedFrom: "TASK_OVERRIDE",
    credentialVariable: "PI_API_KEY"
  });
  assert.doesNotMatch(JSON.stringify(fields), /must not be shown/);
});
