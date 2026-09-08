# Coding MEA 读取模型规范

## Purpose

定义 Coding 内 MEA 只读快照与完整角色结果读取合同：有界引用聚合、精确身份关联、历史 revision / 分页语义，以及 finalization 结果来源与降级，且 GET 不得修改业务状态。

## Requirements

### Requirement: Coding MEA 快照只读且有界

系统 SHALL 提供 `GET /admin/rd-tasks/{taskId}/coding-mea`，在单一 PostgreSQL 只读 `REPEATABLE_READ` 快照内聚合 Coding 因果邻域。省略 `codingStageRunId` 时选择该 task 最新 `CODING_AGENT` stage；不存在则 `available=false` 且 `unavailableReason=NO_CODING_STAGE`。显式 ID 必须属于该 task 且 role 为 `CODING_AGENT`，否则 404。默认响应 MUST NOT 包含全量 Prompt、result、stderr 或 AGENT_EVENTS 正文。

#### Scenario: 无 Coding stage

- **WHEN** 任务尚无 CODING_AGENT stage
- **THEN** 返回 200 且 `available=false`、`unavailableReason=NO_CODING_STAGE`，不创建任何记录

#### Scenario: 跨任务 codingStageRunId

- **WHEN** 查询参数指向其他 task 的 stageRunId
- **THEN** 返回 404，不泄漏他任务摘要

### Requirement: 历史决策状态不得用当前 head 顶替

对每个 Manager decision，`stateAtDecision` SHALL 按该决策的 `stateVersion`/`stateHash` 读取审计 revision。版本缺失时 MUST `available=false`，MUST NOT 用当前 head 填充。

#### Scenario: revision 存在

- **WHEN** decision.stateVersion=8 且 revision8 存在、当前 head 为 10
- **THEN** `stateAtDecision` 来自 revision8

### Requirement: 完整角色结果经精确 identity 读取

系统 SHALL 提供 stage 结果读取端点，通过 AuditRun 的 `commandId + subjectStageRunId`、retry binding 或已有 durable identity 定位 command，再从已落盘 finalization 解出该角色结果。无法证明主体一致时 MUST 不可用，MUST NOT 选择「最后一个同名角色」。

#### Scenario: 仅有 preview

- **WHEN** 历史 stage 仅有 artifact preview、无绑定 finalization
- **THEN** `source=ARTIFACT_PREVIEW`，`downloadPath=null`，`unavailableReason` 标明完整结果未持久化或未绑定

### Requirement: 查询不得改变业务状态

Coding MEA 与 stage 结果 GET SHALL 不调用 finalize、ManagerPolicy.decide、executor、publication 或任何更新端口；查询前后 task/command/decision/audit 行数、版本与 hash MUST 不变。

#### Scenario: 纯度

- **WHEN** 连续两次读取同一任务快照且期间无其他写者
- **THEN** 业务表版本与 hash 与查询前相同
