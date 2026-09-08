## Context

阶段 2（`requirement/fresh-executor-episode`）已归档。生产续跑仍是两条独立权威：`RequirementDeliveryEngine` 冻结的 `ContinuationSpec`，以及 `RequirementDeliveryDispatchService.nextRoleTarget` / 死代码 `nextStageCommand`。后者在 `RequirementStageCommandMapper.find` 滤掉 remediation 行之后，会把已成功的普通世代角色链看成「全 SUCCEEDED」并返回 `DETERMINISTIC_REVIEW`——这是 P3-W1 在 lease reclaim 上会静默失败的原因。

`paused` 只写 `rd_tasks.paused`，claim SQL 不 join 任务行。`WAITING_USER_INPUT` 不存在。

## Goals / Non-Goals

**Goals**

- HOST_VERIFY / QA 成功后进入 `MANAGER_DECIDE:<sourceCommandId>`。
- 3.1：QA 后 PENDING 阻断 AC → `MANAGER_GAP_FIX` 有界 Coding，再验证与重审计。
- ASK / 暂停 / dispatcher 双权威合一。
- 源码守卫：Manager 无 executor/workspace/container 类型引用。

**Non-Goals**

- 不改 `COMPLETED` 写入者集合、gate-mode、QA 指纹、fresh episode Prompt。
- 不把 Manager 插在 Coding 与 HOST_VERIFY 之间。
- 不复用 `QA_PRODUCT_FIX` 作为 Manager 缺口 hop（会消耗 v2 配额并要求 QA 目标行）。
- 不改观测 SUCCESS 分子（仍留给 `upgrade-delivery-observability` 后续 delta）。

## Decisions

### D1. 命令身份用 source command id，round_no 在决策表上顺序分配

**决定**：stage = `MANAGER_DECIDE:<previous.commandId()>`（普通世代，类似 `PUBLICATION:<operationId>`）。`rd_task_manager_decisions.round_no` 在该 Manager command 的 finalize 内取 `max(round_no)+1`。`UNIQUE(task_id, source_command_id)` 保证 QA/HOST_VERIFY finalize 重放不会插入第二条 Manager command。

**放弃**：裸 `MANAGER_DECIDE`（第二次碰撞普通世代唯一索引）；用 id 生成器当 roundNo（重放会新开 round）。

### D2. 额外 Coding 用 MANAGER_GAP_FIX，形状对齐 HOST_VERIFY_FIX

**决定**：`AgentRemediationKind.MANAGER_GAP_FIX(2)`；rounds 目标列为 Coding 非空、QA 空；command generation CHECK 列入该 kind。不要求 `PI_QA_REMEDIATION_V2`。source_stage_run_id 为暴露缺口的 QA `AgentStageRun`。同一 `remediation_round_id` 必须从缺口 Coding 传到随后的 HOST_VERIFY、该轮 Manager 与 QA。QA 成功后的下一条 `MANAGER_DECIDE:*` 才退出到普通世代（`shouldCopyRemediationGeneration`：previous=QA 且 next=`MANAGER_DECIDE:*` 时不复制）。与 `QA_PRODUCT_FIX` / `HOST_VERIFY_FIX` 共享 `MAX_ROLE_ATTEMPTS=3`。

**放弃**：复用 `QA_PRODUCT_FIX`；第四种 command 世代。

### D3. 插入点仅 HOST_VERIFY 成功与 QA 成功

**决定**：Coding 输出是 UNTRUSTED claim。协议失败仍由 `PiQaRemediationPlanner` 一次性 `QA_PROTOCOL_RETRY`。HOST_VERIFY 产品缺陷仍由 `planHostVerifyStage` 铸造 `HOST_VERIFY_FIX`。产品缺口（QA 成功但 AC PENDING）改由 Manager，不再依赖 v2 关闭时缺席的 `QA_PRODUCT_FIX`。

### D4. dispatcher 以决策表为 EXECUTING 路由权威

**决定**：`nextRoleTarget` 在 HOST_VERIFY 已 SUCCEEDED 后查找 `MANAGER_DECIDE:<hostVerify.commandId>` 及其决策；QA 已 SUCCEEDED 后同样。禁止在「全部普通世代角色 SUCCEEDED」时无条件返回 `DETERMINISTIC_REVIEW`。`nextStageFor(WAITING_USER_INPUT)` 抛错。`continuationCommand` 去掉对 HOST_VERIFY→QA 的隐含假设（以 plan.continuation 为准）。

### D5. ASK 镜像 APPROVAL_RESUME，paused 在 claim 谓词生效

**决定**：新端口 `RequirementUserAnswerTransactionPort.answer` / `consumeAnswer`，不经 `planStage`。claim/recoverable SQL 与 dispatcher Java 过滤：`paused=true` 不领取；`WAITING_USER_INPUT` 只允许 `USER_ANSWER_RESUME`。

### D6. 决策挂在 execution plan 上，schema 仍为 v3 记录的可选字段

**决定**：`RequirementStageExecutionPlan` 增加可选 `managerDecision`。缺省字段的旧 JSON 仍可解码（Jackson 缺字段为 null）。finalize 写入决策表。不另开 schema v4，避免与审计 mutation 编解码大面积分叉。

## Risks / Trade-offs

- [Risk] 双权威未改干净 → reclaim 仍进复核。Mitigation：`nextRoleTarget` 单测覆盖「QA 普通世代已 SUCCEEDED + MANAGER_GAP_FIX 仍 PENDING」。
- [Risk] 缺口 Coding 用尽 attempt → 必须 BLOCKED。Mitigation：ManagerPolicy 读总 Coding attempt。
- [Risk] 暂停与领取竞态。Mitigation：SQL claim 也 join `rd_tasks`。

## Migration Plan

1. 合入 p22 与代码（本轮按用户要求不跑测试、不部署）。
2. 验证命令见 tasks.md。真机 P3-W1 路由证据见 `docs/superpowers/qa/2026-09-06-mea-p3-manager-handoff.md`；P3-W2/W3 仍待用户要求后再部署。
3. 回滚：停用 Manager hop 需回退 jar；p22 表可留空。

## Open Questions

无。P3-W1 为阶段退出；3.0 单独回放固定顺序不算完成。
