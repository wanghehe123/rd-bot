## Why

阶段 1 把完成权收口到 `AuditedTaskState`，阶段 2 让新 Attempt 只携带已审计缺口。但成功续跑仍由 `roleContinuation` / `nextRoleTarget` 的固定顺序决定：QA 成功一律进 `DETERMINISTIC_REVIEW`。外卖 W5 已证明：QA 可以诚实留下未覆盖的 `AC-*`，静态链仍会走向复核与完成门。阶段 3 要把「下一步」改成 Host 只读 Manager：读 head、选一个有界状态变化，而不是回放 `requirementDeliveryOrder()`。

## What Changes

- 新增 durable stage `MANAGER_DECIDE:<sourceCommandId>`（role `REQUIREMENT_DELIVERY`），在 `HOST_VERIFY` 成功/docs-only 与 `QA_AGENT` 成功之后由 continuation 进入。Coding 成功续跑仍是 `HOST_VERIFY`（RULE.md 3.5.3）。
- `ManagerPolicy` 纯函数产出 `ManagerDecision{route∈EXECUTE|DONE|BLOCKED|ASK|REPLAN}`，finalize 同事务写入 `rd_task_manager_decisions UNIQUE(task_id, round_no)`，幂等于 `state_version`。
- 3.0：HOST_VERIFY 成功只路由 QA；QA 协议失败仍走 `QA_PROTOCOL_RETRY`；HOST_VERIFY 产品失败仍走 `HOST_VERIFY_FIX`。
- 3.1（本阶段退出条件）：QA 成功后若仍有阻断 `AC-*` PENDING，Manager 派发 `MANAGER_GAP_FIX` 有界 Coding（合同点名记录 ID），再 `HOST_VERIFY` + QA + `auditQa`；不得在缺口仍在时进入 `DETERMINISTIC_REVIEW`。
- `ASK` → 新状态 `RdTaskStatus.WAITING_USER_INPUT`（不复用 `WAITING_APPROVAL`）+ `POST /admin/rd-tasks/{id}/answer` + `USER_ANSWER_RESUME`。
- `paused=true` 时 dispatcher 不得 claim 任何 stage command。
- dispatcher `nextStageFor(EXECUTING)` / `continuationCommand` 以已持久化 `ManagerDecision` 为路由权威，禁止 `requirementDeliveryOrder()` 在 QA 成功后直接返回 `DETERMINISTIC_REVIEW`。

## Capabilities

### New Capabilities

- `requirement/manager-decision-command`：已审计 command 之后的有界 Manager 决策、ASK 等待用户输入、暂停不领取。

### Modified Capabilities

- 无。`requirement/audit-only-writeback` 的完成门与 `requirement/fresh-executor-episode` 的 Prompt 合同不变。

## Impact

- **rag**：`RdTaskStatus.WAITING_USER_INPUT`；`RdTaskTransitionPolicy` 仅需求图新增边。
- **engine**：`ManagerPolicy` / `ManagerDecision` / `planManagerDecisionStage`；`MANAGER_GAP_FIX`；QA/HOST_VERIFY 成功 continuation。
- **bootstrap**：p22 SQL、决策 store、finalize 同事务写入、claim 排除暂停与等待输入、`POST .../answer`、dispatcher 路由。
- **frontend**：与 `WAITING_APPROVAL` 区分的徽标；回答入口；Vite nested-route 合同。
- **非目标**：契约版本化（阶段 4）、预算账本（阶段 5）、把 Manager 放进容器、改 SUCCESS 观测词典、BugFix 图。

## 证据与来源

- 计划（PLAN_OR_DECISION）：`docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md` 决策 K6 / 阶段 3；`docs/superpowers/plans/2026-09-04-mea-next-phases-waimai.md` Task 11。
- 当前代码（2026-09-05）：`RequirementDeliveryEngine#roleContinuation`、`planHostVerifyStage` 成功续 QA；`RequirementDeliveryDispatchService#nextRoleTarget` 走 `requirementDeliveryOrder()`；`PiQaRemediationPlanner`；`paused` 未进入 claim；无 `WAITING_USER_INPUT`。
- 架构评审（本轮）：Option A（`MANAGER_DECIDE:<sourceCommandId>` + `MANAGER_GAP_FIX`）；拒绝复用 `QA_PRODUCT_FIX`；拒绝插在 Coding 与 HOST_VERIFY 之间。
- 历史资料：`docs/openspec/historical-spec-provenance-audit.md` 未收录 Manager 合同。
- 本轮命令：只读检索上述符号；`openspec new change mea-manager-decision-command`。按用户要求本轮不开启测试。
