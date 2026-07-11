import assert from "node:assert/strict";
import test from "node:test";

import {
  dashboardDataForScope,
  formatAvailabilityRatio,
  taskListHref
} from "../src/pages/dashboard/dashboardPresentation.ts";

test("builds a task-list drill-down with the selected project and an exact status", () => {
  assert.equal(
    taskListHref("p1", { status: "FAILED_NEEDS_HUMAN" }),
    "/admin/rd-tasks?projectId=p1&status=FAILED_NEEDS_HUMAN"
  );
});

test("does not constrain all-project task drill-downs to a synthetic project", () => {
  assert.equal(
    taskListHref("all", { taskType: "BUG_FIX" }),
    "/admin/rd-tasks?taskType=BUG_FIX"
  );
});

test("renders unavailable ratios as an unknown value instead of zero", () => {
  assert.equal(formatAvailabilityRatio({ available: false, value: null }), "--");
  assert.equal(formatAvailabilityRatio({ available: true, value: 0.5 }), "50.0%");
});

test("does not render a previous project response after the project scope changes", () => {
  assert.equal(dashboardDataForScope("project-a", "project-a", { requirementCount: 3 })?.requirementCount, 3);
  assert.equal(dashboardDataForScope("project-a", "project-b", { requirementCount: 3 }), null);
});
