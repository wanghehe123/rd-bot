import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const source = readFileSync(new URL("../src/pages/admin/rdtask/RdTaskListPage.tsx", import.meta.url), "utf8");
const listPage = source.slice(
  source.indexOf("export function RdTaskListPage()"),
  source.indexOf("interface RdTaskEditDialogProps")
);

test("loads enabled projects into a controlled all-project task filter", () => {
  assert.match(listPage, /getProjectsPage\(\{ enabled: true, page: 1, pageSize: 200 \}\)/);
  assert.match(listPage, /value=\{projectIdFilter \|\| "all"\}/);
  assert.match(listPage, /onValueChange=\{handleProjectChange\}/);
  assert.match(listPage, /<SelectItem value="all">全部项目<\/SelectItem>/);
});

test("preserves the selected project when task-list actions reload data", () => {
  assert.match(listPage, /loadTasks\(1, statusFilter, keyword, taskTypeFilter, projectIdFilter\)/);
  assert.match(listPage, /loadTasks\(page, statusFilter, keyword, taskTypeFilter, projectIdFilter\)/);
  assert.match(listPage, /loadTasks\(Math\.max\(1, page - 1\), statusFilter, keyword, taskTypeFilter, projectIdFilter\)/);
  assert.match(listPage, /loadTasks\(Math\.min\(pages \|\| 1, page \+ 1\), statusFilter, keyword, taskTypeFilter, projectIdFilter\)/);
});
