import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("project operations expose a real QA browser profile editor", async () => {
  const [page, service] = await Promise.all([
    readFile(new URL("../src/pages/admin/project/ProjectListPage.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/services/projectService.ts", import.meta.url), "utf8")
  ]);

  assert.match(service, /getProjectQaProfile/);
  assert.match(service, /updateProjectQaProfile/);
  assert.match(service, /buildCommands/);
  assert.match(service, /staticCommands/);
  assert.match(page, /浏览器 QA/);
  assert.match(page, /构建命令/);
  assert.match(page, /静态检查命令/);
  assert.match(page, /跳过构建/);
  assert.match(page, /跳过静态检查/);
  assert.match(page, /regressionCommands/);
  assert.match(page, /allowedHosts/);
  assert.doesNotMatch(page, /placeholder=\{\s*"npm run dev/);
});
