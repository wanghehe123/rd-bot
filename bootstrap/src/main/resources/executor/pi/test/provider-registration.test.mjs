import assert from "node:assert/strict";
import http from "node:http";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

import {
  buildCustomProviderRegistration,
  openaiCompletionsCompat,
} from "../src/rd-pi-bridge.mjs";

const here = fileURLToPath(new URL(".", import.meta.url));
const piRoot = join(here, "..");

async function loadPiComposer() {
  return import(pathToFileURL(
    join(piRoot, "node_modules/@earendil-works/pi-coding-agent/dist/core/provider-composer.js"),
  ).href);
}

async function loadOpenAiCompletions() {
  return import(pathToFileURL(
    join(
      piRoot,
      "node_modules/@earendil-works/pi-coding-agent/node_modules/@earendil-works/pi-ai/dist/api/openai-completions.js",
    ),
  ).href);
}

/**
 * Compose a custom provider the same way ModelRuntime.registerProvider does,
 * then stream one completion against a local stub and return the request body.
 * This is behavioral proof of the emitted role — not a file-presence check.
 */
async function captureCompletionsRequestBody(registration) {
  let captured = null;
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
    });
    req.on("end", () => {
      captured = JSON.parse(body);
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({
        id: "chatcmpl-test",
        object: "chat.completion",
        choices: [{
          index: 0,
          message: { role: "assistant", content: "ok" },
          finish_reason: "stop",
        }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    const liveRegistration = {
      ...registration,
      baseUrl: `http://127.0.0.1:${port}`,
    };
    const { composeModelProvider } = await loadPiComposer();
    const { stream } = await loadOpenAiCompletions();
    const provider = composeModelProvider(
      "opencode-go",
      undefined,
      { getProvider() { return undefined; } },
      liveRegistration,
    );
    const model = provider.getModels()[0];
    const events = stream(
      model,
      {
        systemPrompt: "system instructions",
        messages: [{ role: "user", content: "hello" }],
      },
      { apiKey: "test-key-not-a-secret" },
    );
    for await (const _event of events) {
      // drain stream so the HTTP request completes
    }
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
  assert.ok(captured, "expected openai-completions request body to be captured");
  return captured;
}

test("openai-completions custom providers disable developer role", () => {
  assert.deepEqual(openaiCompletionsCompat("openai-completions"), {
    supportsDeveloperRole: false,
    supportsReasoningEffort: false,
  });
  assert.equal(openaiCompletionsCompat("anthropic-messages"), null);
});

test("buildCustomProviderRegistration attaches openai-completions compat", () => {
  const registration = buildCustomProviderRegistration({
    provider: "opencode-go",
    baseUrl: "http://127.0.0.1:9",
    api: "openai-completions",
    authHeader: true,
    model: "deepseek-v4-flash",
  });
  assert.equal(registration.name, "opencode-go");
  assert.equal(registration.api, "openai-completions");
  assert.deepEqual(registration.compat, {
    supportsDeveloperRole: false,
    supportsReasoningEffort: false,
  });
  // Provider-level compat alone is ignored by applyExtension; the model entry
  // is what openai-completions.js actually reads after composition.
  assert.deepEqual(registration.models[0].compat, {
    supportsDeveloperRole: false,
    supportsReasoningEffort: false,
  });
  assert.equal(registration.models[0].id, "deepseek-v4-flash");
});

test("buildCustomProviderRegistration omits compat for anthropic-messages", () => {
  const registration = buildCustomProviderRegistration({
    provider: "deepseek-anthropic",
    baseUrl: "http://127.0.0.1:9",
    api: "anthropic-messages",
    authHeader: true,
    model: "deepseek-v4-flash",
  });
  assert.equal(registration.compat, undefined);
  assert.equal(registration.models[0].compat, undefined);
});

test("composed openai-completions request emits system role not developer", async () => {
  const registration = buildCustomProviderRegistration({
    provider: "opencode-go",
    baseUrl: "http://127.0.0.1:9",
    api: "openai-completions",
    authHeader: true,
    model: "deepseek-v4-flash",
    reasoning: true,
  });
  const body = await captureCompletionsRequestBody(registration);
  assert.equal(
    body.messages[0].role,
    "system",
    `expected messages[0].role=system after composition; got ${JSON.stringify(body.messages)}`,
  );
  assert.equal(body.messages[0].content, "system instructions");
});
