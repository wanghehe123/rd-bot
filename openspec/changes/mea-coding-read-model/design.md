## Context

见 proposal。读取聚合必须在 PostgreSQL `REPEATABLE_READ` 只读事务内完成；复用既有 Store/mapper，不拷贝写入逻辑。完整结果链：stage → 精确 command（AuditRun / retry binding / durable identity）→ `rd_requirement_stage_finalizations.result_json` → 按角色解包。

## Goals / Non-Goals

**Goals:**
- 冻结 §4 TypeScript 合同与 link relation 枚举。
- 实现快照与完整结果 HTTP；历史缺 Manager / 缺 revision / 页外引用语义明确。
- 真实 Postgres 并发快照与归属隔离测试。

**Non-Goals:**
- 新表、缓存第二真值、GET 触发补跑/finalize。
- 为展示改 Manager decision hash / createdAt 领域合同。
- 把 command attemptNo 当 Agent Attempt。

## Decisions

1. **QueryEngine 与 Port 分离**：Port 返回有界原始引用；QueryEngine 生成展示 DTO 与缺失原因。
2. **Manager 时间**：仅返回已关联 Manager command 的创建时间，文案为「决策命令创建时间」。
3. **stateAtDecision**：按 decision 的 stateVersion/stateHash 读 revision；缺失则 `available=false`，禁止用 head 顶替。
4. **完整结果降级**：无 finalization 绑定时 `ARTIFACT_PREVIEW` 或 `UNAVAILABLE`，不假下载。

## Verified associations (B04 / 2026-09-06)

核验基线：`3fcd7db6` 源码。下列为当前代码事实，不是计划臆测。

| 关系 | 实际身份 | 不存在 / 禁止 |
| --- | --- | --- |
| command → stage | 1) `AuditRun.commandId` + `subjectStageRunId`（`UNIQUE(command_id)`）2) `command.targetRetryBindingId` → `TaskRetryAttemptBinding.attemptId`（`kind=AGENT_STAGE` 时即 `stage_run_id`）3) remediation `source/target*StageRunId` | **无** `RequirementStageCommand.stageRunId` 列。禁止用 `command.attemptNo` 猜 `AgentStageRun.attemptNo`。禁止复用写路径 `latestStageOrNull`。 |
| decision → command | `ManagerDecision.sourceCommandId` = **触发** command（QA / HOST_VERIFY）。Manager command `stage` = `MANAGER_DECIDE:` + sourceId | 两字段不相同。领域决策无 `createdAt`；`toDecision` 丢弃行时间。展示时间取 Manager command 的 `createdAtEpochMillis`。无 `findByHash`：`listByTask` 后按 `decisionHash` 过滤（hash 非 UNIQUE）。 |
| round → QA | `AgentRemediationRound.targetQaStageRunId` / `firstCommandId`；同 `remediationRoundId` 的 QA command | 普通首次 Coding 无前置 Manager、QA 后 Manager 退出世代均为正常，不补造 round。 |
| audit → 历史 state | mapper `findRevision(taskId, stateVersion)` 已存在；store **未暴露**。PK `(task_id, state_version)`，无 hash 查询 | `head()` 是当前头。`stateAtDecision` 必须读 revision 并核 `stateHash`；缺失 `available=false`，禁止用 head 顶替。 |
| HV → Coding | `HostVerificationRun.codingStageRunId` | `listByTask` 已有。 |
| 完整结果 | `rd_requirement_stage_finalizations.result_json`，PK `(command_id, attempt_no)`，finalize 后 `FINALIZED` | `findLatestPrepared` **排除** FINALIZED，只服务恢复。B06 必须新增 FINALIZED 精确读。QA 解包复用 `PiQaRemediationPlanner.authoritativeQaResultJson`。`AgentStageArtifact.contentPreview` 上限 20_000；`rd-agent-stage://` 不是通用下载。 |

**完整结果缺口（写入 design，B06 实施）：** 无按 task/stage 读完整 RESULT_JSON 的现成 HTTP；无 artifact `findById` store API；无 command `listByTask` / 分页 / `targetRetryBindingId` 查询。新增只读 mapper，不回填历史绑定。

## Invariants

- 无新业务表、无第二份调度真值缓存。
- GET 不调用 finalize / `ManagerPolicy.decide` / executor / publication / 任何更新端口。
- 不读取 task 全量大文本（Prompt / result / AGENT_EVENTS）；默认 payload 只有引用。
- Manager 时间不进入 `decisionHash`，不改领域写入合同。
- 跨 task 的 stage/command/decision 引用：关系 `available=false` 或 HTTP 404，不泄漏他任务摘要。
- 分页不截断单条对象；页外节点保留 ID 并标 `OUTSIDE_PAGE`。

## Risks / Trade-offs

- [关联无法唯一] → 返回 null + reason，不猜测。
- [大 payload] → 默认截断/引用；按需全文端点。
- [与前端并行] → fixture 锁定；联调 identity：W1e `7502196308401328128`、W1g `7502261872498970624`、W2B `7502311153071165440`、W3J `7502300444828504064`。

## Migration Plan

无 schema migration。部署随常规 backend 发布；纯读取不重建 Pi 镜像。

## Open Questions

- 历史 revision：B05 在 read port 包装已有 `findRevision`，不改写路径。
