import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const detailPage = readFileSync(
  new URL("../src/pages/admin/rdtask/RdTaskDetailPage.tsx", import.meta.url),
  "utf8"
);
const roleWorkbench = readFileSync(
  new URL("../src/components/admin/rdtask/TaskRoleWorkbench.tsx", import.meta.url),
  "utf8"
);
const roleAgentStateCard = readFileSync(
  new URL("../src/components/admin/rdtask/RoleAgentStateCard.tsx", import.meta.url),
  "utf8"
);

test("distinguishes WAITING_USER_INPUT from WAITING_APPROVAL with purple tone and concurrency fencing", () => {
  // WAITING_USER_INPUT has purple badge tone
  assert.match(detailPage, /WAITING_USER_INPUT:\s*"bg-violet-600"/);
  // Distinct explanation
  assert.match(detailPage, /任务正在等待操作员材料（WAITING_USER_INPUT），与策略预算审批（WAITING_APPROVAL）不是同一状态/);
  // Answering form carries concurrency tokens
  assert.match(detailPage, /canAnswerRequirement/);
  assert.match(detailPage, /!task\.paused && task\.status === "WAITING_USER_INPUT"/);
  assert.match(detailPage, /fencingToken/);
  assert.match(detailPage, /managerDecisionHash/);
  assert.match(detailPage, /answerRequestId/);
});

test("preserves task stage inspection when task is paused without forging cancelled state", () => {
  assert.match(detailPage, /pauseRdTask/);
  assert.match(detailPage, /resumeRdTask/);
  // Paused banner exists
  assert.match(detailPage, /task\.paused/);
  // Does not force status to CANCELLED on pause
  assert.doesNotMatch(detailPage, /task\.paused.*status = "CANCELLED"/);
});

test("implements 2/3 main column and 1/3 sticky sidebar layout on desktop", () => {
  // V2: 单份响应式 DOM——状态卡 DOM 在前（窄屏先于内容 tabs），桌面通过显式 grid 放置到右列
  assert.match(roleWorkbench, /lg:grid-cols-3/);
  assert.match(roleWorkbench, /lg:col-start-3 lg:row-start-1/);
  assert.match(roleWorkbench, /lg:col-start-1 lg:col-span-2/);
  assert.match(roleWorkbench, /lg:sticky/);
  assert.match(roleWorkbench, /lg:top-16/);
});

test("keeps the selected attempt status before the content tabs with a single status DOM", () => {
  // 窄屏：aside（状态卡）在 DOM 中先于内容 TabsList，且只有一份状态组件
  const asideIndex = roleWorkbench.indexOf("RoleAgentStateCard");
  const tabsListIndex = roleWorkbench.indexOf('value="issues">产物与证据');
  assert.ok(asideIndex >= 0);
  assert.ok(tabsListIndex > asideIndex);
  assert.equal((roleWorkbench.match(/<RoleAgentStateCard/g) || []).length, 1);
  // 所选阶段行显示当前/历史标签，不再使用占位 resultSummary 副标题
  assert.match(roleWorkbench, /当前 Attempt|历史 Attempt/);
  assert.doesNotMatch(roleWorkbench, /selectedStage\?\.resultSummary/);
});

test("provides compact collapsible view on mobile (390px/900px) to prevent pushing deliverables off first screen", () => {
  assert.match(roleAgentStateCard, /block lg:hidden/);
  assert.match(roleAgentStateCard, /mobileExpanded/);
  assert.match(roleAgentStateCard, /展开完整状态与待办|收起状态详情/);
  assert.match(roleAgentStateCard, /hidden lg:block/);
});
