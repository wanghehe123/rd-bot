import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  findDuplicateEnvVars,
  metadataError,
  MODEL_PROVIDER_PROTOCOLS,
  PROTOCOL_LABELS
} from "../src/pages/admin/provider/modelProviderForm.ts";
import type { ModelProviderMetadataInput } from "../src/services/modelProviderService.ts";

test("model provider service never types a readable apiKey on the profile", () => {
  const service = readFileSync(new URL("../src/services/modelProviderService.ts", import.meta.url), "utf8");
  assert.match(service, /credentialConfigured/);
  assert.doesNotMatch(service, /apiKey\s*\?:/);
  assert.match(service, /X-RD-Agent-Runtime-Token/);
  assert.match(service, /\/admin\/model-provider-profiles/);
});

test("metadataError validates empty providerId, displayName, baseUrl, modelId, and env var format", () => {
  const valid: ModelProviderMetadataInput & { providerId: string } = {
    providerId: "opencode-go",
    displayName: "OpenCode Go",
    protocol: "OPENAI_CHAT_COMPLETIONS",
    baseUrl: "https://opencode.ai/zen/go/v1",
    modelId: "deepseek-v4-flash",
    credentialEnvironmentVariable: "OPENCODE_API_KEY",
    authHeader: false,
    enabled: true,
    version: 1
  };

  assert.equal(metadataError(valid), "");

  assert.equal(metadataError({ ...valid, providerId: "   " }), "请填写 providerId");
  assert.equal(metadataError({ ...valid, displayName: "   " }), "请填写显示名");
  assert.equal(metadataError({ ...valid, baseUrl: "   " }), "请填写 baseUrl");
  assert.equal(metadataError({ ...valid, modelId: "   " }), "请填写模型");
  assert.equal(
    metadataError({ ...valid, credentialEnvironmentVariable: "sk-1234567890abcdef" }),
    "环境变量名必须是大写字母、数字和下划线，不能填写密钥本身"
  );
  assert.equal(
    metadataError({ ...valid, credentialEnvironmentVariable: "lower_case_key" }),
    "环境变量名必须是大写字母、数字和下划线，不能填写密钥本身"
  );
  assert.equal(
    metadataError({ ...valid, credentialEnvironmentVariable: "123_VAR" }),
    "环境变量名必须是大写字母、数字和下划线，不能填写密钥本身"
  );
});

test("findDuplicateEnvVars identifies environment variables shared by multiple profiles", () => {
  const profiles = [
    {
      providerId: "opencode-go",
      displayName: "OpenCode Go",
      protocol: "OPENAI_CHAT_COMPLETIONS" as const,
      baseUrl: "https://opencode.ai/zen/go/v1",
      modelId: "deepseek-v4-flash",
      credentialEnvironmentVariable: "OPENCODE_API_KEY",
      enabled: true,
      version: 1,
      credentialConfigured: true,
      credentialUpdatedAt: 1723900000000
    },
    {
      providerId: "opencode-coder",
      displayName: "OpenCode Coder",
      protocol: "OPENAI_CHAT_COMPLETIONS" as const,
      baseUrl: "https://opencode.ai/zen/coder/v1",
      modelId: "deepseek-coder",
      credentialEnvironmentVariable: "OPENCODE_API_KEY",
      enabled: true,
      version: 1,
      credentialConfigured: true,
      credentialUpdatedAt: 1723900000000
    },
    {
      providerId: "claude-sonnet",
      displayName: "Claude Sonnet",
      protocol: "ANTHROPIC_MESSAGES" as const,
      baseUrl: "https://api.anthropic.com",
      modelId: "claude-3-7-sonnet",
      credentialEnvironmentVariable: "ANTHROPIC_API_KEY",
      enabled: true,
      version: 1,
      credentialConfigured: false,
      credentialUpdatedAt: null
    }
  ];

  const duplicates = findDuplicateEnvVars(profiles);
  assert.equal(duplicates.has("OPENCODE_API_KEY"), true);
  assert.equal(duplicates.has("ANTHROPIC_API_KEY"), false);
});

test("all model provider protocols have human-readable labels", () => {
  for (const protocol of MODEL_PROVIDER_PROTOCOLS) {
    assert.equal(typeof PROTOCOL_LABELS[protocol], "string");
    assert.ok(PROTOCOL_LABELS[protocol].length > 0);
  }
});
