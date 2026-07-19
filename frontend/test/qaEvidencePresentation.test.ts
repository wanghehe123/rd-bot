import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("task detail loads task-scoped QA evidence and renders screenshots through the protected content API", async () => {
  const [workbench, model, service] = await Promise.all([
    readFile(new URL("../src/components/admin/rdtask/TaskRoleWorkbench.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/pages/admin/rdtask/roleWorkbenchModel.ts", import.meta.url), "utf8"),
    readFile(new URL("../src/services/rdTaskService.ts", import.meta.url), "utf8")
  ]);

  assert.match(service, /getRdTaskQaEvidence/);
  assert.match(service, /\/admin\/rd-tasks\/\$\{taskId\}\/qa-evidence/);
  assert.match(workbench, /QA 验证证据/);
  assert.match(workbench, /验收执行结果/);
  assert.match(model, /projectRoleResult/);
  assert.match(model, /failureCategory/);
  assert.match(model, /browserValidation/);
  assert.match(workbench, /item\.previewable \? "查看证据"/);
  assert.match(workbench, /(item|evidence)\.contentUrl/);
  assert.match(workbench, /CURRENT \/ REGRESSION/);
});

test("audit run summaries use responsive records instead of forced wide tables", async () => {
  const page = await readFile(new URL("../src/pages/admin/rdtask/RdTaskDetailPage.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(page, /min-w-\[(?:820|900)px\]/);
  assert.match(page, /AI 交付复核/);
  assert.match(page, /RAG 检索/);
});
