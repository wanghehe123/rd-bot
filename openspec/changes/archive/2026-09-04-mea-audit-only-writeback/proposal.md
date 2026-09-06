## Why

按 DreamX LongHorizon-Harness 的 MEA（Manage–Execute–Audit）方法论对 RD-Bot 做九步代码核查后，需求交付链路的完成语义存在一个结构性缺口：任务写为 `COMPLETED` 的权力虽已收口到 Host finalizer，但完成依据仍以 Pi 角色容器自报的结果 JSON 为准真相——`RequirementDeliveryReviewer` 只做形状检查，`COMPLETED` mutation 不携带任何证据引用，宿主 BUILD/STATIC 验证在生产逐条 command 路径被 `boundedRolePlan` 关闭，QA 的只读性只靠 prompt 与部分工具策略（`repo/` rw + `bash`，`repositoryState` 指纹守卫从未接线）。一个 Coding 自报 `testStatus=PASSED`、QA 自报 `status=PASSED` 并附形状合法证据的任务，可以在构建实际失败的情况下走到 PR 发布与 `COMPLETED`。

指南的首要改造原则是「先修正完成权，再优化规划能力」。本 change 实现路线中的阶段 1：Audit-only writeback 最小闭环——Executor 输出与可信任务状态分离；每个完成状态引用 Auditor 证据；Auditor 不通过不得进入最终交付；未核验内容只能是 pending/untrusted；最终输出只依据已审计状态生成。退出条件是可构造「Executor 虚报成功」测试且系统必须拒绝完成。

## What Changes

- 新增 Host 拥有的显式已审计状态 `AuditedTaskState`（requirement/gate/artifact/fact 记录 × `PENDING/COMPLETED/BLOCKED/UNTRUSTED` × 证据引用）与审计轮次 `AuditRun`（completion/integrity/contractAudit 三维结论），PostgreSQL revision 追加 + head CAS 持久化（`p20_task_audited_state.sql`）。
- 状态在任务首次进入 `EXECUTING` 的事务内初始化（`RequirementPolicyTransactionPort.consumePolicyApply` 判定 `ALLOWED`，或 `APPROVAL_RESUME` 恢复执行），写回时 head 缺失则 initialize-if-absent；验收标准 ID `AC-%03d` 与 `PiAgentContextStateManager` 的 Host 验收 TODO 同源。
- 角色结果 JSON 的自报（`testStatus`、QA `status`、`environmentNotes`、`facts[]`）只能以 `UNTRUSTED` 进入状态；只有 Host 确定性审计器 `DeterministicAuditor` 依据宿主证据源（`HostVerificationRun`、`rd_qa_evidence_objects`、Host 断言、工作区指纹、发布账本）产生的 `AuditRun` 能把记录晋升为 `COMPLETED`，且每条 `COMPLETED` 必须携带可解析证据引用。
- 写回作为 `RequirementStageExecutionPlan` schema v3 的 `auditedStateMutation` 字段，随 `OUTCOME_RECORDED → FINALIZED` 在同一 PostgreSQL 事务提交，恢复重放幂等。
- `ROLE_EXECUTION:CODING_AGENT` 成功后的 continuation 改为新的 durable command `HOST_VERIFY`，使宿主 BUILD/STATIC 验证在生产逐条 command 路径生效；`PRODUCT_DEFECT` 失败在 finalize 边界内冻结 `HOST_VERIFY_FIX` remediation intent（最多 2 轮、Coding `attemptNo <= 3`）只创建 Coding attempt；环境/基础设施/歧义失败转人工。
- QA 只读性技术强制：`DockerPiAgentExecutor` 对 `QA_AGENT` 在容器前后计算 tracked-tree 指纹与 `HEAD`，写入 `dockerMetadataJson`；不一致 → `integrity=VIOLATION`，本轮证据不晋升，任务转人工；缺失 → `SUSPECT`。
- PI-v2 QA 合同：`acceptanceResults[]` 中 `scope=CURRENT` 必带冻结集合内的 `criteriaId`；提示词、`result-tool.mjs`、`QaEvidenceBundleValidator` 三处锁步并重建两个 Pi 镜像。
- `DETERMINISTIC_REVIEW` 在既有形状检查之外读取状态 head 并以 `AuditedCompletionGate` 为准；`PUBLICATION`/`COMPLETION` 再次读 head fail-closed；`COMPLETED` 的 finalize 事务同时写 `rd_task_completion_bindings`；源码守卫测试禁止其他完成写入者。
- PR 描述与 REPORTING 首段为由状态生成的「已审计验收清单」，Agent `prBody` 降为未核验叙述；新增只读 API `GET /admin/rd-tasks/{taskId}/audited-state`、`GET /admin/rd-tasks/{taskId}/audit-runs` 与前端审计视图面板。
- 上线开关 `rd.requirement-delivery.audited-writeback.gate-mode=SHADOW|ENFORCE`（**默认 `ENFORCE`，2026-09-03 已确认**；云端首次发布用 `SHADOW` 覆盖跑真实任务后再回到默认）。

## Capabilities

### New Capabilities

- `requirement/audit-only-writeback`：需求交付任务的已审计状态、审计轮次、`HOST_VERIFY` durable command、QA 指纹完整性、验收 ID 闭环、审计门禁完成与完成绑定合同。

### Modified Capabilities

- 无。`requirement/delivery-platform` 主 spec 的既有 requirement（写入面、Pi 唯一路径、下线功能）不变；本 change 不改 `RdTaskStatus` 与需求任务状态图，只改进入 `REJECTED`/`COMPLETED` 的判定依据与 continuation 序列。

## Impact

- **engine**：新增 `com.wish.rd.engine.requirement.audit` 包；`RequirementDeliveryEngine`（`roleContinuation`、`planHostVerifyStage`、`planDeterministicReviewStage`、`planPublicationStage`、`planCompletionStage`、legacy `execute*Stage`）；`RequirementStageExecutionPlan`/`Codec` schema v3；`AgentRemediationKind.HOST_VERIFY_FIX`；`RequirementStageFinalizationPort` 与 InMemory 实现；`RequirementPolicyTransactionPort` 的 InMemory 适配器（状态初始化）；`TaskRetryPointResolver`。
- **exec**：`DockerPiAgentExecutor`（QA 指纹回执）、`QaExecutionMetadataKeys`、`QaEvidenceBundleValidator`（`criteriaId`）。
- **bootstrap**：`p20_task_audited_state.sql` 与 SQL README；`PostgresAuditedTaskStateStore`、`AuditedStateFinalizationWriter`、`PostgresRequirementStageFinalizationAdapter`、`PostgresRequirementPolicyTransactionAdapter`（`consumePolicyApply` 内初始化状态）；`RequirementDeliveryDispatchService`/`RequirementStageCommandFactory`（`HOST_VERIFY` stage）；`RdTaskAuditedStateController`；`RequirementCompletionWriterPolicyTest`；`PostgresTaskAuditedStateRealSmokeTest`。
- **Pi 资源**：`result-tool.mjs`、QA 输出合同提示、`Dockerfile` 与 `Dockerfile.qa` 重建。
- **frontend**：`RdTaskDetailPage` 审计视图面板、`rdTaskService`、Vite proxy contract test、presentation test。
- **文档与规则**：`RULE.md` 3.5.3 追加完成绑定/写回/QA 指纹条款；`AGENTS.md` 追加 QA `criteriaId` 合同三处锁步提醒；归档时同步 `openspec/specs/requirement/audit-only-writeback/spec.md`。
- **在途 change 协调**：`host-verification-pipeline`（durable 路径由本 change 的 `HOST_VERIFY` command 承接，需先对齐 tasks 并归档或在其 design 记录）；`upgrade-delivery-observability`（**已确认** SUCCESS 仅 `COMPLETED`，本 change 不改观测代码，归档后新 delta 落地）；`improve-requirement-pr-description`（PR 描述首段改为审计清单，需在该 change 内对齐模板）；`pi-agent-state-and-qa-remediation-v2`（`AC-%03d` 与 remediation 账本复用，无冲突）。
- **运行时行为变化**：Coding 成功后多一条 `HOST_VERIFY` command（增加一次干净工作区构建的墙钟）；QA 前后两次指纹计算；构建失败的任务不再进入 QA 与 PR。

## 证据与来源（按 `openspec/config.yaml` proposal 规则记录）

- 方法论来源：`/Volumes/WishDisk/llms/paper/longhorizon-repro/RD-Bot-DreamX-MEA改造指南.md`（§3 信任模型、§5 显式状态、§8 Auditor、§13 阶段 1、§15 常见错误、§18 验收清单）。
- 总体路线与九步核查：`docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md`。
- 实现 Agent 交接：`docs/superpowers/specs/2026-09-03-rd-bot-mea-agent-handoff.md`。
- 历史资料分级：`docs/openspec/historical-spec-provenance-audit.md`；本 change 只把 current-code/current-test 已验证行为写入 Context，计划性内容留在 design/tasks。
- 当前代码锚点（2026-09-03 读码）：`RequirementDeliveryEngine#planCompletionStage`、`#planDeterministicReviewStage`、`#boundedRolePlan`、`#roleContinuation`；`RequirementDeliveryReviewer#review`（76–132）；`DockerPiAgentExecutor.READ_ONLY_REPO_ROLES`（100–102）与挂载（约 1262）；`RepairWorkspaceRepositoryPort#repositoryState`（无生产调用方）；`RequirementStageExecutionPlan`（schema v2）；`RequirementStageFinalizationPort`；`PiAgentContextStateManager#prepareInitialState`（`AC-%03d`）；`QaEvidenceBundleValidator`（`criteriaId` 仅 FAILED 闭环）；`DeliveryObservabilityQueryService`（SUCCESS 集合，48）；`p8_pi_agent_runtime.sql`（`default-qa` 策略 196–226）。
- 现有测试锚点：`RequirementDeliveryReviewerTest`、`RequirementDeliveryStageExecutionTest`、`RequirementDeliveryDispatchServiceTest.recoveryFinalizesOutcomeRecordedPlanWithoutReplanningOrExecutingTheStage`、`PostgresRequirementStageFinalizationAdapterTest`、`DockerPiAgentExecutorTest.shouldMountRepoReadOnlyForReviewAndArchitectRoles`、`ProcessGitRepairWorkspaceRepositoryTest`（`repositoryState`）、`QaEvidenceBundleValidatorTest`、`PiAgentContextStateManagerTest`、`RequirementAgentStageOrchestratorTest.verify_failure_does_not_invoke_qa_executor`。
- 本轮实际运行的命令：`rg`/`sed` 只读检索（`READ_ONLY_REPO_ROLES`、`repositoryState(`、`paused`、`SUCCESS = Set.of`、`case "` 等），`openspec --version`（1.8.0），`ls openspec/ openspec/changes/ bootstrap/src/main/resources/sql/postgres/`；未运行 Maven/npm 测试，本 change 为规划产物，不含代码改动。
