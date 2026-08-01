import assert from "node:assert/strict";
import test from "node:test";

import { taskListFiltersFromSearchParams } from "../src/pages/admin/rdtask/taskListFilters.ts";

test("restores dashboard drill-down filters from the task-list URL", () => {
  const filters = taskListFiltersFromSearchParams(new URLSearchParams(
    "projectId=project-a&taskType=REQUIREMENT&status=WAITING_APPROVAL&keyword=payment"
  ));

  assert.deepEqual(filters, {
    projectId: "project-a",
    taskType: "REQUIREMENT",
    status: "WAITING_APPROVAL",
    keyword: "payment"
  });
});

test("drops blank URL task-list filters instead of making an empty API constraint", () => {
  assert.deepEqual(taskListFiltersFromSearchParams(new URLSearchParams("projectId=%20%20&status=")), {
    projectId: undefined,
    taskType: undefined,
    status: undefined,
    keyword: undefined
  });
});

test("treats the all-project selector sentinel as no task-list API constraint", () => {
  assert.deepEqual(taskListFiltersFromSearchParams(new URLSearchParams("projectId=all")), {
    projectId: undefined,
    taskType: undefined,
    status: undefined,
    keyword: undefined
  });
});
