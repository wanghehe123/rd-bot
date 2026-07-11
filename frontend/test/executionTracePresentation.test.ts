import assert from "node:assert/strict";
import test from "node:test";

import { executionTraceQueryForScope, traceProgressPercent } from "../src/pages/admin/trace/executionTracePresentation.ts";

test("does not send a synthetic all-project filter to execution traces", () => {
  assert.deepEqual(executionTraceQueryForScope("all", { status: "EXECUTING" }), { status: "EXECUTING" });
  assert.deepEqual(executionTraceQueryForScope("project-a", { taskType: "BUG_FIX" }), {
    projectId: "project-a",
    taskType: "BUG_FIX"
  });
});

test("clamps execution trace progress values to a readable percentage", () => {
  assert.equal(traceProgressPercent(3, 12), 25);
  assert.equal(traceProgressPercent(20, 12), 100);
  assert.equal(traceProgressPercent(0, 0), 0);
});
