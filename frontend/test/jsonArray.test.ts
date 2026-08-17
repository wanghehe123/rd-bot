import assert from "node:assert/strict";
import test from "node:test";

import { asArray } from "../src/services/jsonArray.ts";

test("asArray keeps real arrays and drops HTML or object payloads", () => {
  const providers = [{ providerId: "deepseek", enabled: true }];
  assert.deepEqual(asArray(providers), providers);
  assert.deepEqual(asArray("<!doctype html>"), []);
  assert.deepEqual(asArray({ records: providers }), []);
  assert.deepEqual(asArray(null), []);
  assert.deepEqual(asArray(undefined), []);
});
