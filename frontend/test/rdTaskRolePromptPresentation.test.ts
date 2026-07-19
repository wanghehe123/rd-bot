import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const roleWorkbench = readFileSync(
  new URL("../src/components/admin/rdtask/TaskRoleWorkbench.tsx", import.meta.url),
  "utf8"
);
const detailPage = readFileSync(new URL("../src/pages/admin/rdtask/RdTaskDetailPage.tsx", import.meta.url), "utf8");
const taskService = readFileSync(new URL("../src/services/rdTaskService.ts", import.meta.url), "utf8");

test("loads stage-bound role prompts and renders their Markdown and RAG evidence", () => {
  assert.match(taskService, /getRdTaskRolePrompts/);
  assert.match(taskService, /\/admin\/rd-tasks\/\$\{taskId\}\/role-prompts/);
  assert.match(detailPage, /TaskRoleWorkbench/);
  assert.match(roleWorkbench, /MarkdownRenderer/);
  assert.match(roleWorkbench, /实际角色 Prompt/);
  assert.match(roleWorkbench, /上下文证据/);
  assert.match(roleWorkbench, /RetrievalRun/);
  assert.match(detailPage, /任务执行基线 Prompt/);
});
