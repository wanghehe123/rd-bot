import assert from "node:assert/strict";
import test from "node:test";

import { createTaskRequestGuard, loadTaskDetailShell } from "../src/pages/admin/rdtask/rdTaskDetailLoader.ts";

test("publishes the task shell even when supporting panels fail", async () => {
  const published: Array<{ taskId: string }> = [];
  const result = await loadTaskDetailShell({
    loadTask: async () => ({ taskId: "task-42" }),
    onTask: (task) => published.push(task),
    panels: {
      timeline: async () => { throw new Error("timeline unavailable"); },
      materials: async () => ["material-1"],
      overview: async () => { throw new Error("overview unavailable"); }
    }
  });

  assert.deepEqual(published, [{ taskId: "task-42" }]);
  assert.equal(result.task.taskId, "task-42");
  assert.deepEqual(result.panels.materials, ["material-1"]);
  assert.equal(result.panels.timeline, undefined);
  assert.match(result.errors.timeline || "", /timeline unavailable/);
  assert.match(result.errors.overview || "", /overview unavailable/);
});

test("rejects when the task shell itself cannot be loaded", async () => {
  await assert.rejects(
    loadTaskDetailShell({
      loadTask: async () => { throw new Error("task missing"); },
      panels: { timeline: async () => [] }
    }),
    /task missing/
  );
});

test("invalidates every in-flight panel request when the task route changes", () => {
  const guard = createTaskRequestGuard();
  guard.beginTask("task-a");
  const taskARequest = guard.capture();
  assert.equal(guard.isCurrent(taskARequest), true);

  guard.beginTask("task-b");
  const taskBRequest = guard.capture();
  const lateTaskACallback = guard.capture("task-a");
  assert.equal(guard.isCurrent(taskARequest), false);
  assert.equal(guard.isCurrent(taskBRequest), true);
  assert.equal(guard.isCurrent(lateTaskACallback), false);
});
