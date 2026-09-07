import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const roleWorkbench = readFileSync(
  new URL("../src/components/admin/rdtask/TaskRoleWorkbench.tsx", import.meta.url),
  "utf8"
);
const roleContextCard = readFileSync(
  new URL("../src/components/admin/rdtask/RoleEffectiveContextCard.tsx", import.meta.url),
  "utf8"
);
const roleAgentStateCard = readFileSync(
  new URL("../src/components/admin/rdtask/RoleAgentStateCard.tsx", import.meta.url),
  "utf8"
);
const detailPage = readFileSync(
  new URL("../src/pages/admin/rdtask/RdTaskDetailPage.tsx", import.meta.url),
  "utf8"
);
const taskService = readFileSync(
  new URL("../src/services/rdTaskService.ts", import.meta.url),
  "utf8"
);

test("loads stage-bound role prompts and renders effective context, static prompt, and latest state", () => {
  // Service contract
  assert.match(taskService, /getRdTaskRolePrompts/);
  assert.match(taskService, /\/admin\/rd-tasks\/\$\{taskId\}\/role-prompts/);
  assert.match(taskService, /interface RdTaskEffectiveContext/);
  assert.match(taskService, /interface RdTaskLatestAgentState/);
  assert.match(taskService, /interface RdTaskAgentTodo/);
  assert.match(taskService, /interface RdTaskAgentStateBudget/);

  // Detail page renders workbench with freshness evaluation
  assert.match(detailPage, /TaskRoleWorkbench/);
  assert.match(detailPage, /evaluateRolePromptsFreshness/);
  assert.match(detailPage, /任务执行基线 Prompt/);
  const workbenchModel = readFileSync(
    new URL("../src/pages/admin/rdtask/roleWorkbenchModel.ts", import.meta.url),
    "utf8"
  );
  assert.match(workbenchModel, /respStateAvailable = Boolean\(stage\.latestState\?\.available\)/);
  assert.match(workbenchModel, /respInjAvailable = Boolean\(stage\.effectiveContext\?\.available\)/);

  // Workbench renders RoleEffectiveContextCard and evidence sections
  assert.match(roleWorkbench, /RoleEffectiveContextCard/);
  assert.match(roleWorkbench, /上下文证据/);
  assert.match(roleWorkbench, /RetrievalRun/);

  // RoleEffectiveContextCard renders effective context and static prompt
  assert.match(roleContextCard, /角色有效上下文/);
  assert.match(roleContextCard, /有效上下文/);
  assert.match(roleContextCard, /静态 Prompt/);
  assert.match(roleContextCard, /MarkdownRenderer/);
  assert.match(roleContextCard, /这是派发时静态指令，不含运行中最新状态/);
  assert.match(roleContextCard, /安全预览已截断/);
  assert.match(roleContextCard, /PROMPT_SNAPSHOT → AGENT_STATE/);
  assert.doesNotMatch(roleContextCard, /promptSnapshot/);
  assert.match(roleContextCard, /!promptStage[\s\S]*当前 Attempt 尚无已绑定的 Prompt 读模型/);

  // RoleAgentStateCard hosts the persistent right-side state card
  assert.match(roleWorkbench, /RoleAgentStateCard/);
  assert.match(roleAgentStateCard, /AgentLatestStatePanel/);
  assert.match(roleAgentStateCard, /AgentTodoList/);
  assert.match(roleAgentStateCard, /查看安全原始状态/);
});
