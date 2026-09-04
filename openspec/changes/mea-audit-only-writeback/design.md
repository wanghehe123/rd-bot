## Context

本 change 是《RD-Bot × DreamX LongHorizon-Harness 方法论改造指南》阶段 1（Audit-only writeback 最小闭环）的落地设计。总体路线、九步现状核查与后续阶段见 `docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md`。

本轮已按真实代码链路核实的现状（锚点为当前代码符号；行号为 2026-09-03 读码时的近似位置）：

- **完成权已收口到 Host，但完成不绑证据。** 只有 `RequirementDeliveryEngine#planCompletionStage` / `executeCompletionStage` 把需求任务写为 `COMPLETED`，经 `RequirementStageFinalizationPort.finalize` 原子提交；但该 mutation 的 `executionResultJson` 与证据字段为空（`planCompletionStage`：`mutation(task.status(), COMPLETED, "", "", "", "")`），`rd_tasks` 也没有任何指向审计证据的列。
- **确定性复核以 Agent JSON 为准真相。** `RequirementDeliveryReviewer#review`（第 76–132 行）只检查发布视图的形状：`successfulRoles` 含四角色、`CODING_AGENT.testStatus ∈ {PASSED, SKIPPED}`、`QA_AGENT.status=PASSED`、证据 URI 为持久引用、`acceptanceResults` 含 CURRENT+REGRESSION。它不读取 `rd_host_verification_runs`，不比对独立环境观察。
- **宿主验证在生产路径被绕过。** `RequirementDeliveryDispatchService.submit` 逐条派发 `rd_requirement_stage_commands`；`RequirementDeliveryEngine#boundedRolePlan` 把 `hostVerifyRemediationEnabled=false`，因此 `RequirementAgentStageOrchestrator#gateQaBehindHostVerification` 只在 legacy `executeAgentStages(AgentWorkflowPlan.production())` 路径生效。`roleContinuation(CODING_AGENT)` 直接续派 `ROLE_EXECUTION:QA_AGENT`。
- **QA 只读靠 prompt 与部分工具策略。** `DockerPiAgentExecutor.READ_ONLY_REPO_ROLES = {REQUIREMENT_REVIEWER, SOLUTION_ARCHITECT}`（第 100–102 行），QA 的 `/work/repo` 为 rw；`default-qa@2` 工具策略 deny `edit`/`write` 但允许 `bash`；`RepairWorkspaceRepositoryPort.repositoryState`（内容级 SHA-256，注释即 "QA no-mutation guard"）在生产代码中没有任何调用方。
- **没有任务级显式状态。** `rd-agent-state/v2` 是单次 Pi session 内的状态栏（Host 验收 TODO + agent TODO + 最多 32 条 `rd_record_fact`），随 `stageRunId` 重建；跨 episode 的权威状态只有 `rd_agent_stage_runs` + artifacts + compact 交接 JSON。
- **验收标准已有稳定编号来源。** `PiAgentContextStateManager#prepareInitialState` 以 `AC-%03d` 按 `acceptanceCriteriaJson` 顺序生成 Host 验收 TODO；PI-v2 QA 的 `acceptanceResults[].criteriaId` 目前只在 FAILED 项与 `bugFindings[].acceptanceCriteriaId` 闭环时被 `QaEvidenceBundleValidator` 与 `result-tool.mjs` 校验，PASSED 项不要求 ID；发布视图 `RequirementDeliveryPublicationView.AcceptanceResult.criteria` 为自由文本。
- **两阶段终态边界已存在且可搭乘。** `RequirementStageExecutionPlan`（schema v2，含 `piQaRemediationIntent`）由 `RequirementStageExecutionPlanCodec` canonical 编码进 `rd_requirement_stage_finalizations`，`PREPARED → OUTCOME_RECORDED → FINALIZED`；`PiRemediationFinalizationWriter` 已示范如何在 finalize 事务内追加旁路账本并分配轮次。
- **策略控制命令走独立事务端口。** `POLICY_EVALUATE` / `POLICY_APPLY` / `APPROVAL_RESUME` 由 `RequirementDeliveryDispatchService#runPolicyApplyCommand` 等经 `RequirementPolicyTransactionPort`（`PostgresRequirementPolicyTransactionAdapter`、`InMemoryRequirementPolicyTransactionAdapter`）消费，不经 `planStage`/`finalize`；dispatcher 明确 "legacy POLICY stage is quarantined; durable POLICY_EVALUATE is required"。任务进入 `EXECUTING` 与首个 `ROLE_EXECUTION` command 的插入发生在 `consumePolicyApply(ALLOWED)` 事务内。
- **在途 change 关系。** `openspec/changes/host-verification-pipeline/` 的 `tasks.md` 全部未勾选，但 `HostVerificationPort`、`p15_host_verification.sql`、orchestrator 门与测试均已在代码中；其设计 D2「编排插入点在 Orchestrator」只覆盖 legacy 路径。`upgrade-delivery-observability` 把 `COMMITTED/MERGED/COMPLETED` 都记为 SUCCESS（`DeliveryObservabilityQueryService` 第 48 行）。

## Goals / Non-Goals

**Goals**

1. 未经 Host 审计的 Executor 声明不能把需求任务推进到 `COMPLETED`；构造「Executor 虚报成功」测试时系统必须拒绝完成。
2. 每个 `COMPLETED` 记录与任务完成本身都能追溯到具体 `AuditRun` 与可解析证据引用。
3. 宿主 BUILD/STATIC 验证在生产逐条 command 路径生效，且作为独立 durable command 持久化。
4. QA 审计的只读性至少有一项技术强制（tracked-tree 指纹），违规时 fail-closed。
5. 写回沿用既有 `OUTCOME_RECORDED → FINALIZED` 边界，crash-safe、幂等、可恢复。

**Non-Goals**

- 不引入动态 Manager、`ask` 路由、契约版本化或重新规划（阶段 3/4）。
- 不改变角色 prompt 的失败反馈注入与 `environmentNotes` 标注（阶段 2）。
- 不做基于模型的契约反查；`contractAudit` 在本 change 只取 `ALIGNED`/`UNKNOWN` 两个确定性值（阶段 4）。
- 不改任务级预算、无进展检测（阶段 5）；不接线项目记忆晋升（阶段 6）。
- 不删除 legacy `RequirementDeliveryEngine.submit()`；只保证它受同一完成门约束。
- 不改 `upgrade-delivery-observability` 的 SUCCESS 词典（2026-09-03 已确认：归档后以新 delta 将成功率分子收敛为仅 `COMPLETED`；本 change 不改观测代码）。
- 不新增 `AgentRole`，不把 QA 改名为 Auditor。
- 不新增 `RdTaskStatus`（`WAITING_USER_INPUT` 已确认为阶段 3）。

## Decisions

### D1. Auditor 是 Host 确定性审计器，不是新角色

**决定**：新增 `com.wish.rd.engine.requirement.audit.DeterministicAuditor`（纯函数式领域服务），输入当前 `AuditedTaskState` head 与本 command 的宿主可观测证据源，输出 `AuditRun` 与下一版状态。证据源仅限：`HostVerificationRun` 及其 `rd_host_verification_artifacts`；经 `QaEvidenceBundleValidator` 通过并落入 `rd_qa_evidence_objects` 的 QA 证据；`HostOwnedAssertionGate` 结果；`RepairWorkspaceRepositoryPort.repositoryState` 指纹回执；`RequirementPublicationLedger` 的 `PR_CONFIRMED/COMMITTED`。

**理由**：指南原则 4「先用确定性证据，再用模型判断」；QA 容器已经是「在独立上下文跑真实验证」的执行体，缺的不是另一个模型角色，而是把它的证据与宿主证据合并成不可由 Executor 改写的状态。`AiDeliveryReviewEngine` 保持现状，阶段 4 再改造为契约反查。

**放弃的方案**：把 QA 结果 JSON 直接当审计报告（仍是 Executor 自报）；新增 `AUDITOR_AGENT` 角色（增加成本，且违背指南 12.1「业务角色与控制角色正交」）。

### D2. 状态模型：records × status × evidence refs，revision 追加 + head CAS

```text
AuditedTaskState {
  taskId, stateVersion, stateHash,
  contractRef { acceptanceCriteriaHash, taskVersionAtFreeze, fencingTokenAtFreeze },
  records[]: AuditedRecord {
    id,                 // AC-001.. | GATE-BUILD | GATE-STATIC | GATE-QA-EVIDENCE | GATE-WORKSPACE-INTEGRITY | ART-PR | CLAIM-<stageRunId>-<n>
    kind,               // REQUIREMENT | GATE | ARTIFACT | FACT
    blocking,           // AC-* 与 GATE-* 为 true
    text | locator | statement,
    status,             // PENDING | COMPLETED | BLOCKED | UNTRUSTED
    evidenceRefs[]: { auditRunId, sourceKind, uri, sha256 },
    sourceStageRunId,   // 仅 FACT/claim
    blockedReason
  },
  lastAuditRunId
}
AuditRun {
  auditRunId, taskId, subjectStageRunId, subjectRole, commandId,
  completion: COMPLETE | INCOMPLETE | BLOCKED,
  integrity:  CLEAN | SUSPECT | VIOLATION,
  contractAudit: ALIGNED | UNKNOWN,          // NEEDS_REVISION / INVALID 预留给阶段 4
  verified[], missing[], untrusted[], blockers[],
  sourceRefs[], createdAtEpochMillis
}
```

**持久化**（`bootstrap/src/main/resources/sql/postgres/p20_task_audited_state.sql`）：

- `rd_task_audited_state_heads(task_id PK, state_version, state_hash, last_audit_run_id, updated_at)`；
- `rd_task_audited_state_revisions(task_id, state_version, state_hash, state_json jsonb, audit_run_id, command_id, created_at, UNIQUE(task_id, state_version))`；
- `rd_task_audit_runs(audit_run_id PK, task_id, subject_stage_run_id, subject_role, command_id, completion, integrity, contract_audit, report_json jsonb, report_hash, created_at, UNIQUE(command_id))`；
- `rd_task_completion_bindings(task_id PK, audit_run_id, state_version, state_hash, bound_at)`；
- `task_id` 对 `rd_tasks(id)` `ON DELETE CASCADE`，与任务 retention 一致。

**边界**：`state_json` 记录数 ≤ 256、每条证据引用 ≤ 16、canonical JSON ≤ 256 KiB，超限失败关闭。`stateHash` 为 canonical JSON 的 SHA-256（沿用 `agent-state-v2-codec` 的 canonical 规则实现 Java 版 `AuditedTaskStateCodec`）。

**理由**：与 `rd_agent_stage_state_latest`、项目记忆 revision/head 同一模式；JSONB 快照便于管理台一次读取，UNIQUE(version) + head CAS 提供幂等与并发保护（RULE.md 3.5.3、3.4）。

### D3. 写回搭乘 finalize 两阶段：plan schema v3

`RequirementStageExecutionPlan` 增加可选字段 `AuditedStateMutation auditedStateMutation`，`CURRENT_SCHEMA_VERSION=3`；v1/v2 解码为 `null`。`RequirementStageExecutionPlanCodec` 把该字段纳入 canonical 编码与 digest。`PostgresRequirementStageFinalizationAdapter.finalize` 顺序：

1. 校验 marker 为 `OUTCOME_RECORDED`（不变）；
2. **新增** `AuditedStateFinalizationWriter.apply(mutation)`：插入 `rd_task_audit_runs`（`UNIQUE(command_id)` 吸收重放）、插入 revision（`UNIQUE(task_id,state_version)`）、CAS head（`WHERE task_id=? AND state_version=?`）；同 version 同 hash 视为已应用，同 version 异 hash 抛 `IllegalStateException` 并保持 marker；
3. 任务 CAS → command 完成 CAS → continuation 插入 → marker FINALIZED（不变）。

`InMemoryRequirementStageFinalizationPort` 同步实现，供 engine 单测。

**初始化入口**：状态第 1 版不经 `planStage`，而是在 `RequirementPolicyTransactionPort.consumePolicyApply` 判定 `ALLOWED`（以及 `APPROVAL_RESUME` 恢复为 `EXECUTING`）的同一事务内由 `AuditedTaskStateInitializer` 写入（`PostgresRequirementPolicyTransactionAdapter` 与 `InMemoryRequirementPolicyTransactionAdapter` 同步实现）。`AuditedStateFinalizationWriter.apply` 在 head 缺失时 initialize-if-absent 作为兜底，`contractRef` 不一致失败关闭。

**理由**：RULE.md 3.5.x 要求快照与事件同事务、恢复只读冻结 plan；`PiRemediationFinalizationWriter` 已证明该扩展点可行。

### D4. `HOST_VERIFY` 作为独立 durable command

- `RequirementDeliveryEngine#roleContinuation(CODING_AGENT)` 返回 `new ContinuationSpec("REQUIREMENT_DELIVERY", "HOST_VERIFY")`；
- `planStage` 新增 `case "HOST_VERIFY" -> planHostVerifyStage(task, command)`：读取最新 `SUCCEEDED` 的 Coding stage run（沿用 `RequirementAgentStageOrchestrator` 现有的查找逻辑抽成 `LatestSucceededStageLocator`），调用 `HostVerificationPort.verify(task, codingStage, boundedRolePlan(CODING_AGENT), usedHostVerifyFixRounds)`；
- 结果映射：`SUCCEEDED/SKIPPED` → `DeterministicAuditor` 产出 `AuditRun` + 状态（GATE-BUILD/STATIC COMPLETED），continuation `ROLE_EXECUTION:QA_AGENT`；`FAILED` 且 `failureCategory=PRODUCT_DEFECT` 且预算允许 → `CommandDisposition.SUCCEEDED` + 冻结 remediation intent（见 D5）+ terminal continuation；其余失败 → `FAILED_NEEDS_HUMAN`，provenance `failure_phase=HOST_VERIFY`、`failed_verification_run_id`（`p15` 已加列）。
- `RequirementDeliveryDispatchService#nextStageFor`、`continuationCommand`、`RequirementStageCommandFactory` 接受 stage `HOST_VERIFY`（role `REQUIREMENT_DELIVERY`，资源类 Docker/CPU，deadline 与 command 默认一致）。
- `TaskRetryPointResolver`：`HOST_VERIFY` 失败 → 从 `CODING_AGENT` 恢复（`TaskFailurePhase.HOST_VERIFY` 已存在）。
- legacy `executeAgentStages(production())` 内的 orchestrator 门保留不动，但 legacy 路径也必须调用同一 `DeterministicAuditor` 写 `AuditRun`（见 D9）。

**与 `host-verification-pipeline` 的关系**：本决定覆盖其 D2 在 durable 路径上的空白，不改其 D1/D3/D4/D6/D7。前置动作：该 change 的 owner 先把 `tasks.md` 与现有代码对齐并归档，或在其 `design.md` 追加「durable 路径由 `mea-audit-only-writeback` 的 `HOST_VERIFY` command 承接」。

### D5. `HOST_VERIFY_FIX` 复用 remediation 账本

`AgentRemediationKind` 新增 `HOST_VERIFY_FIX(2)`；`p20` 放宽 `rd_agent_remediation_rounds` 的 kind 检查约束以接受该值（`remediation_no BETWEEN 1 AND 2` 不变）。intent 结构复用 `PiQaRemediationIntent` 的来源身份字段（taskId/version/fencing/sourceStageRunId），新增 `kind` 与 `hostVerificationRunId`，冻结附件为 `attachments/host-verify-remediation/request.json`（bounded、sanitized、hash-bound，由 `HostVerifyRemediationPackageBuilder` 生成，格式参照 `QaRemediationPackageBuilder`）。`PiRemediationFinalizationWriter` 按 kind 只创建 Coding attempt；Coding 成功后 continuation 再次为 `HOST_VERIFY`。

**理由**：RULE.md 要求补救轮次、目标 stage、首 command 与 request hash 在 `recordOutcome/finalize` 边界内持久化；不另造第二套账本。

### D6. QA 只读：tracked-tree 指纹，不改挂载模式

- `DockerPiAgentExecutor` 对 `QA_AGENT`（集合 `SUBJECT_READ_ONLY_ROLES`）在 npm 预安装容器之后、agent 容器之前调用 `repositoryState`，容器退出后再次调用；两次结果（`headSha`、`trackedTreeSha256`、`trackedFileCount`）写入 `dockerMetadataJson`，键由 `QaExecutionMetadataKeys` 新增：`WORKSPACE_FINGERPRINT_BEFORE_JSON`、`WORKSPACE_FINGERPRINT_AFTER_JSON`、`WORKSPACE_INTEGRITY`（`CLEAN|VIOLATION`）。
- 不把 `/work/repo` 改为 `:ro`：QA 的 `npm run build && npm run start` 需要写 `.next/` 等被忽略的产物；这是指南 8.3 所述「可隔离、可清理的检查副作用」。`bash` 保留（QA 必须运行命令）。
- `DeterministicAuditor` 读取该键：`VIOLATION` 或 `HEAD` 变化 → `integrity=VIOLATION`，`GATE-WORKSPACE-INTEGRITY=BLOCKED`，本轮 QA 证据不晋升，任务 `FAILED_NEEDS_HUMAN`；键缺失 → `SUSPECT`，同样不晋升。
- 自动重审计留给阶段 3 的 Manager 决策；本 change 只允许操作员经既有 checkpoint retry 从 QA 重跑。

### D7. 验收 ID 闭环与三处合同锁步

- 冻结集合 = `AC-%03d` 按 `acceptanceCriteriaJson` 顺序，由 `AuditedTaskStateInitializer` 与 `PiAgentContextStateManager` 共用 `AcceptanceCriteriaIds.of(list)`。
- 合同变更：PI-v2 QA `acceptanceResults[]` 中 `scope=CURRENT` 必带 `criteriaId ∈ 冻结集合`。同步修改三处：`RequirementAgentStageOrchestrator#roleOutputContract*`（QA 段）与 QA skill 提示；`bootstrap/src/main/resources/executor/pi/src/result-tool.mjs#validateQaReport`；`exec/.../QaEvidenceBundleValidator`（Host 权威）。冻结集合通过既有 `qa-profile.json`/`/work/input` 只读附件下发给容器。
- 重建 `Dockerfile` 与 `Dockerfile.qa`。
- legacy（无 `PI_QA_REMEDIATION_V2` capability）QA 不强制 `criteriaId`：其 CURRENT 结果按 `criteria` 文本与冻结验收标准做精确规范化匹配；匹配不上则该 `AC-*` 保持 `PENDING`，从而 fail-closed。

### D8. 完成绑定用独立表，不改 `rd_tasks`

`rd_task_completion_bindings` 在 `COMPLETION` 的 finalize 事务写入。`planCompletionStage` 在 plan 阶段重新读 head 并调用 `AuditedCompletionGate`，不满足则产出 `FAILED_NEEDS_HUMAN` mutation 而不是 `COMPLETED`。`GET /admin/rd-tasks/{taskId}` 不返回 binding（沿用 RULE.md 对详情接口的瘦身约束），binding 由 `GET .../audited-state` 返回。

### D9. `DETERMINISTIC_REVIEW` 双门，legacy 路径同门

`planDeterministicReviewStage` 与 `executeDeterministicReviewStage` 均改为：先 `RequirementDeliveryReviewer.review`（形状），再 `AuditedCompletionGate.supportsCompletion(head)`；任一失败 → `REJECTED`，reason 拼接缺口记录 ID 列表（有界，≤ 32 个 ID）。`planPublicationStage` 在推分支之前再次读 head fail-closed。legacy `executeAgentStages` 在 orchestrator 内的 host verify 与 QA 之后同样调用 `DeterministicAuditor` 并经 `AuditedTaskStateStore` 写回（legacy 路径不经 finalize，直接走 store 的 CAS 方法），保证 `RequirementDeliveryEngineTest` 全套在同一门下通过。

### D10. 源码守卫

新增 `RequirementCompletionWriterPolicyTest`（bootstrap，模式同 `TransactionalProxyPolicyTest`）：扫描 `engine`/`bootstrap` 源码，`RdTaskStatus.COMPLETED` 作为需求任务 `toStatus` 只允许出现在 `RequirementDeliveryEngine#planCompletionStage`、`#executeCompletionStage` 与 `RdTaskTransitionPolicy`；`RepairTaskMergeSyncEngine` 的 `COMPLETED → MERGED` 属于读取源状态，不受限。

### D11. 模块边界

- `engine`：`requirement.audit` 包内模型、`DeterministicAuditor`、`AuditedCompletionGate`、`AuditedTaskStateStore` 端口、`AuditedTaskStateCodec`、`InMemoryAuditedTaskStateStore`；`planHostVerifyStage`；plan schema v3；`AgentRemediationKind.HOST_VERIFY_FIX`。
- `exec`：`DockerPiAgentExecutor` 指纹回执；`QaExecutionMetadataKeys` 新键；`QaEvidenceBundleValidator` 的 `criteriaId` 规则。
- `bootstrap`：`PostgresAuditedTaskStateStore`、`AuditedStateFinalizationWriter`、`p20` SQL、`RdTaskAuditedStateController`、dispatcher/command factory 的 `HOST_VERIFY` 支持、`RequirementCompletionWriterPolicyTest`、前端代理合同测试夹具。
- `rag`：不改（`RdTaskStatus`、`RdTaskTransitionPolicy` 图不变）。
- Pi 资源：`result-tool.mjs`、QA 提示合同、两个 Dockerfile。
- 前端：`RdTaskDetailPage` 审计视图新增「已审计状态」面板与摘要条徽标；`rdTaskService` 两个只读请求；`frontend/test/auditedTaskStatePresentation.test.ts` 与 proxy contract test。

### D12. 上线模式与回滚

配置 `rd.requirement-delivery.audited-writeback.gate-mode=SHADOW|ENFORCE`（`RD_REQUIREMENT_DELIVERY_AUDITED_GATE_MODE`）。**2026-09-03 已确认：代码与 `application.yaml` 默认 `ENFORCE`。** 状态初始化、写回、`HOST_VERIFY` command、指纹回执与管理 API 始终开启；`SHADOW` 时 `AuditedCompletionGate` 只记录「本应拒绝」到 `AuditRun.blockers[]` 与告警日志，不改变 `REJECTED/COMPLETED` 判定。云端首次发布在 `deploy/cloud-server/application-local.server.yaml` 显式设 `SHADOW`，跑不少于 5 个真实任务（项目 `codex-run-test-waimai`，其中至少 1 个故意注入构建失败），核对无误后去掉覆盖以回到默认 `ENFORCE`。回滚 = 切回 `SHADOW`；`HOST_VERIFY` command 不可关闭（它只增加证据，不改变旧行为下的 continuation 结果，除非验证失败）。

## Risks / Trade-offs

- **每条 command 多一次 revision 写入与 JSONB 读取。** 有界（≤ 256 KiB），且读 head 走 PK；管理台一次读取 head 即可，符合 RULE.md 对 `execution-overview` 的单次列表约束（新面板独立请求，不叠加到 overview）。
- **混合版本部署。** 旧 dispatcher 不识别 `HOST_VERIFY` stage。要求滚动发布前 drain 正在 `EXECUTING` 的任务，或全部实例同时升级；plan v3 对旧解码器不可读，因此 `OUTCOME_RECORDED` 的 v3 marker 必须由新实例完成——发布说明写明。
- **`criteriaId` 合同变更会让旧 QA 镜像的 PASSED 结果不再覆盖 `AC-*`。** 表现为 `INCOMPLETE` 而非误判 `COMPLETE`（fail-closed），但会阻塞交付，必须与镜像重建一起发布。
- **指纹计算成本。** `repositoryState` 为 tracked-tree 内容哈希，大仓库可能耗时数秒；只对 QA 角色执行两次，纳入 QA 墙钟预算；超时记为 `SUSPECT`。
- **legacy 路径双写。** legacy 路径不经 finalize 直接写 store，存在与 durable 路径行为漂移的风险；由 `RequirementDeliveryEngineTest` 与 `RequirementDeliveryStageExecutionTest` 对同一场景的双路径断言约束。
- **误拒。** `SHADOW` 期间统计「本应拒绝」比例并逐条人工核对，超过 20% 的任务被 gate 拦截而人工判定为真通过时，先修审计器再切 `ENFORCE`。

## Migration Plan

1. 前置：`host-verification-pipeline` owner 对齐 `tasks.md` 与现有代码并归档（或在其 design 记录由本 change 承接 durable 路径）。
2. 合并 `p20_task_audited_state.sql` 与 README 行；`PostgresTaskAuditedStateRealSmokeTest` 在真实 PostgreSQL 通过。
3. 后端发布（`gate-mode=SHADOW`），同时重建两个 Pi 镜像（`criteriaId` 合同）。
4. 云端按 `deploy/cloud-server/run-codex-memory-live-task.py` 的模式、项目 `codex-run-test-waimai` 提交 ≥ 5 个真实任务，其中 1 个在候选补丁中故意破坏构建；核对 `HOST_VERIFY` command、`AuditRun`、状态 revision、PR 描述审计清单与「本应拒绝」日志。
5. 去掉服务器 `SHADOW` 覆盖以回到默认 `ENFORCE`；重复注入构建失败任务，确认任务停在 `EXECUTING`/`FAILED_NEEDS_HUMAN` 而非 `COMPLETED`。
6. 更新 `RULE.md`（3.5.3 追加完成绑定与写回条款、3.5.x 追加 QA 指纹条款）、`AGENTS.md`（QA `criteriaId` 合同三处锁步与镜像重建提醒）、`openspec/specs` 归档。

## Resolved Decisions（2026-09-03）

1. **`gate-mode` 默认 `ENFORCE`**。云端首次发布用 `application-local.server.yaml` 覆盖为 `SHADOW`，核对后再回到默认。
2. **`HOST_VERIFY` 为独立 durable command**，不嵌入 Coding command。
3. **QA 只读用 tracked-tree 指纹**，保留 `repo/` rw 与 `bash`；违规转人工，阶段 1 不自动重审计。
4. **`ASK` 新增 `RdTaskStatus.WAITING_USER_INPUT`**（阶段 3，本 change 不改枚举）。
5. **观测 SUCCESS 仅 `COMPLETED`**。`COMMITTED` 为在途；`MERGED` 仅在历史含 `COMPLETED` 时计成功。本 change 不改观测代码；`upgrade-delivery-observability` 归档后新 delta 落地。
6. **阶段 0/1 真机项目沿用 `codex-run-test-waimai`**。

## Open Questions

1. `HostOwnedAssertionGate` 结果是否在本 change 就映射到 `AC-*`（当任务带 `hostAssertionBundle` 时），还是只作为 `GATE-HOST-ASSERTION` 独立门？本设计取独立门（可选，只在 bundle 存在时初始化），避免与 QA 覆盖语义耦合。
2. legacy QA（无 v2 capability）按 `criteria` 文本匹配的规范化规则（去空白、全半角、大小写）需在实现时用真实历史结果验证误匹配率。
3. `DockerPiAgentExecutorTest` 中 `@Disabled` 的 QA provider-attempt 隔离用例（独立 checkout / `:ro` 挂载）留到阶段 2。阶段 1 只接线 tracked-tree 指纹，不改 QA `repo/` 挂载模式。

## 验证命令

```bash
# engine 聚焦
./mvnw -pl engine -am -Dtest='AuditedTaskState*Test,DeterministicAuditorTest,AuditedCompletionGateTest,RequirementStageExecutionPlanCodecTest,RequirementDeliveryStageExecutionTest,RequirementDeliveryEngineTest,RequirementAgentStageOrchestratorTest,TaskRetryPointResolverTest,AgentRemediationCoordinatorTest' -Dsurefire.failIfNoSpecifiedTests=false test
# exec 聚焦
./mvnw -pl exec -am -Dtest='DockerPiAgentExecutorTest,QaEvidenceBundleValidatorTest,AgentRoleResultValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false test
# bootstrap 聚焦
./mvnw -pl bootstrap -am -Dtest='PostgresAuditedTaskStateStoreTest,AuditedStateFinalizationWriterTest,PostgresRequirementStageFinalizationAdapterTest,RequirementDeliveryDispatchServiceTest,RequirementStageCommandFactoryTest,RdTaskAuditedStateControllerTest,RequirementCompletionWriterPolicyTest,TransactionalProxyPolicyTest,PiAgentRemediationSqlPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false test
# 真实 PostgreSQL
./mvnw -pl bootstrap -am -Dtest=PostgresRdTaskStateAtomicRealSmokeTest,PostgresTaskAuditedStateRealSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Drd.integration.stage-finalization.enabled=true -Dtest=PostgresRequirementStageFinalizationRealSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
# Pi 资源
cd bootstrap/src/main/resources/executor/pi && npm test
# 前端
cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build
# OpenSpec
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
```

真实 HTTP 请求链（RULE.md 6.2）：提交一个候选补丁故意破坏构建的需求任务 → 轮询 `GET /admin/rd-tasks/{taskId}` 与 `GET /admin/rd-tasks/{taskId}/audited-state`，断言 `HOST_VERIFY` command 存在、`GATE-BUILD=PENDING`、任务未到 `COMPLETED`；再提交一个正常任务，断言 `COMPLETED` 后 `rd_task_completion_bindings` 有行且 PR body 含审计清单。
