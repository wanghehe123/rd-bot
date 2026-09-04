# RD-Bot × DreamX MEA 改造总体方案

日期：2026-09-03
状态：阶段 1 代码已落地（`openspec/changes/mea-audit-only-writeback/` tasks 0–11）；待 Docker 镜像重建（8.5）、云端 SHADOW/ENFORCE 真机（12.x）与归档。本文档的阶段 2–7 仍为规划。交给实现 Agent 的入口是 `docs/superpowers/specs/2026-09-03-rd-bot-mea-agent-handoff.md`。

## 0. 文档边界与证据分级

- 方法论来源：`/Volumes/WishDisk/llms/paper/longhorizon-repro/RD-Bot-DreamX-MEA改造指南.md`（下称「指南」）。本方案按指南 §16 九步阅读顺序核查代码，按 §13 分阶段路线组织改造，按 §14 设计验证实验，按 §17 模板记录关键决策。
- 仓库约束来源：`RULE.md`、`AGENTS.md`、`openspec/config.yaml`、`openspec/specs/requirement/delivery-platform/spec.md`、`docs/openspec/historical-spec-provenance-audit.md`。
- 证据分级（沿用 `historical-spec-provenance-audit.md` 的分类）：
  - **VERIFIED_CURRENT**：本轮以 `rg`/`sed`/Read 直接核对过的代码符号与测试名。第 2 节的结论均属此类，行号为 2026-09-03 读码时的近似位置，符号名是权威锚点。
  - **PLAN_OR_DECISION**：第 4–9 节全部内容。任何一条在实现前都必须经 OpenSpec change（delta spec + design + tasks）落地，禁止直接改 `openspec/specs/`。
  - 子代理探索报告（附录 A）是二手材料，其关键结论已在本轮交叉验证（QA `repo/` rw、`repositoryState` 无调用方、`paused` 无派发检查、`RequirementDeliveryReviewer` 只做形状检查、`SUCCESS` 含 `COMMITTED`）。
- 本文档不替代 `docs/rd-task-management-requirements.md` / `-design.md`（它们以 `RdBugFixTask` 为中心，已不是需求交付当前真相），也不替代在途 change 的 artifacts。

## 1. 一页结论

RD-Bot 的需求交付链路已经具备 MEA 所需的大部分「硬件」：任务状态写入权收口在 Host finalizer（两阶段 `PREPARED → OUTCOME_RECORDED → FINALIZED`，任务 CAS + fencing + 租约 + 幂等键）、QA 在独立容器/镜像/网络中做真实浏览器验证并受宿主证据包校验、宿主 BUILD/STATIC 验证器与 Host 断言 Oracle 已存在、失败恢复有精确 provenance 与 checkpoint 重试。缺的是「软件」层面的四个权力边界：

1. **完成权仍以 Executor 自报为准真相。** `DETERMINISTIC_REVIEW` 只检查 Agent JSON 形状；`COMPLETED` 不绑定任何审计证据；宿主验证在生产逐条 command 路径被 `boundedRolePlan` 关闭；QA 只读性靠 prompt，`repositoryState` 指纹守卫未接线。
2. **没有任务级显式状态。** 只有单 session 的 `rd-agent-state/v2` 与 compact 交接 JSON，没有 requirement/artifact/fact × completed/pending/blocked/untrusted 的跨 episode 权威对象。
3. **没有 Manager。** 下一步由 `AgentRole.requirementDeliveryOrder()` 与 `ContinuationSpec` 静态决定；`ask` 退化为 `FAILED_NEEDS_HUMAN`，`paused` 只是标记，没有 `replan`。
4. **Executor episode 不 fresh、记忆未审计。** 重试注入截断的上一轮 `errorMessage`，`environmentNotes` 被当作已验证事实；项目记忆巩固在生产未接线，晋升策略以交付成功而非审计为依据。

改造顺序遵循指南 §19：先修完成权（阶段 1），再 fresh episode（阶段 2），再 Manager（阶段 3），再契约反查（阶段 4），再预算与恢复治理（阶段 5），再记忆（阶段 6），最后模型/成本（阶段 7）。每个阶段是一个独立 OpenSpec change，阶段 1 已完整成稿：`openspec/changes/mea-audit-only-writeback/`。阶段 0（基线）与阶段 1 并行启动，阶段 1 的退出条件是「Executor 虚报成功的任务被系统拒绝完成」。第 9 节六项决策已于 2026-09-03 冻结。

## 2. 九步现状核查（VERIFIED_CURRENT）

### 第一步：原始任务的权威链路

| 问题 | 结论 | 锚点 |
|---|---|---|
| 原始需求在哪创建 | 管理台 `POST /admin/rd-tasks/requirements`（`RdTaskController#createRequirement`）与飞书 `FeishuImMessageController#handleRequirement`；权威校验 `RagStreamTaskRegistry#createRequirementTask`（title/baseBranch/repo/expectedResult 非空，初始 `CREATED`） | `RdTaskController.java`、`FeishuImMessageController.java`、`RagStreamTaskRegistry.java` |
| 权威模型 | `RdRequirementTask`（`rd_tasks`），非 `RdBugFixTask`；字段 `expectedResult`、`acceptanceCriteriaJson`、`hostAssertionBundle`、`promptSnapshot`、`executionResultJson`、`version`、`fencingToken`；材料在 `rd_task_materials` | `RdRequirementTask.java`、`p0_knowledge_productionization.sql:243-330` |
| 是否不可变/版本化 | **否。** `PUT` 只能改 `title`/`priority`，但会让 `version` 与 `fencingToken` 同时 +1，持旧 fence 的 stage command 被拒；材料 `POST .../materials/*` 在 `EXECUTING` 期间仍可追加；角色 prompt 由 `RequirementAgentStageOrchestrator#buildAgentPrompt` 用**当时**任务快照与材料现场重建，`PROMPT_SNAPSHOT` 只是审计副本 | `RagStreamTaskRegistry#updateTaskMetadata`、`RdTaskFencingConcurrencyTest.metadataEditFencesOutStaleRequirementStageSnapshot`、`RdTaskController.java:556-575` |
| 契约对象 | **无。** 散落冻结物：策略 `plan_digest`/`policy_digest`（`rd_requirement_policy_runs`）、`rd_host_assertion_bundles`、QA 打回 `request.json` hash、checkpoint `operator_note` | `p1_multi_agent_orchestration.sql`、`HostOwnedAssertionGate#freeze`、`QaRemediationPackageBuilder` |

### 第二步：全局完成判定

| 问题 | 结论 | 锚点 |
|---|---|---|
| 谁能写 `COMPLETED` | 只有 Host：`RequirementDeliveryEngine#planCompletionStage`（durable）与 `#executeCompletionStage`（legacy），经 `RequirementStageFinalizationPort.finalize` 提交 | `RequirementDeliveryEngine.java`（`planCompletionStage` 约 1759–1765） |
| 完成依据 | 顺序门禁：角色协议 →（宿主验证，仅 legacy 路径）→ QA 证据包 → `DETERMINISTIC_REVIEW` → `AI_REVIEW`（可选）→ `PUBLICATION` → `REPORTING` → `COMPLETION`。`RequirementDeliveryReviewer#review`（76–132）只检查 Agent JSON 形状（四角色 `SUCCEEDED`、`testStatus∈{PASSED,SKIPPED}`、`qa.status=PASSED`、证据 URI 持久、CURRENT+REGRESSION），不读 `rd_host_verification_runs` | `RequirementDeliveryReviewer.java`、`RequirementDeliveryReviewerTest` |
| `COMPLETED` 是否绑证据 | **否。** mutation 为 `mutation(status, COMPLETED, "", "", "", "")`；`rd_tasks` 无证据列；证据只在旁路账本（`rd_qa_evidence_objects`、`rd_host_verification_artifacts`、`rd_requirement_publications`） | `planCompletionStage`、`p5_qa_evidence.sql`、`p15_host_verification.sql` |
| 局部/全局混淆 | 控制面无「四角色 SUCCEEDED ⇒ COMPLETED」；但观测面 `SUCCESS = {COMPLETED, COMMITTED, MERGED}` | `DeliveryObservabilityQueryService.java:48` |

### 第三步：状态写入权

| 问题 | 结论 | 锚点 |
|---|---|---|
| Executor 能否直写可信状态 | **不能写 `rd_tasks.status`。** 链：`rd_submit_result` → `result.json` → `DockerPiAgentExecutor` 协议校验 → `EngineRequirementExecutorAdapter` → `AgentStageRun` CAS → `planRoleExecutionStage` 产 plan → `finalize` | `DockerPiAgentExecutor#validateRoleProtocolResult`、`AgentRoleResultValidator`、`StructuredResultValidator` |
| 但 Executor 声明驱动什么 | `AgentStageRun.SUCCEEDED` 直接决定 `roleContinuation` 到下一角色；`testStatus`/`environmentNotes`/`facts[]` 进入 compact 交接被下游当事实 | `RequirementDeliveryEngine#roleContinuation`、`RequirementAgentStageOrchestrator#compactUpstreamHandoffJson` |
| 冲突证据 | 顺序否决（宿主验证否决 Coding 自报；QA 失败否决 Coding 成功），无并列仲裁台账 | `RequirementAgentStageOrchestrator#gateQaBehindHostVerification`、`RequirementAgentStageOrchestratorTest.verify_failure_does_not_invoke_qa_executor` |

### 第四步：attempt 与上下文

| 问题 | 结论 | 锚点 |
|---|---|---|
| 新 attempt 是否 fresh | **否。** `previousFailureFeedbackSection` 注入上一轮同角色 `errorCategory` + `errorMessage`（≤ 4000 字）；checkpoint 恢复段注入下游失败与操作员 note；QA 打回注入冻结 `request.json`（有界、hash-bound，这部分合理） | `RequirementAgentStageOrchestrator.java:2552-2578, 2593+`、`RequirementDeliveryEngine.MAX_ROLE_ATTEMPTS=3` |
| 下游读什么 | compact 交接 JSON（status/summary/environmentNotes≤8/facts≤16/handoff 指针/patch 元数据），不读 `AGENT_EVENTS`/`PI_RAW_EVENTS`（合理）；但 `environmentNotes` 被 prompt 写成「已实测验证，直接沿用」 | `RequirementAgentStageOrchestrator.java:1751-1812, 2083-2085` |
| 可信度标记 | 项目记忆有 `UNTRUSTED_PROJECT_MEMORY` 块；OpenViking 有 `<untrusted_knowledge>`；其它检索证据只有 sourceType/uri/hash/summary，无「线索非事实」标注 | `ProjectMemoryUntrustedContext.java`、`UntrustedKnowledgeFence.java`、`RoleContextBuilder.DEFAULT_MAX_CHARS=18000` |
| 显式状态 | `rd-agent-state/v2` 是单 session 状态栏（Host 验收 TODO 不可删、`rd_record_fact`≤32）；无跨 episode 任务级状态 | `PiAgentContextStateManager`、`agent-state-projector.mjs`、`rd_agent_stage_state_latest` |
| 产物隔离 | 角色工作区 `{taskId}`（Coding）/`{taskId}-qa_agent`（QA）；同角色重试复用根目录 `reset --hard`；QA provider-attempt 隔离未接线（测试 `@Disabled`） | `EngineRequirementExecutorAdapter#executionTaskId`、`DockerPiAgentExecutorTest` 943–964 |

### 第五步：QA 独立性

| 问题 | 结论 | 锚点 |
|---|---|---|
| 工作区/权限 | 独立 QA 镜像（`Dockerfile.qa`）、`--internal` 网络、`input` `:ro`、`default-qa@2` deny `edit`/`write`；**但 `repo/` 为 rw 且 `bash` 允许**（`READ_ONLY_REPO_ROLES` 只含 REVIEWER/ARCHITECT） | `DockerPiAgentExecutor.java:100-102, 1262`、`p8_pi_agent_runtime.sql:196-226`、`rd-pi-bridge.mjs#resolveToolNames` |
| 能否改产物 | 能改自己 checkout 的跟踪文件；`RepairWorkspaceRepositoryPort#repositoryState`（内容级 SHA-256，注释即 "QA no-mutation guard"）**在生产代码零调用** | `ProcessGitRepairWorkspaceRepository.java:161-217` |
| 是否独立跑验证 | 是：`npm run build && npm run start` + Playwright，证据包由 `QaEvidenceBundleValidator` 与 `result-tool.mjs` 双侧校验 | `QaEvidenceBundleValidator.java:84-316`、`result-tool.mjs#validateQaReport` |
| 失败如何成为修复契约 | PI-v2 产品打回：bounded/sanitized/hash-bound `attachments/qa-remediation/request.json`，≤2 轮，`attemptNo<=3`；协议失败 QA→QA 1 次 | `PiQaRemediationPlanner`、`QaRemediationPackageBuilder`、`AgentRemediationKind` |
| 其它审计者 | `HostVerificationPort`（BUILD/STATIC，仅 legacy 路径生效）、`RequirementDeliveryReviewer`（形状）、`AiDeliveryReviewEngine`（读全部 stage artifact 含 `PROMPT_SNAPSHOT`——存在指南 15.3 的锚定风险）、`HostAssertionOracle` | `AiReviewPackageBuilder.java:111-117` |

### 第六步：调度方式

| 问题 | 结论 | 锚点 |
|---|---|---|
| 角色顺序 | 固定：Reviewer → Architect → Coding → QA → `DETERMINISTIC_REVIEW` → `AI_REVIEW` → `PUBLICATION` → `REPORTING` → `COMPLETION` | `AgentRole#requirementDeliveryOrder`、`AgentWorkflowPlan#production`、`RequirementDeliveryEngine#roleContinuation` |
| 由什么决定下一步 | 预先 stage 表 + `task.status` 查表；command 逐条生成（成功 finalize 时插下一条）；策略控制命令 `POLICY_EVALUATE`/`POLICY_APPLY`/`APPROVAL_RESUME` 走独立 `RequirementPolicyTransactionPort` 事务（legacy `POLICY` stage 被 dispatcher 隔离） | `RequirementDeliveryDispatchService#nextStageFor`、`#continuationCommand`、`#enqueueStageCommand`（禁止凭空 mint 角色 command）、`#runPolicyApplyCommand`、`PostgresRequirementPolicyTransactionAdapter` |
| 非线性 | 白名单：QA→Coding 产品打回（intent 在 finalize 内插 command）；QA→QA 协议重试；操作员 checkpoint 重试；宿主验证廉价返工（仅 legacy plan） | `RequirementDeliveryEngine#buildPiQaRemediationIntent`、`TaskRetryEngine`、`boundedRolePlan`（`qaRemediationEnabled=false`、`hostVerifyRemediationEnabled=false`） |
| ask/approval/blocked | approval 有（`WAITING_APPROVAL` + `APPROVAL_RESUME`）；blocked 映射 `FAILED_NEEDS_HUMAN`/`DEAD_LETTERED`；**ask 弱**（`NEED_INFO` → `FAILED_NEEDS_HUMAN`，恢复靠 retry 补材料）；**`paused` 只写标记，dispatcher 不检查**；无 replan | `RdTaskStatus`、`RagStreamTaskRegistry#pauseTask`、`TaskRetryEngineTest.requiresSupplementForNeedInfoAndPersistsSelectedTaskEvidence` |
| 双路径 | `RequirementDeliveryEngine.submit()` legacy 同步 umbrella 仍可一次跑完全流水线（含 orchestrator 内 QA 修复与宿主验证）；生产走 dispatcher 逐条 command | `RequirementDeliveryEngine.java:843-968, 2761-2778` |

### 第七步：持久化与恢复

| 问题 | 结论 | 锚点 |
|---|---|---|
| 两阶段边界 | `prepare`(PREPARED) → `plan()` → `recordOutcome`(OUTCOME_RECORDED，plan JSON+digest) → `finalize`（task CAS → command CAS → continuation → FINALIZED）；恢复时 `isOutcomeRecorded()` 只 decode 不重跑 | `RequirementStageFinalizationPort`、`PostgresRequirementStageFinalizationAdapter`、`RequirementDeliveryDispatchServiceTest.recoveryFinalizesOutcomeRecordedPlanWithoutReplanningOrExecutingTheStage` |
| 幂等/版本/租约/fencing | task `version`+`fencing_token` CAS；command `(task_id,role,stage[,checkpoint|round])` 部分唯一 + lease；stage run `taskId:ROLE:attemptNo`；checkpoint `taskId:version:phase:role:attemptNo`；policy `ledger_version`；publication `operation_id` + UNKNOWN reconcile；remediation `row_version` + advisory lock | `RdTaskMapper.java:89-95`、`TaskRetryEngine.java:309-312`、`RequirementPublicationLedger#decideReplay` |
| 最危险窗口 | 执行中 / `OUTCOME_RECORDED` 之前崩溃：reclaim 同一 command `attempt_no+1` 会**重新 `plan()` 并可能重跑 agent**；角色执行没有等价于 publication ledger 的「已发生」标记 | `RequirementDeliveryDispatchService#runClaimedCommand` 708–732 |
| 恢复读什么 | 冻结 plan/intent/snapshot/ledger；`OUTCOME_RECORDED` 后禁止 live profile | `RULE.md:905-906`、`EngineRequirementExecutionProfileResolver` |

### 第八步：RAG 与长期记忆

| 问题 | 结论 | 锚点 |
|---|---|---|
| 数据模型 | `ProjectMemory`/`Revision`/`Source`，状态 `CANDIDATE/ACTIVE/SUPERSEDED/EXPIRED/REJECTED/QUARANTINED/DELETED`；SQL 有 `confidence/importance/evidence_quality/redacted/valid_to`，Java revision 不写这些列 | `p19_project_agent_memory.sql`、`ProjectMemoryRevision.java`、`ProjectMemoryRevisionMapper.java:16-20` |
| 写入是否经审计 | **否。** 晋升看 `deliverySucceeded/qaSucceeded/administratorConfirmed`；**生产未接线**：`FinalizationCommand` 兼容构造器固定 `ProjectMemoryOperationDraft.none()`，worker 默认关且 handler 为 stub；真机导出四表全空 | `ProjectMemoryPromotionPolicy`、`RequirementStageFinalizationPort.java:357-373`、`ProjectMemoryOperationWorkerConfiguration.java:41-42`、`tmp-codex-memory-run/project-memory-export-*.json` |
| 检索谓词与默认值矛盾 | 搜索要求 `redacted=TRUE` 且 `confidence/evidence_quality ≥ 0.5`；INSERT 默认 `redacted=FALSE`、0 → 即便写入也检索不到 | `ProjectMemoryMapper.java:53-55,105-109`、`ProjectScopedRequirementKnowledgeSearchAdapter.java:265` |
| 注入标注 | 项目记忆：`UNTRUSTED_PROJECT_MEMORY`（合规）；知识 chunk：进 evidence JSON 且 `DeepRetrievalOrchestrator` 把命中类型当角色证据门已满足；Skill `forceGuide`：指令性（Host 所有，合理） | `DeepRetrievalOrchestrator.java:439-491`、`rd-pi-bridge.mjs:584-602` |

### 第九步：预算与停止条件

| 预算 | 现状 | 锚点 |
|---|---|---|
| 角色 episode 墙钟 | 四角色同一 `rd.executor.pi.execution-timeout-millis`=1h（relay 对齐）；QA 启动 300s 硬编码 | `application.yaml:230`、`DockerPiAgentExecutor.QA_STARTUP_TIMEOUT_SECONDS` |
| 派发 | `command-deadline-millis`=1h、`lease-millis`=10min、`max-attempts`=3 → `DEAD_LETTERED`+`TECHNICAL_EXHAUSTED` | `application.yaml:62-73`、`RequirementStageCommand#claimable` |
| 修复轮 | `QA_PRODUCT_FIX=2`、`QA_PROTOCOL_RETRY=1`、`attemptNo<=3` | `AgentRemediationKind`、`AgentRemediationRound` |
| token | 单 episode `maxAgentTurns/maxTotalTokens` 默认 0=关；任务级只有 Reviewer 后估算 → `WAITING_APPROVAL` | `rd-pi-bridge.mjs:1143-1212`、`RequirementDeliveryEngine#effectiveTokenBudget` |
| 成本 | 只有飞书告警（`RepairExecutionWatchdog`），无硬停 | `RepairExecutionWatchdog.java` |
| 总轮次 / 墙钟 / 无进展检测 | **均无**（检索迭代 `NO_NEW_EVIDENCE` 默认关闭；Pi 协议恢复 1 次不是无进展检测） | `IterativeRetrievalStopGate`、`rd-pi-bridge.mjs:348-364` |
| 预算耗尽是否诚实 | 失败路径诚实（不会 `COMPLETED`，provenance 记 phase/kind）；但无「部分完成 + 已验证事实账本」 | `rd_task_failure_provenance`、`TaskRetryPointResolver` |

## 3. MEA 差距矩阵（指南 §12 表填充）

| MEA 概念 | RD-Bot 现状 | 差距等级 | 归属阶段 |
|---|---|---|---|
| Original task | `RdRequirementTask` 字段部分不可编辑，但材料可追加、prompt 现场重建，无版本化契约 | 中 | 4 |
| Stable contract | 无对象；冻结物散落（policy digest、assertion bundle、remediation hash） | 高 | 4 |
| Manager | 无；静态 `ContinuationSpec` + 白名单 remediation planner | 高 | 3 |
| Task state | 无任务级对象；`rd-agent-state/v2` 为 session 级 | 高 | 1 |
| Evidence refs | 旁路账本齐全，但 `COMPLETED` 不引用 | 高 | 1 |
| Executor episode | 非 fresh（注入截断失败文本、`environmentNotes` 当事实） | 中 | 2 |
| Bounded contract | command 路径「一条 command = 一组 mutation」基本成立；legacy 路径不成立 | 低 | 1（收敛 legacy）/3 |
| Auditor | QA 独立执行成立；宿主验证/断言存在但不聚合；形状复核当真相 | 高 | 1 |
| Read-only enforcement | 部分技术强制；`repo/` rw + `bash`，指纹未接线 | 中 | 1（指纹）/2（provider-attempt 隔离） |
| Completion gate | 只有 Completion 维度；无 Integrity、Contract alignment | 高 | 1（Completion+Integrity）/4（Contract） |
| Ask route | `NEED_INFO` → 人工；无问答 round | 中 | 3 |
| Blocked route | `FAILED_NEEDS_HUMAN`/`DEAD_LETTERED`，无统一 blocked 对象 | 低 | 3 |
| Resume | 强：冻结 plan/intent/ledger/checkpoint；缺 Manager 决策持久化与角色执行「已发生」标记 | 低 | 3/5 |
| Budget | episode/attempt/修复轮有；任务级 token/成本/总轮次/墙钟/无进展检测无 | 中 | 5 |
| Memory promotion | 模型有、生产未接线、晋升非审计、检索谓词矛盾 | 高 | 6 |

## 4. 关键改造决策卡（指南 §17 模板）

### 决策 K1：完成权 = Host 确定性审计器 + 显式已审计状态

- 现有行为：`planCompletionStage` 空 mutation；`RequirementDeliveryReviewer` 形状检查；宿主验证 bounded 路径关闭。写入者 Host；完成依据 Agent JSON。
- MEA 要求：Audit-only writeback（§3.1）；每条 completed 有审计轮次与证据引用（§5.3）。
- 差距：语义（自报当真相）、证据（无引用）、持久化（无状态对象）。
- 候选方案：(1) 最小闭环——Host `DeterministicAuditor` 聚合既有宿主证据源写 `AuditedTaskState`，复核/发布/完成读该状态；(2) 完整——再加模型型 Auditor 做契约反查。**选 (1)**，(2) 留阶段 4。**2026-09-03 已确认：`HOST_VERIFY` 独立 command；QA 指纹 fail-closed；`gate-mode` 默认 `ENFORCE`。**
- 风险：误拒（`SHADOW` 期核对）；混合版本部署（plan v3）；成本（每 command 一次 revision）。
- 验收：`RequirementDeliveryStageExecutionTest`「Executor 虚报成功 → REJECTED」；真机注入构建失败任务不到 `COMPLETED`。
- 落地：`openspec/changes/mea-audit-only-writeback/`。

### 决策 K2：宿主验证作为独立 durable command `HOST_VERIFY`

- 现有行为：`gateQaBehindHostVerification` 只在 legacy `production()` 生效；`boundedRolePlan` 关闭。
- MEA 要求：Auditor 不通过不得进入最终交付；每轮一个主要状态变化。
- 差距：权限/调度（生产路径无此门）。
- 候选：(1) 在 Coding bounded command 内同步跑验证；(2) 独立 command。**选 (2)**：与逐条 command 架构一致、可独立重试/恢复、为阶段 3 Manager 提供决策点；(1) 会让一条 command 承载两个状态变化且违反 RULE.md 对 bounded 路径关闭内循环的约束精神。
- 风险：多一次干净工作区构建墙钟；与 `host-verification-pipeline` D2 的表述需对齐。
- 验收：`RequirementDeliveryDispatchServiceTest` Coding 成功 → 下一条 `HOST_VERIFY`。

### 决策 K3：QA 只读靶向 tracked-tree 指纹，不改 `:ro`

- 现有行为：`repo/` rw、`bash` 开、`repositoryState` 未调用。
- MEA 要求：只读由技术约束保证（§8.3），区分「最终状态修改」与「可隔离检查副作用」。
- 差距：权限。
- 候选：(1) `repo/` `:ro`——破坏 `npm run build`；(2) 指纹 + 违规 fail-closed；(3) 去掉 `bash`——QA 无法跑命令。**选 (2)**，阶段 2 再加 provider-attempt 级隔离。
- 风险：大仓库指纹耗时；误报（构建写了跟踪文件，如 lockfile）→ 该情况本身应视为违规并由 QA profile 调整。
- 验收：`DockerPiAgentExecutorTest` 指纹回执；`DeterministicAuditorTest` `VIOLATION/SUSPECT` 不晋升。

### 决策 K4：验收标准 ID `AC-%03d` 成为状态、TODO 与 QA 证据的共同主键

- 现有行为：`PiAgentContextStateManager` 已用 `AC-%03d`；QA PASSED 项无 `criteriaId`；发布视图 `criteria` 为自由文本。
- MEA 要求：证据必须指向真实状态载体（§3.3）；契约反查需要可枚举的 blocking requirements（§8.6）。
- 差距：证据无法逐条映射。
- 方案：PI-v2 QA CURRENT 结果必带 `criteriaId`；提示词/`result-tool.mjs`/`QaEvidenceBundleValidator` 三处锁步（AGENTS.md 协议裂缝规则）；legacy QA 文本规范化匹配 fail-closed。
- 风险：镜像未同步重建导致全部任务 `INCOMPLETE`（阻塞但不误判）。

### 决策 K5：Fresh Executor = 用已审计状态替代失败文本

- 现有行为：注入 `errorMessage`≤4000 字与下游失败文本；`environmentNotes` 标「已实测验证」。
- MEA 要求：新 episode 只注入原始目标、契约、可信状态与本轮必要证据（§7.2）；旧轨迹留作诊断。
- 方案（阶段 2）：`previousFailureFeedbackSection` 改为由 `AuditedTaskState` 生成的「缺口段」（`missing[]`/`blockers[]`/`untrusted[]`，有界）；`environmentNotes`/`facts[]` 一律以 `UNTRUSTED` 标注，除非已被 `AuditRun` 晋升；QA 打回包保持 hash-bound 但来源改为 `AuditRun`；每角色 episode 默认非零 `maxAgentTurns`/`maxTotalTokens`；启用 QA provider-attempt 隔离。
- 验收：中断—恢复实验：新 Executor 仅凭状态续做，prompt 不含旧 `errorMessage` 原文。

### 决策 K6：Manager 落在 continuation 边界，先规则后动态

- 现有行为：`roleContinuation` 静态；`nextStageFor(status)` 查表；`PiQaRemediationPlanner` 是微型规则 Manager。
- MEA 要求：每轮读可信状态选一个主要状态变化，五路由（§6.3），无环境写权限（§6.2）。
- 方案（阶段 3，**已确认新增 `RdTaskStatus.WAITING_USER_INPUT`**）：新增 durable stage `MANAGER_DECIDE`（role `REQUIREMENT_DELIVERY`），在每个已审计 command 之后由 continuation 进入；`planManagerDecisionStage` 读 head 状态 + 契约 + 预算账本 → `ManagerDecision{route∈EXECUTE|DONE|BLOCKED|ASK|REPLAN, targetRecordId, boundedContract, executorRoute, rationale}` 持久化到 `rd_task_manager_decisions`（`UNIQUE(task_id, round_no)`，幂等于 `state_version`）→ 输出 `ContinuationSpec`。3.0 用确定性规则复刻今日固定顺序与 remediation 白名单（行为保持）；3.1 允许插入诊断 Executor、重新审计、跳过已有可信证据覆盖的工作。`ASK` **不得**复用 `WAITING_APPROVAL`（该状态专用于策略/token 预算审批）。必须新增共享枚举 `RdTaskStatus.WAITING_USER_INPUT`，并按 `RdTaskType` 分图写入 `RdTaskTransitionPolicy` 需求图：`EXECUTING → WAITING_USER_INPUT`；`WAITING_USER_INPUT → EXECUTING | REJECTED | FAILED_RETRYABLE | FAILED_NEEDS_HUMAN | CANCELLED | DEAD_LETTERED`；`RECOVERING → WAITING_USER_INPUT`。恢复命令为 `USER_ANSWER_RESUME`（镜像 `APPROVAL_RESUME` 的独立事务端口，不经 `planStage`），入口 `POST /admin/rd-tasks/{id}/answer`。`WAITING_USER_INPUT` 是在途而非终态：dispatcher 不得 claim 角色 command；观测 SUCCESS/FAILURE/TERMINAL 均不含该状态。前端徽标与 `WAITING_APPROVAL` 区分。`paused` 改为 Manager 决策前置检查，dispatcher 不 claim 已暂停任务。Manager 无工作区与容器权限（engine 纯函数 + store 端口）。BugFix 图不得写入该状态（RULE.md 3.5.3）。
- 验收：中途制造新缺口（QA 发现未覆盖 `AC-*`）→ 插入修复/重审计而非进入下一固定阶段。

### 决策 K7：稳定契约与三维门禁

- 现有行为：无契约对象；`AiDeliveryReviewEngine` 读全部 artifact。
- MEA 要求：契约版本化（§4）；Completion/Integrity/ContractAudit 三维（§8.4）；验收约束反查（§8.6）。
- 方案（阶段 4）：`rd_task_contracts`（在 `POLICY_APPLY` 冻结 v1：最终消费者、状态载体、权威输入 hash、验收清单、非目标保持、禁止捷径）；`EXECUTING` 期间追加材料 → 契约 v+1 → Manager `REPLAN`/`ASK`；`AiDeliveryReviewEngine` 改造为只读契约审计器：输入原始请求 + 契约 + 已审计状态 + Executor 摘要，**不输入** `PROMPT_SNAPSHOT`/`AGENT_EVENTS`，输出结构化 `contractAudit∈{ALIGNED,NEEDS_REVISION,INVALID}` 与 blocking unknowns；完成要求三维同时满足且 blocking unknowns 为空；最终消费者检查（PR 真实存在、分支含提交、body marker）。
- 验收：局部测试全过但遗漏一条原始要求 → `incomplete`。

### 决策 K8：预算、无进展与记忆晋升

- 阶段 5：`rd_task_budget_ledgers`（总轮次、墙钟、token、CNY）由 Manager 每轮读写；耗尽 → provenance `BUDGET_EXHAUSTED` + 保留已审计状态（诚实部分完成）；无进展检测 = 连续 N 轮 `state_hash` 不变且无新 `AuditRun.verified[]` → `BLOCKED`/`ASK`；角色执行「已发生」回执（`rd_requirement_stage_execution_receipts`）让 `OUTCOME_RECORDED` 前的 reclaim 先 reconcile 已落盘结果（复用 `InterruptedStageRecoveryService`）而非重跑；四边界故障注入测试。
- 阶段 6：生产 finalize 传真实 `ProjectMemoryOperationDraft`；晋升条件改为「存在 `AuditRun` 证据引用」而非交付成功；修正 INSERT 默认值与检索谓词；知识 chunk 不再单凭启发式类型满足角色证据门（标为 hint）。

## 5. 分阶段路线（PLAN_OR_DECISION）

每阶段 = 一个 OpenSpec change；命名、范围、退出条件与验证命令如下。阶段间存在硬依赖时注明。

### 阶段 0：可比较基线（与阶段 1 并行）

- change：`mea-baseline-evaluation`（只增评测配置与报告，不改交付行为）。
- 复用：`docs/superpowers/specs/2026-07-30-evaluation-v2-coding-ablation-design.md`、`2026-07-31-coding-benchmark-step6-control-plane-design.md` 的评测控制面；云端真实任务模式 `deploy/cloud-server/run-codex-memory-live-task.py`（项目 `codex-run-test-waimai`）。
- 任务集（指南 §14.3，**已确认**）：项目固定为 `codex-run-test-waimai`，沿用 `deploy/cloud-server/run-codex-memory-live-task.py` 的项目/知识库/seed 约定。≥ 12 个带真实浏览器验收的需求任务，覆盖：多文件修改、后半程暴露隐含约束、易虚报完成（如仅改文案不改逻辑）、需要审批、注入超时/进程终止、含过时项目记忆、检查点续做、易写错分支、非目标保持。每个任务附独立评测器脚本（hidden acceptance）。
- 指标：端到端通过率、false-complete rate（系统 `COMPLETED` 但 hidden acceptance 失败）、false-block rate、每成功任务成本（token/CNY/墙钟）、失败分类（产品/Agent/基础设施/评测器）。
- 预注册阈值（写入 change 的 design，改造前冻结）：见第 6 节。
- 退出：同批任务可稳定重复运行 ≥ 3 次，主要失败模式已分类。

### 阶段 1：Audit-only writeback 最小闭环

- change：`openspec/changes/mea-audit-only-writeback/`（已成稿：proposal/design/delta spec/tasks）。
- 范围：`AuditedTaskState` + `AuditRun`（p20）、`DeterministicAuditor`、plan schema v3 写回、`HOST_VERIFY` durable command + `HOST_VERIFY_FIX(2)`、QA tracked-tree 指纹、QA `criteriaId` 合同、`DETERMINISTIC_REVIEW`/`PUBLICATION`/`COMPLETION` 审计门与完成绑定、PR 审计清单、管理 API 与前端面板、源码守卫、`gate-mode=SHADOW|ENFORCE`。
- 前置：`host-verification-pipeline` tasks 对齐与归档（或 design 记录承接）。
- 退出：「Executor 虚报成功」测试与真机注入构建失败任务均被拒绝完成；`unaudited_claim_promoted_to_completed = 0`。
- 验证命令：见该 change `design.md`「验证命令」。
- **实现状态（2026-09-03）**：tasks 0–11 代码与聚焦测试已落地（PR 审计清单、只读管理 API、前端已审计面板、完成写入者守卫、RULE.md / AGENTS.md）。8.5 需本机 Docker daemon；12.x 需云端项目 `codex-run-test-waimai` 真机后才能归档。

### 阶段 2：Fresh Executor episode

- change：`mea-fresh-executor-episode`。依赖阶段 1（缺口段来源于 `AuditedTaskState`）。
- 范围：
  - `RequirementAgentStageOrchestrator#previousFailureFeedbackSection` / `RequirementDeliveryEngine` 同构段 → `auditedGapSection(head)`（有界：≤ 16 条缺口、≤ 2000 字，只含记录 ID、缺口描述、证据引用 URI，不含原始 `errorMessage`）；原始失败文本只进 `RESULT_JSON`/`AGENT_EVENTS` 审计产物。
  - compact 交接的 `environmentNotes`/`facts[]` 一律标 `UNTRUSTED`，prompt 删除「已实测验证，直接沿用」措辞；已被 `AuditRun` 晋升的 fact 以 `VERIFIED(auditRunId)` 标注。
  - `RoleContextBuilder` 证据项加 `trust=HINT|VERIFIED` 字段并在 `roleContextJson` 中输出；`DeepRetrievalOrchestrator` 角色证据门只接受 `VERIFIED` 或 Host 材料，检索命中降为 hint（与阶段 6 交界，此处只改门的输入标注）。
  - 每角色 episode 预算：`AgentExecutionProfile` 新增 `maxAgentTurns`/`maxTotalTokens` 默认非零（按角色），冻结进 snapshot；`BUDGET_EXCEEDED` 映射为 `AuditRun.blockers[]`。
  - QA provider-attempt 隔离：启用 `DockerPiAgentExecutorTest` 两个 `@Disabled` 用例并接线 `createProviderAttempt`。
- 退出：中断—恢复实验：kill 进程后新 attempt 的 `PROMPT_SNAPSHOT` 不含旧 `errorMessage` 原文，且任务凭状态续做到完成。
- 验证：`RequirementAgentStageOrchestratorTest`、`RequirementDeliveryEngineTest`（prompt 断言重写）、`DockerPiAgentExecutorTest`、Pi `npm test`、真机中断恢复一次。

### 阶段 3：有界子任务与动态 Manager

- change：`mea-manager-decision-command`。依赖阶段 1（状态）、建议在阶段 2 之后。
- 范围（见决策 K6）：`MANAGER_DECIDE` durable stage 与 `rd_task_manager_decisions`；`ManagerPolicy` 3.0 规则版（行为保持）→ 3.1 动态版；`WAITING_USER_INPUT` 状态 + `RdTaskTransitionPolicy` 需求图新增边 + `POST .../answer` + `USER_ANSWER_RESUME`；`paused` 生效；`RequirementDeliveryDispatchService#nextStageFor`/`continuationCommand` 改为读 `ManagerDecision`；观测面新增 route 分布指标（与 `upgrade-delivery-observability` 协调）。
- 硬约束：Manager 位于 engine 纯函数 + store 端口，无 `RepairExecutorPort`/容器/工作区依赖（源码守卫）；每轮决策幂等于 `(task_id, state_version)`；决策与 continuation 同 finalize 事务。
- 退出：QA 发现未覆盖 `AC-*` → Manager 派发针对该缺口的 Coding bounded contract 并重新审计，而不是进入 `DETERMINISTIC_REVIEW`；`NEED_INFO` → `WAITING_USER_INPUT`，回答后从同一 round 续做。
- 验证：`ManagerPolicyTest`、`RequirementDeliveryStageExecutionTest`、`RequirementDeliveryDispatchServiceTest`、`RdTaskTransitionPolicyTest`、`RdTaskControllerTest`、前端 proxy contract；`PostgresRdTaskStateAtomicRealSmokeTest`。

### 阶段 4：三重完成门禁与契约反查

- change：`mea-stable-contract-and-backcheck`。依赖阶段 1、3。
- 范围（见决策 K7）：`rd_task_contracts` 版本化；材料追加触发契约 v+1 与 Manager `REPLAN/ASK`；`AiDeliveryReviewEngine` 改造为只读契约审计器（输入剔除 `PROMPT_SNAPSHOT`/`AGENT_EVENTS`，输出结构化三维结论）；`AuditedCompletionGate` 要求 `contractAudit=ALIGNED` 且 blocking unknowns 为空；最终消费者检查（GitHub `GET /repos/.../pulls`、分支含提交、body marker）计入 `ART-PR` 证据。
- 退出：局部测试全过但遗漏一条原始要求 → `incomplete`；契约被中间计划弱化 → `NEEDS_REVISION` 阻断。
- 验证：`AiDeliveryReviewEngineTest`（输入白名单断言）、`RequirementDeliveryStageExecutionTest`、真机对照任务。

### 阶段 5：恢复、并发与预算治理

- change：`mea-budget-and-recovery-governance`。依赖阶段 3。
- 范围（见决策 K8）：`rd_task_budget_ledgers`；Manager 预算路由；`BUDGET_EXHAUSTED` provenance 与诚实部分完成视图；无进展检测；角色执行「已发生」回执与 reclaim 前 reconcile；故障注入测试矩阵（Manager/Executor/Auditor/持久化四边界 × 派发前/执行中/结果已算出/`OUTCOME_RECORDED` 后/finalize 中/PR 发布中）。
- 退出：四边界注入故障，任务不误报完成、已审计进展零丢失、无重复远端副作用。
- 验证：`RequirementDeliveryDispatchServiceTest` 故障矩阵、`PostgresRequirementStageFinalizationRealSmokeTest`、`RequirementPublicationCrashWindowAcceptanceTest`。

### 阶段 6：项目记忆与跨任务经验

- change：`mea-audited-memory-promotion`。依赖阶段 1（`AuditRun` 作为晋升证据）。
- 范围：生产 `FinalizationCommand` 传真实 `ProjectMemoryOperationDraft`；worker handler 从 stub 变为 extractor→validator→resolver→CAS；`ProjectMemoryPromotionEvidence` 增加 `auditRunIds`，`ACTIVE` 需 ≥1 引用；INSERT 写 `redacted/confidence/evidence_quality/valid_to`，与检索谓词一致；检索命中在角色门中只作 hint；晋升错误率纳入观测。
- 退出：伪造/过时经验不能扩大权限、不能绕过 QA、不能把 `AC-*` 标 `COMPLETED`（对抗测试）。
- 验证：`ProjectMemoryPromotionPolicyTest`、`ProjectMemoryConsolidationEngineTest`、`ProjectAgentMemoryPostgresRealSmokeTest`、真机导出非空且检索命中。

### 阶段 7：角色模型与成本优化

- change：`mea-role-model-routing`。依赖阶段 0–6 完成且消融结果可读。
- 范围：`AgentExecutionProfile` 按 Manager 路由与风险等级选模型；Auditor 先确定性后模型；高风险决策升级强模型。
- 退出：在阶段 0 任务集上，固定预算成功率不降、每成功任务成本下降。

## 6. 验证实验设计（指南 §14）

### 6.1 消融组（映射到 RD-Bot）

| 组 | 显式状态 | Fresh Executor | 独立 Auditor 门 | 动态 Manager | 对应实现 |
|---|---|---|---|---|---|
| R0 基线 | 否 | 否 | QA 存在但形状复核 | 否 | 当前 `main` |
| R1 | 是 | 否 | 是 | 否 | 阶段 1 `ENFORCE` |
| R2 | 是 | 是 | 是 | 否 | 阶段 1+2 |
| R3 | 是 | 是 | 是 | 是 | 阶段 1+2+3 |
| R4 | 是 | 是 | 是 + 契约反查 | 是 | 阶段 1–4 |

预算有限时优先比较 R0 / R1 / R3（对应指南 A / D / E）。

### 6.2 公平比较控制

同一模型与版本、同一 reasoning effort、同一 Pi 镜像 digest、同一 `AgentExecutionProfile` 冻结快照、同一任务输入与材料、同一总 token 与墙钟预算、同一云端宿主（`deploy/cloud-server/`）、每组 ≥ 3 次独立运行；基础设施失败（Docker 125/EACCES/网络）单独统计。

### 6.3 指标

- 质量：hidden acceptance 通过率、完整任务成功率、blocking requirement 覆盖率、false-complete rate、false-block rate、QA false-pass/false-reject、契约遗漏率。
- 恢复：故障注入恢复率、恢复后重复工作比例、已核验进展丢失率、幂等重放重复副作用比例。
- 成本：总 token、分角色 token（Manager/Executor/Auditor）、墙钟、每成功任务 CNY、episode 数与审计数、无效重复轮次比例。
- 证据质量：`COMPLETED` 带有效证据引用比例（阶段 1 后应为 100%）、Executor 声明被 Auditor 推翻比例、记忆候选错误晋升率。

### 6.4 预注册成功阈值

```yaml
primary:
  false_complete_rate: 相比 R0 下降 ≥ 50%
  end_to_end_pass_rate: 不低于 R0
recovery:
  injected_failure_recovery_rate: ≥ 80%
  verified_progress_loss_rate: 0%
cost:
  median_cost_per_success: ≤ R0 的 1.8 倍
  manager_token_share: ≤ 10%
safety:
  unaudited_claim_promoted_to_completed: 0
  auditor_mutates_protected_state: 0   # 由 QA 指纹 VIOLATION 计数
```

阈值在阶段 0 change 的 design 中冻结，不得事后移动。

## 7. 与在途 change 的协调

| change | 关系 | 动作 |
|---|---|---|
| `host-verification-pipeline` | 代码已落地、tasks 全未勾选；D2 只覆盖 legacy 路径 | owner 对齐 tasks 并归档，或在 design 记录 durable 路径由阶段 1 的 `HOST_VERIFY` 承接 |
| `upgrade-delivery-observability` | 已实现未归档；代码 `SUCCESS = {COMPLETED, COMMITTED, MERGED}` | **已确认**：归档后以新 delta 把交付成功率分子改为仅 `COMPLETED`。`COMMITTED` 视为在途（PR 账本确认 ≠ 任务完成）。当前状态为 `MERGED` 时，仅当 `rd_task_status_events` 中存在 `COMPLETED` 才计入成功（`COMPLETED → MERGED`）；`COMMITTED → MERGED` 且从未 `COMPLETED` 的任务不得计入成功。`WAITING_USER_INPUT`（阶段 3）加入在途集合。本阶段 1 change 不改观测代码。 |
| `pi-agent-state-and-qa-remediation-v2` | `AC-%03d`、remediation 账本、`rd_agent_stage_state_latest` 被阶段 1 复用 | 无冲突；阶段 1 放宽 `rd_agent_remediation_rounds.kind` 约束 |
| `project-scoped-agent-memory` | 阶段 6 的基础 | 阶段 6 前先归档其已实现部分，并把「生产未接线」记为其 tasks 未完成项 |
| `improve-requirement-pr-description` | PR 描述模板 | 阶段 1 的审计清单首段需在该 change 模板中占位 |
| `cloud-server-live-verification` | 真机验证流程 | 阶段 0/1 的真机步骤复用其脚本与证据目录约定 |

## 8. 规范约束与 RULE.md 增补草案

实现时必须遵守的既有条款：模块依赖方向（1.1）；`@Bean` 依赖显式（2.x）；端口/适配器分层与 PostgreSQL 真值（3.1、3.4）；任务快照与事件同事务、`AgentStageRun` CAS、checkpoint 提交边界（3.5.3）；Pi 只走容器与 relay、`--internal`、docs-only 键只走 `dockerMetadataJson`、`boundedRolePlan` 关闭内循环、协议失败 receipt 白名单、remediation 在 `recordOutcome/finalize` 边界持久化（3.5.x Pi 段）；异常与状态码约定（4.x、5.3）；每个公开能力有测试、真实 HTTP 请求链、`PostgresRdTaskStateAtomicRealSmokeTest`（6.x）；前端代理合同测试（5.x）。

随阶段 1 归档追加到 `RULE.md` 3.5.3 的【强制】草案（实现时以可验证形式写入并附验证命令）：

1. 需求任务写为 `COMPLETED` 的 finalize 事务必须同时写 `rd_task_completion_bindings`；`RequirementCompletionWriterPolicyTest` 钉住完成写入者集合。
2. `AuditedTaskState` 只能在 `RequirementStageFinalizationPort.recordOutcome/finalize` 边界内由冻结 plan 的 `auditedStateMutation` 写回；同 `state_version` 只允许同 hash 幂等重放。
3. `ROLE_EXECUTION:CODING_AGENT` 成功的 continuation 必须是 `HOST_VERIFY`；不得再直接续派 `ROLE_EXECUTION:QA_AGENT`。
4. `QA_AGENT` 容器前后 tracked-tree 指纹必须写入 `dockerMetadataJson`；`VIOLATION`/`SUSPECT` 的 QA 结果不得晋升任何记录。
5. PI-v2 QA `acceptanceResults[]` CURRENT 必带冻结集合内 `criteriaId`；提示词、`result-tool.mjs`、`QaEvidenceBundleValidator` 三处必须同步，改后重建两个 Pi 镜像。

`AGENTS.md` 追加：改动审计器、`HOST_VERIFY`、指纹或 QA 合同前先追踪 `RequirementDeliveryDispatchService.submit → planStage → DeterministicAuditor → finalize` 链；`unaudited_claim_promoted_to_completed` 任何非零都是阻断缺陷。

## 9. 已确认决策与剩余风险

2026-09-03 用户冻结（对应原待确认项 1–6）：

| # | 决策 | 落地位置 |
|---|---|---|
| 1 | `rd.requirement-delivery.audited-writeback.gate-mode` **默认 `ENFORCE`**。云端首次发布仍按阶段 1 Migration Plan：`application-local.server.yaml` 显式 `SHADOW`，≥ 5 个真实任务（含 1 个故意破坏构建）核对后再切回默认。 | `mea-audit-only-writeback` D12、tasks 0.3 / 12.x |
| 2 | **`HOST_VERIFY` 作为独立 durable command**，不嵌入 Coding command。接受每任务多一次干净工作区构建墙钟（估计 1–5 分钟）。 | 阶段 1 D4、`roleContinuation(CODING_AGENT)` |
| 3 | **QA 只读 = tracked-tree 指纹 fail-closed**。保留 `repo/` rw 与 `bash`；指纹 `VIOLATION`/`SUSPECT` 转人工；阶段 1 **不**自动重审计。 | 阶段 1 D6 |
| 4 | 阶段 3 **新增 `RdTaskStatus.WAITING_USER_INPUT`**，不复用 `WAITING_APPROVAL`。边与观测分类见决策 K6。 | 阶段 3 `mea-manager-decision-command` |
| 5 | 交付观测 **SUCCESS 仅 `COMPLETED`**。`COMMITTED` 为在途；`MERGED` 仅在历史含 `COMPLETED` 时计成功。阶段 1 不改观测代码；`upgrade-delivery-observability` 归档后以新 delta 落地。 | 第 7 节协调表 |
| 6 | 阶段 0 任务集项目 **沿用 `codex-run-test-waimai`**（脚本 `deploy/cloud-server/run-codex-memory-live-task.py`）。 | 阶段 0 / 阶段 1 真机 tasks 12.1 |

仍开放、实现时再定（不阻塞阶段 1 开工）：

- `HostOwnedAssertionGate` 映射为独立 `GATE-HOST-ASSERTION`（bundle 存在时才初始化）还是并入 `AC-*`：阶段 1 design 默认独立门。
- legacy QA（无 v2 capability）`criteria` 文本规范化匹配的误匹配率，实现时用历史结果验证。

已识别但由实现阶段处理的风险：混合版本部署（plan v3 / `HOST_VERIFY` stage）需要 drain 或同时升级；Pi 镜像与 Host 合同必须同一发布；legacy `submit()` 与 durable 路径的双写漂移由双路径测试约束；大仓库指纹耗时纳入 QA 墙钟。

工具缺失：`AGENTS.md` 引用的 `model-escalation` skill 在本机不存在，本方案中的高风险决策（完成权、schema、并发）未经该 advisor 复核，已在第 4 节以决策卡形式显式记录候选方案与放弃理由，供人工复核。

## 附录 A：探索来源与本轮命令

子代理只读探索报告（二手材料，关键结论已交叉验证）：

- 第一至三步（原始任务链路 / 完成判定 / 状态写入权）：`82b81d9e-5ce6-4c29-9169-e4ef45877929`
- 第四至五步（attempt 上下文 / QA 独立性）：`32f23569-8d77-4ac2-886d-f8bcc64d0f5b`
- 第六至七步（调度 / 持久化恢复）：`e231dc3e-57b6-4f77-877d-5814905c5292`
- 第八至九步 + 前端（记忆 / RAG 可信度 / 预算 / 任务详情 IA）：`f89d6102-87c4-4786-8906-b98f41516893`

本轮实际运行的只读命令（无代码改动、无测试运行）：

```bash
rg -n 'READ_ONLY_REPO_ROLES' exec/src/main/java/com/wish/rd/exec/repair/pi/impl/DockerPiAgentExecutor.java
rg -n 'repositoryState\(' --glob '*.java' -g '!**/target/**' -g '!**/test/**'
sed -n '196,226p' bootstrap/src/main/resources/sql/postgres/p8_pi_agent_runtime.sql
rg -n 'paused' bootstrap/src/main/java/com/wish/rd/bootstrap/threading/RequirementDeliveryDispatchService.java engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java engine/src/main/java/com/wish/rd/engine/scheduling/
rg -n 'COMMITTED|MERGED|COMPLETED' engine/src/main/java/com/wish/rd/engine/admin/observability/DeliveryObservabilityQueryService.java
rg -n 'case "' engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java
openspec --version   # 1.8.0
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict   # 13 passed
```

## 附录 B：术语映射

| MEA | RD-Bot 现有符号 | 阶段 1 新增 |
|---|---|---|
| Original task | `RdRequirementTask` / `rd_tasks` / `rd_task_materials` | `AuditedTaskState.contractRef`（阶段 4 升为 `rd_task_contracts`） |
| Stable contract | `acceptanceCriteriaJson` + `hostAssertionBundle` + policy digest | 同上 |
| Task state | （无）`rd-agent-state/v2` 为 session 级 | `AuditedTaskState` / `rd_task_audited_state_*` |
| Evidence refs | `rd_qa_evidence_objects`、`rd_host_verification_artifacts`、`rd_requirement_publications` | `EvidenceRef` + `rd_task_completion_bindings` |
| Manager | `roleContinuation` / `nextStageFor` / `PiQaRemediationPlanner` | （阶段 3）`MANAGER_DECIDE` / `rd_task_manager_decisions` |
| Executor | Pi 角色容器（`DockerPiAgentExecutor`） | 不变；声明进入 `UNTRUSTED` |
| Auditor | QA 容器 + `HostVerificationPort` + `HostAssertionOracle` + `RequirementDeliveryReviewer` | `DeterministicAuditor` / `AuditRun` / `HOST_VERIFY` command |
| Completion gate | `RequirementDeliveryReviewer` + `planCompletionStage` | `AuditedCompletionGate` |
| Round | `rd_requirement_stage_commands` 一条 command | `AuditRun` 与 command 1:1（`UNIQUE(command_id)`） |
| Resume | `rd_requirement_stage_finalizations` / checkpoint / publication ledger | 写回幂等于 `state_version` |
