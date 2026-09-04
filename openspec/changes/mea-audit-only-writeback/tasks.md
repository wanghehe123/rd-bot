## 0. 前置对齐

- [x] 0.1 与 `host-verification-pipeline` owner 对齐：把该 change 的 `tasks.md` 与现有 `HostVerificationPort`/`p15`/orchestrator 门代码状态对齐并归档，或在其 `design.md` 追加「durable 路径由 `mea-audit-only-writeback` 的 `HOST_VERIFY` command 承接」。验证：`OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`（已在 `host-verification-pipeline/design.md` D2 追加 durable 路径归属，并在其 `tasks.md` 记录代码已落地、不要把 orchestrator 门当生产路径归档；`openspec validate --all --strict` 13 passed）
- [x] 0.2 重新阅读 `RULE.md` 3.5.3 / 3.5.x Pi 段与 `AGENTS.md`，追踪 `RequirementDeliveryDispatchService.submit → RequirementStageExecutor.plan → RequirementDeliveryEngine.planStage → RequirementStageFinalizationPort.finalize` 调用链并在 PR 描述中记录起止符号。起止符号：`RequirementDeliveryDispatchService.submit` → `stageExecutor.plan`（`EngineRequirementStageExecutor.plan`）→ `RequirementDeliveryEngine.planStage` → `RequirementStageFinalizationPort.recordOutcome` → `RequirementDeliveryDispatchService.finalizeStageOutcome` → `RequirementStageFinalizationPort.finalize`。策略放行不经该链：`runPolicyApplyCommand` → `RequirementPolicyTransactionPort.consumePolicyApply`。
- [x] 0.3 在 `application.yaml` 增加 `rd.requirement-delivery.audited-writeback.gate-mode`（默认 `ENFORCE`）与 `deploy/cloud-server/application-local.example.yaml` 的 `SHADOW` 示例；先写 `RequirementDeliveryAuditedWritebackPropertiesTest` 断言默认值与非法值失败关闭

## 1. 领域模型（engine `com.wish.rd.engine.requirement.audit`）

- [x] 1.1 先写 `AuditedTaskStateTest`：记录 ID 唯一、`COMPLETED` 必有 ≥1 `EvidenceRef`、记录数 ≤ 256、每条证据 ≤ 16、`BLOCKED` 必有 `blockedReason`；再实现 `AuditedTaskState`、`AuditedRecord`、`AuditedRecordKind`、`AuditedRecordStatus`、`EvidenceRef`、`EvidenceSourceKind` record/enum
- [x] 1.2 先写 `AuditedTaskStateCodecTest`：canonical JSON 稳定（key 排序、无多余空白）、`stateHash` 为 SHA-256、≤ 256 KiB 超限失败关闭、往返解码相等；再实现 `AuditedTaskStateCodec`
- [x] 1.3 先写 `AuditRunTest`：三维枚举取值、`UNIQUE(commandId)` 语义、`verified/missing/untrusted/blockers` 有界；再实现 `AuditRun`、`AuditCompletion`、`AuditIntegrity`、`ContractAuditVerdict`
- [x] 1.4 先写 `AcceptanceCriteriaIdsTest`：`AC-%03d` 顺序与 `PiAgentContextStateManagerTest` 既有断言一致；再抽出 `AcceptanceCriteriaIds.of(List<String>)` 并让 `PiAgentContextStateManager` 复用
- [x] 1.5 先写 `AuditedTaskStateInitializerTest`：按冻结 `acceptanceCriteriaJson` 生成 `AC-*` + `GATE-BUILD/STATIC/QA-EVIDENCE/WORKSPACE-INTEGRITY` + `ART-PR`（有 `hostAssertionBundle` 时追加 `GATE-HOST-ASSERTION`），全部 `PENDING`，`contractRef` 记录验收哈希与任务 version/fencing；再实现
- [x] 1.6 先写 `AuditedTaskStateStoreContractTest`（抽象合同，InMemory 与 Postgres 共用）：`head`、`appendRevision` CAS（同 version 同 hash 幂等、同 version 异 hash 拒绝、expected 不匹配拒绝）、`listAuditRuns`、`bindCompletion`；再实现 `AuditedTaskStateStore` 端口与 `InMemoryAuditedTaskStateStore`

## 2. 确定性审计器与完成门

- [x] 2.1 先写 `DeterministicAuditorTest`（HOST_VERIFY 分支）：`SUCCEEDED` → `GATE-BUILD/STATIC=COMPLETED` 且证据引用指向 `rd_host_verification_artifacts` URI；`SKIPPED`(docs-only) → 两门 `COMPLETED`（证据为 docs-only 判定）；`FAILED` → 保持 `PENDING`，`completion=INCOMPLETE`；旧 `COMPLETED` 遇新失败 → 降级 `PENDING` 并写 `untrusted[]`
- [x] 2.2 先写 `DeterministicAuditorTest`（QA 分支）：按 `criteriaId` 映射 CURRENT `PASSED` 且证据全部可解析 → `AC-*=COMPLETED`；漏覆盖 → `INCOMPLETE` + `missing[]`；`QA_AGENT.status=PASSED` 无审计 → 不晋升；legacy（无 v2 capability）按规范化 `criteria` 文本匹配，匹配失败保持 `PENDING`
- [x] 2.3 先写 `DeterministicAuditorTest`（integrity 分支）：指纹一致 → `CLEAN` + `GATE-WORKSPACE-INTEGRITY=COMPLETED`；不一致或 HEAD 变化 → `VIOLATION` + `BLOCKED` + 本轮不晋升；键缺失 → `SUSPECT` 不晋升
- [x] 2.4 先写 `DeterministicAuditorTest`（claims）：角色结果 `testStatus`/`status`/`environmentNotes`/`facts[]` 记为 `UNTRUSTED` claim，ID `CLAIM-<stageRunId>-<n>`，有界 ≤ 32 条/attempt
- [x] 2.5 先写 `AuditedCompletionGateTest`：全部阻断记录 `COMPLETED` 且证据可解析、无 `BLOCKED` 阻断、最近 `AuditRun.integrity=CLEAN`、`contractAudit=ALIGNED` → 支持完成；任一不满足 → 拒绝并返回缺口 ID 列表（≤ 32）；`SHADOW` 模式只记录不拒绝；再实现 `AuditedCompletionGate`
- [x] 2.6 先写 `EvidenceRefResolverTest`：`HOST_VERIFICATION`/`QA_EVIDENCE`/`PUBLICATION_LEDGER`/`WORKSPACE_FINGERPRINT`/`HOST_ASSERTION` 五类 URI 解析到已持久化产物；悬空引用抛 `IllegalStateException`；再实现端口 `EvidenceRefResolverPort` 与 InMemory 实现

## 3. plan schema v3 与 finalize 写回

- [x] 3.1 先写 `RequirementStageExecutionPlanTest` 新用例：v3 可携带 `AuditedStateMutation`；v1/v2 不得携带；`expectedStateVersion` 必须与 mutation 一致；再实现字段、`withAuditedStateMutation`、`CURRENT_SCHEMA_VERSION=3`
- [x] 3.2 先写 `RequirementStageExecutionPlanCodecTest`：v3 canonical 编码含写回且 digest 变化；v2 JSON 解码为 `auditedStateMutation=null`；再实现
- [x] 3.3 先写 `InMemoryRequirementStageFinalizationPortTest`：finalize 先写 audit run/revision/head CAS 再写任务 CAS；同 version 同 hash 重放幂等；异 hash 拒绝且 marker 保持 `OUTCOME_RECORDED`；再实现
- [x] 3.4 先写 `AuditedStateFinalizationWriterTest`（bootstrap，mock JDBC/mapper）与 `PostgresRequirementStageFinalizationAdapterTest` 新用例：写回在任务 CAS 之前、同一 `@Transactional`；CAS 失败回滚全部；再实现 `AuditedStateFinalizationWriter` 并接入 `PostgresRequirementStageFinalizationAdapter.finalize`
- [x] 3.5 先写 `RequirementDeliveryDispatchServiceTest` 新用例：`OUTCOME_RECORDED` 含写回的 plan 恢复时只 `decodeOutcomePlan`、写回恰好一次（扩展 `recoveryFinalizesOutcomeRecordedPlanWithoutReplanningOrExecutingTheStage`）；再调整 dispatcher

## 4. 持久化（bootstrap）

- [x] 4.1 新增 `bootstrap/src/main/resources/sql/postgres/p20_task_audited_state.sql`：`rd_task_audited_state_heads`、`rd_task_audited_state_revisions(UNIQUE(task_id,state_version))`、`rd_task_audit_runs(UNIQUE(command_id))`、`rd_task_completion_bindings`，`task_id → rd_tasks(id) ON DELETE CASCADE`；放宽 `rd_agent_remediation_rounds.kind` 检查以接受 `HOST_VERIFY_FIX`；更新 `sql/postgres/README.md` 行
- [x] 4.2 先写 `PostgresAuditedTaskStateStoreTest`（mapper 级）与合同测试子类；再实现 `PostgresAuditedTaskStateStore`（MyBatis mapper 与既有 store 风格一致，`@Transactional` 类非 `final`）
- [x] 4.3 先写防退化测试 `AuditedTaskStateStoreProductionTruthTest`：生产装配下 `AuditedTaskStateStore` 不是 InMemory 实现；`rd.knowledge.store=memory` 才允许
- [x] 4.4 新增 `PostgresTaskAuditedStateRealSmokeTest`（真实 PostgreSQL，`-Drd.integration.*` 开关）：初始化 → 写回 → 并发 CAS 冲突 → 完成绑定；同时运行 `PostgresRdTaskStateAtomicRealSmokeTest`
- [x] 4.5 `PiAgentRemediationSqlPolicyTest` 追加：`HOST_VERIFY_FIX` 被 p20 约束接受，`remediation_no BETWEEN 1 AND 2` 不变

## 5. 状态初始化与 Executor claims

- [x] 5.1 先写 `InMemoryRequirementPolicyTransactionAdapterTest` 与 `PostgresRequirementPolicyTransactionAdapterTest` 新用例：`consumePolicyApply` 判定 `ALLOWED` 的同一事务写入 `state_version=1`（记录数 = 冻结验收标准数 + 固定门/产物），重放不产生第二个 v1，`contractRef` 不一致失败关闭；`APPROVAL_RESUME` 恢复为 `EXECUTING` 时 head 缺失则初始化；再在两个适配器接入 `AuditedTaskStateInitializer`（legacy `planPolicyStage` 已被 dispatcher 隔离，不接）
- [x] 5.1b 先写 `AuditedStateFinalizationWriterTest` 兜底用例：写回时 head 缺失 → initialize-if-absent 后再应用 mutation；再实现
- [x] 5.2 先写 `RequirementDeliveryStageExecutionTest` 新用例：每条 `ROLE_EXECUTION` 成功 plan 携带 `UNTRUSTED` claims 写回且不含任何 `COMPLETED` 晋升；再在 `planRoleExecutionStage` 接入 `DeterministicAuditor.recordClaims`
- [x] 5.3 `RequirementDeliveryEngineTest`（legacy 路径）新用例：同样初始化与 claims 经 `AuditedTaskStateStore` 直接写入

## 6. `HOST_VERIFY` durable command 与 `HOST_VERIFY_FIX`

- [x] 6.1 先写 `RequirementDeliveryStageExecutionTest`：`CODING_AGENT` 成功 plan 的 continuation 为 `("REQUIREMENT_DELIVERY","HOST_VERIFY")`；再改 `roleContinuation`
- [x] 6.2 先写 `RequirementDeliveryStageExecutionTest`（`planHostVerifyStage`）：`SUCCEEDED/SKIPPED` → 写回 + continuation `ROLE_EXECUTION:QA_AGENT`；`PRODUCT_DEFECT` 且预算允许 → `SUCCEEDED` + 冻结 intent + terminal；预算耗尽或环境/基础设施/歧义 → `FAILED_NEEDS_HUMAN` 且 provenance `HOST_VERIFY`/`failed_verification_run_id`；再实现 `planHostVerifyStage` 与 `LatestSucceededStageLocator`
- [x] 6.3 先写 `AgentRemediationCoordinatorTest`/`RequirementStageCommandRemediationTest` 新用例：`HOST_VERIFY_FIX(2)` 轮次上限、Coding `attemptNo <= 3`、与 `QA_PRODUCT_FIX` 互不占用；再新增枚举值与 intent `kind`
- [x] 6.4 先写 `HostVerifyRemediationPackageBuilderTest`：bounded（≤ 64 KiB）、sanitized（去密钥/URL）、hash-bound、`fromFrozen` 校验；再实现，附件路径 `attachments/host-verify-remediation/request.json`
- [x] 6.5 先写 `PiRemediationFinalizationWriterTest` 新用例：kind 为 `HOST_VERIFY_FIX` 时只创建 Coding attempt/command，不创建 QA；再实现
- [x] 6.6 先写 `RequirementDeliveryDispatchServiceTest`/`RequirementStageCommandFactoryTest`：`nextStageFor`/`continuationCommand` 接受 `HOST_VERIFY`（role `REQUIREMENT_DELIVERY`、Docker 资源 token、默认 deadline）；再实现
- [x] 6.7 先写 `TaskRetryPointResolverTest`：`HOST_VERIFY` 失败 provenance → `retryFromRole=CODING_AGENT`；`TaskRetryEngineTest` 操作员重试首个 command 为 `HOST_VERIFY`，`policyRunId` 继承 checkpoint `sourcePolicyRunId`，预绑定下游 PENDING `QA_AGENT`，不新开 Coding attempt
- [x] 6.8 `RequirementAgentStageOrchestratorTest`：确认 bounded plan 下 orchestrator 不再需要 host-verify 内循环即可被 `HOST_VERIFY` 覆盖；legacy `production()` 用例保持通过并新增「orchestrator 门之后调用 `DeterministicAuditor` 写 `AuditRun`」断言

## 7. QA 工作区指纹（exec）

- [x] 7.1 先写 `DockerPiAgentExecutorTest`：`QA_AGENT` 在 npm 预安装之后、agent 容器之前与容器退出后各调用一次 `repositoryState`，两次回执与 `WORKSPACE_INTEGRITY` 写入 `dockerMetadataJson`；非 QA 角色不调用；`repositoryState` 抛错或超时 → 键缺失（由审计器记 `SUSPECT`）；再实现（复用 `RepairWorkspaceRepositoryPort` 注入）
- [x] 7.2 `QaExecutionMetadataKeys` 新增 `WORKSPACE_FINGERPRINT_BEFORE_JSON`、`WORKSPACE_FINGERPRINT_AFTER_JSON`、`WORKSPACE_INTEGRITY`，补常量测试；确认只写 `dockerMetadataJson` 通道（RULE.md 3.5.x）
- [x] 7.3 `ProcessGitRepairWorkspaceRepositoryTest`：`repositoryState` 对 `.gitignore` 覆盖的 `.next/`、`node_modules/` 不敏感、对跟踪文件内容与 `HEAD` 敏感（若已有则引用，缺则补）
- [x] 7.4 启用并修复 `DockerPiAgentExecutorTest` 中 `@Disabled` 的 QA provider-attempt 隔离用例，或在 design Open Questions 记录为阶段 2 任务（本 change 只需指纹）

## 8. QA `criteriaId` 合同三处锁步

- [x] 8.1 先改 `bootstrap/src/main/resources/executor/pi/test/*.test.mjs`：PI-v2 QA `acceptanceResults[]` CURRENT 缺 `criteriaId` 或不在冻结集合 → `validateQaReport` 拒绝；REGRESSION 可不带；再改 `result-tool.mjs`（冻结集合从 `/work/input` 只读附件读取）
- [x] 8.2 先写 `QaEvidenceBundleValidatorTest`：同规则 Host 权威校验；legacy（无 v2 capability）不强制；再实现
- [x] 8.3 `RequirementAgentStageOrchestratorTest.buildAgentPrompt_*`：QA 输出合同段写明 CURRENT 必带 `criteriaId` 并列出冻结集合；再改 `roleOutputContract*` QA 段与 `RequirementDeliveryEngine` 同构合同
- [x] 8.4 `EngineRequirementExecutorAdapterTest`：冻结验收集合作为只读附件下发到 `/work/input`，`RoleExecutionInputManifest` 记录其 hash
- [ ] 8.5 重建 `bootstrap/src/main/resources/executor/pi/Dockerfile` 与 `Dockerfile.qa`，在 PR 描述记录镜像 digest；验证：`cd bootstrap/src/main/resources/executor/pi && npm test`

## 9. 审计门接入复核、发布、完成

- [x] 9.1 先写 `RequirementDeliveryStageExecutionTest`「Executor 虚报成功」：Coding `testStatus=PASSED`、QA `status=PASSED` 形状合法，但 `GATE-BUILD=PENDING` → `DETERMINISTIC_REVIEW` 产出 `REJECTED`，reason 含 `GATE-BUILD`，无 `AI_REVIEW` continuation；再改 `planDeterministicReviewStage`
- [x] 9.2 先写「漏覆盖验收标准」用例：`AC-002` 无 CURRENT 结果 → `REJECTED` 且 reason 含 `AC-002`
- [x] 9.3 先写 `planPublicationStage` fail-closed 用例：head 不满足门 → 不推分支、不建 PR，任务 `FAILED_NEEDS_HUMAN`；再实现
- [x] 9.4 先写 `planCompletionStage` 用例：head 支持完成 → plan 携带 completion binding（`audit_run_id/state_version/state_hash`）与 `ART-PR=COMPLETED`（`PUBLICATION_LEDGER` 证据）；head 被降级 → `FAILED_NEEDS_HUMAN` 而非 `COMPLETED`；finalize 缺 binding → 拒绝；再实现
- [x] 9.5 `RequirementDeliveryEngineTest`（legacy `executeDeterministicReviewStage`/`executeCompletionStage`）同场景断言，保证双路径一致
- [x] 9.6 `SHADOW` 模式用例：门本应拒绝时仅写 `AuditRun.blockers[]` 与 warn 日志，判定结果与今日一致

## 10. 输出、管理 API 与前端

- [x] 10.1 先写 `RequirementPullRequestBodyComposerTest`（或既有 PR 描述组合器测试）：首段为审计清单（`AC-*` 状态、`auditRunId`、证据 URI），Agent `prBody` 其后并标注「未核验叙述」；与 `improve-requirement-pr-description` 模板对齐；再实现
- [x] 10.2 先写 `RdTaskAuditedStateControllerTest`：`GET /admin/rd-tasks/{taskId}/audited-state`、`GET /admin/rd-tasks/{taskId}/audit-runs`；项目归属不符 404；响应 record 不含 `prompt_snapshot`/`execution_result_json`；再实现 Controller（`bootstrap.controller.admin.rdtask`）
- [x] 10.3 前端：`rdTaskService` 新增两个只读请求；`frontend/test/auditedTaskStatePresentation.test.ts` 先写（状态徽标映射、证据链接、缺口列表）；`RdTaskDetailPage` 审计视图新增面板与摘要条「已审计 n/m」徽标
- [x] 10.4 更新 Vite proxy contract test：分别断言页面导航与两个嵌套 API；验证：`cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build`

## 11. 守卫与规则

- [x] 11.1 先写 `RequirementCompletionWriterPolicyTest`（bootstrap，源码扫描）：`RdTaskStatus.COMPLETED` 作为需求任务目标只出现在 `planCompletionStage`/`executeCompletionStage`/`RdTaskTransitionPolicy`；再修正任何越界写入者
- [x] 11.2 `TransactionalProxyPolicyTest` 覆盖新增 `@Transactional` 类非 `final`
- [x] 11.3 `RULE.md` 3.5.3 追加【强制】条款：完成必须绑定 `rd_task_completion_bindings`；写回只在 `recordOutcome/finalize` 边界；QA 指纹 `VIOLATION/SUSPECT` 不得晋升；Coding 成功 continuation 为 `HOST_VERIFY`；附验证命令（design「验证命令」段）
- [x] 11.4 `AGENTS.md` 追加：QA `criteriaId` 合同三处锁步与镜像重建提醒；`docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md` 标记阶段 1 状态

## 12. 真实验证与归档

- [ ] 12.1 云端（`deploy/cloud-server/`，项目 `codex-run-test-waimai`）以 `gate-mode=SHADOW` 提交 ≥ 5 个真实任务，其中 1 个候选补丁故意破坏构建；保存 `audited-state`/`audit-runs` 导出与「本应拒绝」日志到 `tmp-*/`（不入库），在 PR 描述记录 taskId 与结论
- [ ] 12.2 去掉服务器 `SHADOW` 覆盖以回到默认 `ENFORCE` 后，重复注入构建失败任务：断言任务未到 `COMPLETED`、无 QA attempt、`HOST_VERIFY` command 与 `AuditRun` 存在；正常任务 `COMPLETED` 后 `rd_task_completion_bindings` 有行且 PR body 首段为审计清单（真实 HTTP 请求链，RULE.md 6.2）
- [ ] 12.3 运行 design「验证命令」全部命令并把输出摘要写入 PR 描述
- [ ] 12.4 归档：`openspec archive mea-audit-only-writeback`，确认 `openspec/specs/requirement/audit-only-writeback/spec.md` 只含当前已验证行为；`OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`
