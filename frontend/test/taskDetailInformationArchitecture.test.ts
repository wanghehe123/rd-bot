import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const detailPage = readFileSync(
  new URL("../src/pages/admin/rdtask/RdTaskDetailPage.tsx", import.meta.url),
  "utf8"
);

test("makes the role workbench the primary task-detail surface", () => {
  assert.match(detailPage, /TaskRoleWorkbench/);
  // V2: 顶部只保留一套任务级摘要，旧摘要大卡与空快速操作容器不得回归
  assert.doesNotMatch(detailPage, /TaskSummaryBand/);
  assert.doesNotMatch(detailPage, /快速操作：/);
  assert.ok(detailPage.indexOf("<TaskHeaderNotice") >= 0);
  assert.ok(detailPage.indexOf("<TaskHeaderNotice") < detailPage.indexOf("<TaskRoleWorkbench"));
  assert.doesNotMatch(detailPage, /<RolePromptEvidenceCard/);
  assert.doesNotMatch(detailPage, /<ExecutionOverviewCard/);
  assert.doesNotMatch(
    detailPage,
    /function (?:RolePromptEvidenceCard|ExecutionOverviewCard|MetricBlock|StageRunRow|QaStageOutcome|QaScopeOutcome)\b/
  );
});

test("keeps waiting, recovery and blocker notices and merged quick actions in the compact header", () => {
  // 原摘要大卡的等待/恢复/阻断提示迁到页头后必须仍然存在
  assert.match(detailPage, /等待补充信息/);
  assert.match(detailPage, /恢复中：<\/span>/);
  assert.match(detailPage, /当前阻断：<\/span>/);
  // 快速操作合并进页头操作区：三个条件与 handler 保留，false 时不渲染容器
  assert.match(detailPage, /canSubmitRequirementTask\(task\) \? \(\n            <Button size="sm" onClick=\{handleSubmitTask\}/);
  assert.match(detailPage, /task\?\.status === "AWAITING_BUDGET_APPROVAL" \? \(\n            <Button size="sm" onClick=\{\(\) => setBudgetApprovalOpen\(true\)\}/);
  assert.match(detailPage, /canAnswerRequirement\(task\) \? \(\n            <Button size="sm" onClick=\{\(\) => setAnswerOpen\(true\)\}/);
  // 页头次级行携带项目/流程/Token/审计计数
  assert.match(detailPage, /当前流程:/);
  assert.match(detailPage, /累计 Token:/);
  assert.match(detailPage, /auditedCoverage\(auditedState\.records\)\.label/);
});

test("workbench header does not duplicate the task-level progress summary", () => {
  const workbench = readFileSync(
    new URL("../src/components/admin/rdtask/TaskRoleWorkbench.tsx", import.meta.url),
    "utf8"
  );
  assert.doesNotMatch(workbench, /阶段推进/);
  assert.match(workbench, /overviewError/);
});

test("moves task input and audit history behind explicit workspace views", () => {
  assert.match(detailPage, /角色工作台/);
  assert.match(detailPage, /任务输入与交付/);
  assert.match(detailPage, /任务审计/);
  assert.match(detailPage, /view === "delivery"/);
  assert.match(detailPage, /view === "audit"/);
});

test("persists the selected role attempt and inspector tab in the URL", () => {
  assert.match(detailPage, /useSearchParams/);
  assert.match(detailPage, /searchParams\.get\("role"\)/);
  assert.match(detailPage, /searchParams\.get\("attempt"\)/);
  assert.match(detailPage, /searchParams\.get\("tab"\)/);
});

test("keeps initial load and two-second polling focused on the task shell", () => {
  const initialLoad = detailPage.slice(
    detailPage.indexOf("const loadInitial"),
    detailPage.indexOf("const refreshCore")
  );
  const coreRefresh = detailPage.slice(
    detailPage.indexOf("const refreshCore"),
    detailPage.indexOf("const loadMaterialsData")
  );

  assert.match(initialLoad, /getRdTask\(taskId\)/);
  assert.doesNotMatch(initialLoad, /getRdTaskExecutionOverview|overview:/);
  assert.match(initialLoad, /onTask[\s\S]*setLoading\(false\)/);
  assert.doesNotMatch(initialLoad, /timeline:|materials:|qaEvidence:|retrievalRuns:|aiReviews:/);
  assert.match(coreRefresh, /getRdTask\(taskId\)/);
  assert.match(detailPage, /view === "roles"[\s\S]*getRdTaskExecutionOverview\(taskId\)/);
  assert.doesNotMatch(coreRefresh, /getRdTaskMaterials|getRdTaskQaEvidence|getRetrievalRuns|getAiReviews/);
});

test("loads prompt, evidence and audit blobs only when those views are selected", () => {
  assert.match(detailPage, /selectedRoleTab === "evidence"/);
  assert.match(detailPage, /view === "delivery"/);
  assert.match(detailPage, /view === "audit"/);
  assert.match(detailPage, /getRdTaskAuditContent\(taskId\)/);
  assert.match(detailPage, /view !== "roles" \|\| selectedRoleTab !== "evidence"/);
});

test("loads QA evidence for the default role deliverables tab without opening Prompt", () => {
  assert.match(detailPage, /const loadQaEvidenceData = useCallback/);
  assert.match(detailPage, /selectedRoleTab === "issues"[\s\S]*loadQaEvidenceData/);
});

test("serializes non-core detail polling and refreshes evidence when the stage signature changes", () => {
  assert.doesNotMatch(detailPage, /setInterval\(async/);
  assert.match(detailPage, /scheduleActiveDetailRefresh/);
  assert.match(detailPage, /rolePromptSignature !== loadedRoleEvidenceSignatureRef\.current/);
  assert.match(detailPage, /loadRoleEvidenceData\(rolePromptSignature\)/);
  assert.match(detailPage, /hasActiveEvidenceRetrieval/);
  assert.match(detailPage, /shouldPollRoleEvidence/);
  assert.match(detailPage, /shouldPollTask\(task\)/);
  assert.match(detailPage, /qaEvidenceError/);
});

test("keeps role-bound recovery inside the exact attempt rather than duplicating it in audit", () => {
  assert.match(detailPage, /isTaskLevelRecovery\(failureRecovery\)/);
  assert.match(detailPage, /failurePhase === "RAG"/);
  assert.match(detailPage, /failedRetrievalRunId/);
  assert.match(detailPage, /return !snapshot\.retryPoint\.retryFromRole/);
  assert.doesNotMatch(detailPage, /view === "audit" && task\.taskType === "REQUIREMENT" && canRetryRequirement\(task\)/);
});

test("resolves the recovery target before navigating from low-frequency views", () => {
  assert.match(detailPage, /handleOpenFailureRecovery/);
  assert.match(detailPage, /recoverySnapshot = await getTaskFailureRecovery\(taskSnapshot\.taskId\)/);
  assert.match(detailPage, /isTaskLevelRecovery\(recoverySnapshot\)/);
  assert.match(detailPage, /onClick=\{\(\) => void handleOpenFailureRecovery\(\)\}/);
});

test("clears task-owned detail state and guards async writes when the route task changes", () => {
  assert.match(detailPage, /requestGuardRef\.current\.beginTask\(taskId\)/);
  assert.match(detailPage, /setSelectedRetrievalRun\(null\)/);
  assert.match(detailPage, /setSelectedAiReview\(null\)/);
  assert.match(detailPage, /requestGuardRef\.current\.isCurrent\(requestToken\)/);
});

test("guards task mutations and clears their pending UI when the route changes", () => {
  const taskScopedActionTokens = detailPage.match(
    /requestGuardRef\.current\.capture\(taskSnapshot\.taskId\)/g
  ) || [];

  assert.ok(taskScopedActionTokens.length >= 9);
  assert.match(detailPage, /setSubmitting\(false\)/);
  assert.match(detailPage, /setApproving\(false\)/);
  assert.match(detailPage, /setStartingAiReview\(false\)/);
  assert.match(detailPage, /setBudgetApprovalOpen\(false\)/);
  assert.match(detailPage, /setApprovalMessage\(""\)/);
});

test("keeps retrieval and AI inspectors bound to the latest same-task selection", () => {
  assert.match(detailPage, /retrievalDetailLoadSeqRef/);
  assert.match(detailPage, /aiReviewDetailLoadSeqRef/);
  assert.match(detailPage, /requestSeq !== retrievalDetailLoadSeqRef\.current/);
  assert.match(detailPage, /requestSeq !== aiReviewDetailLoadSeqRef\.current/);
});

test("prevents an older core poll from overwriting a newer task mutation", () => {
  assert.match(detailPage, /coreLoadSeqRef/);
  assert.match(detailPage, /requestSeq !== coreLoadSeqRef\.current/);
  assert.match(detailPage, /coreLoadSeqRef\.current \+= 1/);
});

test("keeps failed requirements exclusive to recovery and retries transient prompt loads", () => {
  assert.match(detailPage, /canSubmitRequirementTask\(task\)/);
  assert.match(detailPage, /rolePromptInFlightRef/);
  assert.match(detailPage, /rolePromptError/);
  assert.match(detailPage, /scheduleActiveDetailRefresh\(\(\) => loadRolePrompts\(rolePromptSignature\)\)/);
  assert.match(detailPage, /loadingRoleEvidence/);
});
