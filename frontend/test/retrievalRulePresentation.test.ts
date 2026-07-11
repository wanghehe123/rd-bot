import assert from "node:assert/strict";
import test from "node:test";

import { retrievalRuleSummary, scopeForNewRule } from "../src/pages/admin/retrieval/retrievalRulePresentation.ts";

test("derives project-only rule creation scope from the shared selector", () => {
  assert.equal(scopeForNewRule("project-a"), "PROJECT");
  assert.equal(scopeForNewRule("all"), "GLOBAL");
  assert.equal(scopeForNewRule(""), "GLOBAL");
});

test("summarizes global, project, enabled and disabled rule counts", () => {
  assert.deepEqual(retrievalRuleSummary([
    { scope: "GLOBAL", enabled: true },
    { scope: "PROJECT", enabled: true },
    { scope: "PROJECT", enabled: false }
  ]), {
    total: 3,
    global: 1,
    project: 2,
    enabled: 2,
    disabled: 1
  });
});
