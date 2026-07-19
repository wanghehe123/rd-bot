import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("running execution token usage exposes only the CNY spend contract", async () => {
  const service = await readFile(new URL("../src/services/rdTaskService.ts", import.meta.url), "utf8");

  assert.match(service, /tokenUsage:\s*\{[\s\S]*estimatedSpendCny:\s*number/);
  assert.doesNotMatch(service, /estimatedCostUsd/);
});
