import assert from "node:assert/strict";
import test from "node:test";

import {
  agentProfileSaveError,
  agentRuntimeMutationError,
  firstEnabledProviderId,
  piSupportsRole,
  runtimeImageDialogHint
} from "../src/pages/admin/project/agentExecutionProfileForm.ts";

test("Pi can bind every requirement-delivery role", () => {
  for (const role of ["REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"]) {
    assert.equal(piSupportsRole(role), true, role);
  }
});

test("firstEnabledProviderId keeps a valid selection and otherwise picks the first enabled provider", () => {
  const providers = [
    { providerId: "off", enabled: false },
    { providerId: "deepseek", enabled: true },
    { providerId: "longcat-anthropic", enabled: true }
  ];
  assert.equal(firstEnabledProviderId(providers, ""), "deepseek");
  assert.equal(firstEnabledProviderId(providers, "longcat-anthropic"), "longcat-anthropic");
  assert.equal(firstEnabledProviderId(providers, "missing"), "deepseek");
  assert.equal(firstEnabledProviderId([], ""), "");
});

test("save error names the missing provider instead of a generic filled-form rejection", () => {
  assert.equal(
    agentProfileSaveError({
      mutationToken: "token",
      profileId: "coding",
      name: "PI",
      providerProfileId: "",
      toolPolicyId: "legacy-host-bound"
    }),
    "请选择 Provider Profile"
  );
  assert.equal(
    agentProfileSaveError({
      mutationToken: "token",
      profileId: "coding",
      name: "PI",
      providerProfileId: "deepseek",
      toolPolicyId: "legacy-host-bound"
    }),
    null
  );
});

test("project list no longer contains the old Pi coding-only banner", async () => {
  const { readFile } = await import("node:fs/promises");
  const page = await readFile(new URL("../src/pages/admin/project/ProjectListPage.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(page, /Pi Agent 只允许绑定编码执行角色/);
  assert.doesNotMatch(page, /Pi Agent 当前仅允许编码执行角色/);
});

test("maps mutation 403/503 to an actionable token message", () => {
  assert.equal(
    agentRuntimeMutationError({ response: { status: 403 } }),
    "操作令牌不正确。本地请填写 local-agent-runtime"
  );
  assert.match(String(agentRuntimeMutationError({ response: { status: 503 } })), /未配置/);
  assert.equal(agentRuntimeMutationError({ response: { status: 400 } }), null);
});

test("runtime image dialog explains Pi is not switched by Dockerfile upload", () => {
  assert.match(runtimeImageDialogHint(["PI"]), /Pi Agent/);
  assert.match(runtimeImageDialogHint(["PI"]), /Dockerfile/);
  assert.match(runtimeImageDialogHint(["CLAUDE_CODE"]), /Agent 执行策略/);
});
