# Requirement Manager Decision Command Specification

## Purpose

定义需求交付在已审计 command 之后的 Host Manager 合同：下一步由只读决策选择有界状态变化；用户补充材料使用独立的等待输入状态；暂停任务不得被领取。

## Requirements

### Requirement: 已审计成功之后必须进入 Manager 决策
系统 SHALL 在 `HOST_VERIFY` 以 `SUCCEEDED` 或 `SKIPPED_DOCS_ONLY` 结束、以及 `ROLE_EXECUTION:QA_AGENT` 以协议有效的 `SUCCEEDED` 结束之后，把 continuation 设为 role `REQUIREMENT_DELIVERY`、stage `MANAGER_DECIDE:<sourceCommandId>` 的 durable command。`sourceCommandId` MUST 等于刚结束的那条 command 的 id。系统 MUST NOT 在 `ROLE_EXECUTION:CODING_AGENT` 成功与 `HOST_VERIFY` 之间插入 Manager。QA 协议失败 MUST 仍只走允许名单内的一次性 `QA_PROTOCOL_RETRY`，不得由 Manager 抢占或合成结果。

#### Scenario: HOST_VERIFY 成功后续跑 Manager
- **WHEN** `HOST_VERIFY` command 成功或 docs-only 跳过
- **THEN** 同 finalize 事务插入的下一条 command 的 stage 为 `MANAGER_DECIDE:<该 HOST_VERIFY commandId>`，而不是直接 `ROLE_EXECUTION:QA_AGENT`

#### Scenario: QA 成功后续跑 Manager
- **WHEN** `QA_AGENT` command 协议校验通过并以 `SUCCEEDED` 结束（即使部分 `AC-*` 仍为 PENDING）
- **THEN** continuation stage 为 `MANAGER_DECIDE:<该 QA commandId>`，而不是 `DETERMINISTIC_REVIEW`

#### Scenario: Coding 成功仍直接 HOST_VERIFY
- **WHEN** `CODING_AGENT` command 成功
- **THEN** continuation 仍为 `HOST_VERIFY`，不含 `MANAGER_DECIDE`

### Requirement: Manager 决策幂等且与 continuation 同事务
系统 SHALL 由 engine 纯函数 `ManagerPolicy` 根据 `AuditedTaskState` head、上一 command、暂停标记与 Coding attempt 预算产出 `ManagerDecision`。决策 MUST 在 `RequirementStageFinalizationPort.finalize` 内与 continuation（或 ASK 状态迁移）同一 PostgreSQL 事务写入 `rd_task_manager_decisions`。`UNIQUE(task_id, round_no)`；同 `state_version` 重放 MUST 得到同一 `decision_hash`，不得新开 round。Manager MUST NOT 依赖 `RepairExecutorPort`、工作区或容器。无法按决策铸造 intent 时 MUST 记 `BLOCKED` 并停止，MUST NOT 回落到 `DETERMINISTIC_REVIEW`。

#### Scenario: 同版本重放不新开 round
- **WHEN** 同一 `MANAGER_DECIDE` command 的 finalize 被重放且 head 的 `state_version` 未变
- **THEN** 不插入第二条 `(task_id, round_no)` 决策，continuation command 身份与首次相同

#### Scenario: 铸造失败不得进入复核
- **WHEN** Manager 选择对 PENDING `AC-003` 做有界 Coding，但 intent 因 attempt 预算耗尽无法铸造
- **THEN** 决策 route 为 `BLOCKED`，任务不得进入 `DETERMINISTIC_REVIEW`

### Requirement: QA 后未覆盖的验收必须派发有界 Coding
当上一审计主体为 QA 且 head 中仍存在 `kind=REQUIREMENT`、`blocking=true`、`status=PENDING` 的记录时，系统 SHALL 选择 `EXECUTE`，`executorRoute=CODING_AGENT`，`boundedContract` MUST 点名那些记录 ID。该 Coding hop MUST 使用 remediation 世代 `MANAGER_GAP_FIX`（不得占用 `(task_id, role, stage)` 普通世代），MUST 继承同一 `remediation_round_id` 走后续 `HOST_VERIFY` 与 QA，再退出到下一条普通世代 `MANAGER_DECIDE`。`MANAGER_GAP_FIX` MUST NOT 要求 `piQaRemediationV2Enabled`。系统 MUST NOT 在仍有阻断 PENDING `AC-*` 时进入 `DETERMINISTIC_REVIEW`。HOST_VERIFY 成功的 3.0 决策 MUST 只路由 QA。

#### Scenario: 未实现的 AC 触发有界修复
- **WHEN** QA 成功且 head 中 `AC-003` 为 PENDING、Coding attempt 未耗尽
- **THEN** Manager 冻结 `MANAGER_GAP_FIX`，首个 command 为 `ROLE_EXECUTION:CODING_AGENT`，请求 JSON/角色 Prompt 含 `AC-003`，随后 HOST_VERIFY 与 QA 带同一 `remediation_round_id`

#### Scenario: 全部阻断验收已完成后才复核
- **WHEN** QA 成功且所有阻断 `REQUIREMENT` 记录均为 `COMPLETED`
- **THEN** Manager route 为 `DONE`，continuation 为 `DETERMINISTIC_REVIEW`

#### Scenario: HOST_VERIFY 成功只路由 QA
- **WHEN** `HOST_VERIFY` 成功后的 Manager 运行且任务未暂停
- **THEN** continuation 为 `ROLE_EXECUTION:QA_AGENT`，不铸造 `MANAGER_GAP_FIX`

### Requirement: ASK 使用 WAITING_USER_INPUT 且回答后从决策续做
系统 SHALL 新增 `RdTaskStatus.WAITING_USER_INPUT`，仅写入需求状态图：`EXECUTING → WAITING_USER_INPUT`；`WAITING_USER_INPUT → EXECUTING | REJECTED | FAILED_RETRYABLE | FAILED_NEEDS_HUMAN | CANCELLED | DEAD_LETTERED`；`RECOVERING → WAITING_USER_INPUT`。BugFix 图 MUST NOT 包含该状态。`ASK` MUST NOT 写入 `WAITING_APPROVAL`。恢复 MUST 经 `POST /admin/rd-tasks/{taskId}/answer` 产生 `USER_ANSWER_RESUME`（不经 `planStage`）。在 `WAITING_USER_INPUT` 期间 dispatcher MUST NOT claim 角色 command；`nextStageFor` MUST 像 `WAITING_APPROVAL` 一样失败关闭。该状态是在途：观测 SUCCESS/FAILURE/TERMINAL 均不含它。前端徽标 MUST 与 `WAITING_APPROVAL` 可区分。

#### Scenario: 缺材料时进入等待输入
- **WHEN** Manager 判定缺少推进所需材料并选择 `ASK`
- **THEN** 任务状态为 `WAITING_USER_INPUT`，不插入角色 command

#### Scenario: 回答后恢复执行
- **WHEN** 任务为 `WAITING_USER_INPUT` 且 `POST /admin/rd-tasks/{id}/answer` 带有与当前 version/fence 绑定的正文
- **THEN** 插入 `USER_ANSWER_RESUME`；消费后任务回到 `EXECUTING` 并再入 Manager，不得调用泛化 `submit()` 铸造角色 command

### Requirement: 暂停任务不得被领取
当需求任务 `paused=true` 时，系统 SHALL 不把该任务的 stage command 纳入 recoverable/claim 结果（含已 PENDING 的角色 command 与 `MANAGER_DECIDE`）。`USER_ANSWER_RESUME` 在暂停期间同样不得领取。操作员 `POST .../resume` 清除暂停后，既有 PENDING command 才可再被 claim。

#### Scenario: 暂停后 dispatcher 不 claim
- **WHEN** 任务 `paused=true` 且存在 PENDING `ROLE_EXECUTION:QA_AGENT` 或 `MANAGER_DECIDE:*`
- **THEN** claim 批次不含这些 command
