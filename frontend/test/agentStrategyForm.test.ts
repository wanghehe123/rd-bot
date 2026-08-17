import assert from "node:assert/strict";
import test from "node:test";

import {
  agentStrategySaveError,
  defaultStrategyDraft,
  localDefaultImageLabel
} from "../src/pages/admin/project/agentStrategyForm.ts";

const images = {
  defaultPiImage: "rd-bot/pi-agent:local",
  defaultPiQaImage: "rd-bot/pi-agent-qa:local",
  defaultClaudeImage: "rd-bot/claude-code:local"
};

test("default draft uses Pi and local default images for all four roles", () => {
  const draft = defaultStrategyDraft("deepseek");
  assert.equal(draft.roles.length, 4);
  assert.ok(draft.roles.every((slot) => slot.runtimeType === "PI"));
  assert.ok(draft.roles.every((slot) => slot.imageMode === "LOCAL_DEFAULT"));
  assert.ok(draft.roles.every((slot) => slot.providerProfileId === "deepseek"));
});

test("local default image labels follow Pi QA and Claude defaults", () => {
  assert.equal(localDefaultImageLabel("PI", "CODING_AGENT", images), "rd-bot/pi-agent:local");
  assert.equal(localDefaultImageLabel("PI", "QA_AGENT", images), "rd-bot/pi-agent-qa:local");
  assert.equal(localDefaultImageLabel("CLAUDE_CODE", "CODING_AGENT", images), "rd-bot/claude-code:local");
  assert.equal(localDefaultImageLabel("MODEL_ONLY", "CODING_AGENT", images), "");
});

test("save is allowed without a Dockerfile when every role uses the local default", () => {
  const draft = defaultStrategyDraft("deepseek");
  draft.mutationToken = "local-agent-runtime";
  assert.equal(agentStrategySaveError(draft), null);
});

test("save names the missing provider on the role card instead of a generic toast", () => {
  const draft = defaultStrategyDraft("");
  draft.mutationToken = "local-agent-runtime";
  const error = agentStrategySaveError(draft);
  assert.ok(error);
  assert.equal(error.roles.CODING_AGENT, "请选择 Provider");
  assert.equal(error.form, undefined);
});

test("custom image without a file is rejected on that role", () => {
  const draft = defaultStrategyDraft("deepseek");
  draft.mutationToken = "token";
  draft.roles[2].imageMode = "CUSTOM";
  const error = agentStrategySaveError(draft);
  assert.ok(error);
  assert.match(String(error.roles.CODING_AGENT), /Dockerfile/);
});

test("strategy id longer than 64 characters is rejected on the form", () => {
  const draft = defaultStrategyDraft("deepseek");
  draft.mutationToken = "token";
  draft.strategyId = "a".repeat(65);
  const error = agentStrategySaveError(draft);
  assert.ok(error);
  assert.match(String(error.form), /64/);
});

test("project list navigates to the strategy page and no longer opens runtime image dialog", async () => {
  const { readFile } = await import("node:fs/promises");
  const page = await readFile(new URL("../src/pages/admin/project/ProjectListPage.tsx", import.meta.url), "utf8");
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(page, /aria-label="运行镜像"/);
  assert.doesNotMatch(page, /function ProjectRuntimeProfileDialog/);
  assert.doesNotMatch(page, /function AgentExecutionProfileDialog/);
  assert.match(page, /\/admin\/projects\/\$\{project\.projectId\}\/agent-strategy/);
  assert.match(app, /projects\/:projectId\/agent-strategy/);
});

test("strategy page explains the agent-runtime switch and persist-only Pi images", async () => {
  const { readFile } = await import("node:fs/promises");
  const page = await readFile(new URL("../src/pages/admin/project/AgentStrategyPage.tsx", import.meta.url), "utf8");
  assert.match(page, /rd\.executor\.agent-runtime\.enabled/);
  assert.match(page, /Pi 自定义镜像本轮只落库/);
  assert.match(page, /X-RD-Agent-Runtime-Token/);
  assert.doesNotMatch(page, /X-RD-Runtime-Profile-Token/);
});
