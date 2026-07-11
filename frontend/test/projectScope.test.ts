import assert from "node:assert/strict";
import test from "node:test";

import { resolveProjectScope } from "../src/hooks/useProjectScope.ts";

test("uses a valid project from the URL before remembered scope", () => {
  assert.equal(resolveProjectScope("p2", "p1", ["p1", "p2"], true), "p2");
});

test("falls back to the remembered enabled project when the URL is stale", () => {
  assert.equal(resolveProjectScope("missing", "p2", ["p1", "p2"], true), "p2");
});

test("uses the only enabled project when no scope is selected", () => {
  assert.equal(resolveProjectScope(null, null, ["p1"], true), "p1");
});

test("allows the all-project scope only when the caller opts in", () => {
  assert.equal(resolveProjectScope("all", null, ["p1"], true), "all");
  assert.equal(resolveProjectScope("all", null, ["p1"], false), "p1");
});

test("waits for enabled projects before choosing an initial all-project scope", () => {
  assert.equal(resolveProjectScope(null, null, [], true), "");
});
