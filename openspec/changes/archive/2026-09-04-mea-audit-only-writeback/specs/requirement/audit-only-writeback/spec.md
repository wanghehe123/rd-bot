## Purpose

定义需求交付任务的 Host 拥有的显式已审计状态（`AuditedTaskState`）、审计轮次（`AuditRun`）与完成门禁合同：Executor（Pi 角色容器）的结果 JSON 只能进入 `UNTRUSTED`，只有 Host 确定性审计器依据真实环境证据产生的 `AuditRun` 才能把记录晋升为 `COMPLETED`；交付复核、PR 发布与 `COMPLETED` 写入必须以已审计状态为唯一依据，并把完成绑定到具体审计轮次与证据引用。本能力对应 DreamX MEA 改造路线的阶段 1（Audit-only writeback 最小闭环）。

## ADDED Requirements

### Requirement: 需求任务必须持有 Host 拥有的显式已审计状态
系统 SHALL 在需求任务首次进入 `EXECUTING` 的同一 PostgreSQL 事务内初始化 `AuditedTaskState` 第 1 版——即 `RequirementPolicyTransactionPort.consumePolicyApply` 判定 `ALLOWED` 的事务，或 `APPROVAL_RESUME` 把 `WAITING_APPROVAL` 恢复为 `EXECUTING` 的事务；任何后续写回在 head 缺失时 MUST 先以同一规则 initialize-if-absent 作为兜底。初始化内容：冻结的 `acceptanceCriteriaJson` 中每条验收标准对应一条 `REQUIREMENT` 记录，ID 为 `AC-%03d`（与 `PiAgentContextStateManager` 生成 Host 验收 TODO 使用的编号同源同序），状态 `PENDING`；同时初始化阻断门记录 `GATE-BUILD`、`GATE-STATIC`、`GATE-QA-EVIDENCE`、`GATE-WORKSPACE-INTEGRITY` 与产物记录 `ART-PR`，状态均为 `PENDING`。状态对象 MUST 持久化在 PostgreSQL（revision 追加 + head CAS），JVM 内存实现只允许用于单测。

#### Scenario: 策略放行时初始化状态
- **WHEN** `POLICY_APPLY` command 经 `consumePolicyApply` 以 `ALLOWED` 把任务写为 `EXECUTING` 并插入首个 `ROLE_EXECUTION` command
- **THEN** 同一事务内出现 `state_version=1` 的 `AuditedTaskState`，其 `REQUIREMENT` 记录数等于冻结验收标准条数，全部为 `PENDING` 且 `evidenceRefs` 为空

#### Scenario: 初始化幂等
- **WHEN** 同一任务的 `POLICY_APPLY` 消费被重放，或首个 `ROLE_EXECUTION` command 的写回发现 head 已存在
- **THEN** 不产生第二个 `state_version=1` revision，head 保持同一 `state_hash`；`contractRef` 不一致时失败关闭而不是覆盖

#### Scenario: 非需求任务不初始化
- **WHEN** 任务类型不是 `REQUIREMENT`
- **THEN** 系统不创建 `AuditedTaskState`，也不改变既有只读 BugFix 行为

### Requirement: Executor 声明只能以 UNTRUSTED 进入状态
系统 SHALL 把角色结果 JSON 中经协议校验的自报内容——`CODING_AGENT.testStatus`、`QA_AGENT.status`、各角色 `environmentNotes` 与 `facts[]`（`DECLARED`/`INFERRED`）——在该 `ROLE_EXECUTION` command 的 finalize 事务内记录为 `UNTRUSTED` claim（`kind=FACT`，`status=UNTRUSTED`，`sourceStageRunId` 指向本次 attempt）。`recordClaims` MUST NOT 把 `REQUIREMENT`、`GATE-*` 或 `ART-*` 记录写为 `COMPLETED`。`ROLE_EXECUTION:QA_AGENT` 成功（含产品补救冻结 intent 的 SUCCEEDED 命令）MUST 改为一次 `auditQaWithClaims`：同一 `finish()` 写入 UNTRUSTED claims 与 QA 审计，`UNIQUE(command_id)` 仍只允许一条 `AuditRun`。

#### Scenario: Coding 自报测试通过
- **WHEN** `CODING_AGENT` 结果为 `status=SUCCESS`、`testStatus=PASSED` 且协议校验通过
- **THEN** `GATE-BUILD` 与 `GATE-STATIC` 仍为 `PENDING`，状态新增一条 `UNTRUSTED` claim 记录 `testStatus=PASSED`

#### Scenario: QA 自报 PASSED 但尚无审计轮次
- **WHEN** `QA_AGENT` 结果为 `status=PASSED` 而本 command 尚未产生 `AuditRun`
- **THEN** 没有任何 `AC-*` 记录变为 `COMPLETED`

#### Scenario: 生产 QA 成功接线 auditQaWithClaims
- **WHEN** bounded `ROLE_EXECUTION:QA_AGENT` 成功，CURRENT `acceptanceResults` 含 `criteriaId=AC-001`、`status=PASSED`、`exitCode=0`、可解析证据，且前后工作区指纹一致
- **THEN** 该 command 的 `AuditedStateMutation` 恰好一次：`subjectRole=QA_AGENT`，`AC-001`、`GATE-QA-EVIDENCE`、`GATE-WORKSPACE-INTEGRITY` 为 `COMPLETED`，`testStatus=PASSED` 仅作为 `CLAIM-*` UNTRUSTED

#### Scenario: bounded QA 成功结果从 multiAgentStages 解包
- **WHEN** orchestrator 把已成功 Coding 的 `resultJson` 与 `multiAgentStages[]` 合并（根对象是 Coding 字段，`status=SUCCESS`，没有顶层 `stages`/`acceptanceResults`/`dockerMetadata`），QA 行的 `resultJson` 含 CURRENT 通过项与一致指纹
- **THEN** `QaSubjectExtractor` / `authoritativeQaResultJson` 仍读到 QA 对象：指纹不为 SUSPECT，`AC-*` 与 `GATE-QA-EVIDENCE`/`GATE-WORKSPACE-INTEGRITY` 可晋升；不得把 Coding 根对象当成 QA 报告

### Requirement: 只有 AuditRun 能晋升记录且必须携带可解析证据引用
系统 SHALL 只允许 Host 确定性审计器（`DeterministicAuditor`）产生 `AuditRun{completion, integrity, contractAudit, verified[], missing[], untrusted[], blockers[]}` 并据此写下一版状态。每条 `COMPLETED` 记录 MUST 携带至少一条 `EvidenceRef{auditRunId, sourceKind, uri, sha256}`，`sourceKind` 限于 `HOST_VERIFICATION`、`QA_EVIDENCE`、`HOST_ASSERTION`、`PUBLICATION_LEDGER`、`WORKSPACE_FINGERPRINT`，且 `uri` 在写入时 MUST 解析到已持久化的产物（`rd_host_verification_artifacts`、`rd_qa_evidence_objects`、`rd_requirement_publications` 或指纹回执）。状态转移遵守：`PENDING --干净证据--> COMPLETED`；`COMPLETED --新证据冲突或失效--> PENDING/UNTRUSTED`（不得静默覆盖，冲突写入 `AuditRun.untrusted[]`）；`UNTRUSTED --重新独立核验--> COMPLETED/PENDING`。

#### Scenario: 悬空证据引用被拒绝
- **WHEN** 审计器试图把某条记录写为 `COMPLETED` 而其 `EvidenceRef.uri` 无法解析到已持久化产物
- **THEN** 写回失败关闭（`IllegalStateException`），本 command 以 `RETRYABLE_TECHNICAL_FAILURE` 落盘，任务状态不变

#### Scenario: 新证据与旧完成冲突
- **WHEN** `GATE-BUILD` 已为 `COMPLETED`，后续 `HOST_VERIFY` 的 BUILD 退出码非 0
- **THEN** 新版状态把 `GATE-BUILD` 降为 `PENDING`，`AuditRun.untrusted[]` 记录冲突及新旧证据引用，不得保留旧 `COMPLETED`

### Requirement: 宿主验证必须作为独立 durable command 在生产路径生效
系统 SHALL 把 `ROLE_EXECUTION:CODING_AGENT` command 成功后的 continuation 改为 `HOST_VERIFY`（role `REQUIREMENT_DELIVERY`），而不是直接 `ROLE_EXECUTION:QA_AGENT`。`HOST_VERIFY` command 调用 `HostVerificationPort.verify`，以干净工作区退出码为唯一真值（不采信 `CODING_AGENT.testStatus`），并产生 `AuditRun`。`SUCCEEDED` 时把 `GATE-BUILD`/`GATE-STATIC` 记为 `COMPLETED`（证据引用指向 `rd_host_verification_artifacts`）并 continuation 到 `ROLE_EXECUTION:QA_AGENT`；`failureCategory=PRODUCT_DEFECT` 且已用 `HOST_VERIFY_FIX` 轮次 < 2 且 Coding 下一 `attemptNo <= 3` 时，MUST 在 `recordOutcome/finalize` 边界内冻结 remediation intent，只创建 Coding attempt，不创建 QA attempt；环境、基础设施、歧义类失败 MUST 落 `FAILED_NEEDS_HUMAN` 且不创建 Coding attempt；docs-only（以 Host 变更集分类为准）标记 `SKIPPED`，两门以 `sourceKind=HOST_VERIFICATION` 的 docs-only 判定为证据记为 `COMPLETED`。

#### Scenario: Coding 虚报通过但构建失败
- **WHEN** `CODING_AGENT` 结果 `testStatus=PASSED`，`HOST_VERIFY` 的 BUILD 步骤退出码非 0 且分类 `PRODUCT_DEFECT`
- **THEN** 不创建任何 `QA_AGENT` attempt，`GATE-BUILD` 保持 `PENDING`，创建绑定 `HOST_VERIFY_FIX` 第 1 轮的 Coding attempt，任务保持 `EXECUTING`

#### Scenario: 两轮修复后仍失败
- **WHEN** `HOST_VERIFY_FIX` 已成功 claim 两轮且第三次 `HOST_VERIFY` 仍失败
- **THEN** 不创建新 attempt，任务进入 `FAILED_NEEDS_HUMAN`，`rd_task_failure_provenance.failure_phase=HOST_VERIFY` 并绑定 `failed_verification_run_id`

#### Scenario: 分角色 bounded 路径不再绕过验证
- **WHEN** 生产通过 `RequirementDeliveryDispatchService.submit` 派发且 `boundedRolePlan(CODING_AGENT)` 关闭了 orchestrator 内 host-verify 返工
- **THEN** 验证仍由 `HOST_VERIFY` command 执行，`RequirementDeliveryDispatchServiceTest` 能证明 Coding 成功后的下一条 command 是 `HOST_VERIFY`

#### Scenario: 操作员重试歧义或环境类 HOST_VERIFY
- **WHEN** 任务因 `HOST_VERIFY` 的 `REQUIREMENT_AMBIGUITY` 或 `ENVIRONMENT` 进入 `FAILED_NEEDS_HUMAN`，操作员 `POST /retry`
- **THEN** 首个 durable command 为 `HOST_VERIFY`（role `REQUIREMENT_DELIVERY`），`policy_run_id` 继承 checkpoint 的 `sourcePolicyRunId`，不得创建新的 `CODING_AGENT` attempt；尚未终态的下游 `QA_AGENT` attempt 必须预绑定到同一 checkpoint；`PRODUCT_DEFECT` 的 Coding 补救只走 `HOST_VERIFY_FIX`

### Requirement: QA 审计的只读性由工作区指纹技术强制
系统 SHALL 在 `QA_AGENT` 容器启动前（工作区 prepare、候选补丁应用与 npm 预安装之后）与容器退出后，由 Host 通过 `RepairWorkspaceRepositoryPort.repositoryState` 计算 tracked-tree 内容指纹与 `HEAD`，并以 `QaExecutionMetadataKeys` 定义的键写入 `dockerMetadataJson`。指纹或 `HEAD` 不一致 MUST 使 `AuditRun.integrity=VIOLATION`：本次 QA 结果不得晋升任何记录，任务落 `FAILED_NEEDS_HUMAN`，provenance 记录 `INTEGRITY_VIOLATION`；指纹缺失（执行器未写入）MUST 记 `integrity=SUSPECT`，同样不得晋升。仅出现 `.gitignore` 覆盖的未跟踪构建产物（如 `.next/`、`node_modules/`）视为可隔离检查副作用，不构成违规。

#### Scenario: QA 修改了跟踪文件
- **WHEN** QA 容器退出后 tracked-tree 指纹与启动前不一致
- **THEN** `AuditRun.integrity=VIOLATION`，`GATE-WORKSPACE-INTEGRITY` 为 `BLOCKED`，所有 `AC-*` 保持原状态，任务 `FAILED_NEEDS_HUMAN`

#### Scenario: QA 只生成被忽略的构建产物
- **WHEN** QA 运行 `npm run build` 只写入 `.gitignore` 覆盖的路径，tracked-tree 指纹与 `HEAD` 均未变化
- **THEN** `AuditRun.integrity=CLEAN`，`GATE-WORKSPACE-INTEGRITY` 记为 `COMPLETED` 且证据引用指向指纹回执

#### Scenario: 指纹缺失
- **WHEN** `dockerMetadataJson` 缺少启动前或退出后指纹键
- **THEN** `AuditRun.integrity=SUSPECT`，不晋升任何记录，任务 `FAILED_NEEDS_HUMAN`

### Requirement: QA 证据必须按冻结验收标准 ID 闭环
系统 SHALL 要求 PI-v2 `QA_AGENT` 的 `acceptanceResults[]` 中每条 `scope=CURRENT` 的结果携带 `criteriaId`（形如 `AC-%03d`，必须属于冻结集合）；`REGRESSION` 结果可不带。Host 审计器按 `criteriaId` 把 `status=PASSED`、`exitCode=0` 且 `logArtifactId`/`evidenceArtifactIds` 全部解析到 `rd_qa_evidence_objects` 的结果映射为对应 `AC-*` 记录的 `COMPLETED`。未被任何 CURRENT 结果覆盖的冻结验收标准 MUST 保持 `PENDING`，`AuditRun.completion=INCOMPLETE` 并写入 `missing[]`。`criteriaId` 不属于冻结集合 MUST 在容器内预校验（`result-tool.mjs`）与 Host 校验（`QaEvidenceBundleValidator`）两处一致拒绝。提示词、bridge、Host 三处合同 MUST 同步更新，并重建 `Dockerfile` 与 `Dockerfile.qa` 两个 Pi 镜像。

#### Scenario: 全部验收标准有证据通过
- **WHEN** QA 对每条 `AC-*` 提交 CURRENT `PASSED` 结果且全部证据引用可解析
- **THEN** 全部 `AC-*` 记为 `COMPLETED`，`GATE-QA-EVIDENCE` 记为 `COMPLETED`，`AuditRun.completion=COMPLETE`

#### Scenario: GATE-QA-EVIDENCE 证据并集超过每条记录 16 条上限
- **WHEN** 多条 CURRENT `PASSED` 的 `evidenceArtifactIds` 去重后仍超过 16（浏览器 QA 常为每条 AC 各附 console/network/trace/desktop/mobile）
- **THEN** 审计器 MUST 在写入 `AuditedRecord` 前把该记录的证据截到 ≤ 16（优先保留 console/network/traces.zip/desktop/mobile 引用），仍晋升 `AC-*` 与 `GATE-QA-EVIDENCE`；MUST NOT 抛 `evidenceRefs must not exceed 16` 把诚实任务打成 `FAILED_RETRYABLE`

#### Scenario: 漏掉一条验收标准
- **WHEN** QA 报告 `status=PASSED` 但某条 `AC-*` 没有 CURRENT 结果
- **THEN** 该 `AC-*` 保持 `PENDING`，`AuditRun.completion=INCOMPLETE`，`missing[]` 含该 ID，任务不得进入 `DETERMINISTIC_REVIEW` 通过

#### Scenario: 未知 criteriaId
- **WHEN** `acceptanceResults[]` 引用了不在冻结集合中的 `criteriaId`
- **THEN** 容器内预校验与 Host 校验均拒绝该 QA 结果，按既有 QA 协议失败处理

### Requirement: 交付复核、发布与完成必须以已审计状态为唯一依据
系统 SHALL 在 `DETERMINISTIC_REVIEW` 保留既有 `RequirementDeliveryReviewer` 形状检查之外，读取 `AuditedTaskState` head 并以 `AuditedCompletionGate.supportsCompletion` 为准：全部阻断记录（`AC-*`、`GATE-*`）为 `COMPLETED` 且各有可解析证据引用、无 `BLOCKED` 阻断记录、最近 `AuditRun.integrity=CLEAN`、`contractAudit=ALIGNED`。不满足则任务 `REJECTED`，reason 列出缺口记录 ID。`PUBLICATION` 与 `COMPLETION` command MUST 在 plan 阶段重新读取 head 并 fail-closed；`COMPLETED` 的 finalize 事务 MUST 同时写 `rd_task_completion_bindings(task_id, audit_run_id, state_version, state_hash)`，`ART-PR` 以 `PUBLICATION_LEDGER` 证据记为 `COMPLETED`。任何代码路径 MUST NOT 在没有 completion binding 的情况下把需求任务写为 `COMPLETED`。

#### Scenario: Executor 虚报成功被拒绝完成
- **WHEN** 各角色结果 JSON 通过全部形状检查，但 `GATE-BUILD` 为 `PENDING` 或某条 `AC-*` 缺少证据引用
- **THEN** `DETERMINISTIC_REVIEW` 把任务写为 `REJECTED`，reason 含缺口记录 ID，不创建 `AI_REVIEW`/`PUBLICATION` command

#### Scenario: 发布后状态被降级
- **WHEN** `PUBLICATION` 成功后 head 因新证据冲突不再满足完成门
- **THEN** `COMPLETION` command 不写 `COMPLETED`，任务落 `FAILED_NEEDS_HUMAN` 并保留已确认的 PR 账本

#### Scenario: 完成绑定缺失
- **WHEN** 任何写入者试图在同一事务中不写 `rd_task_completion_bindings` 而把任务写为 `COMPLETED`
- **THEN** finalize 拒绝该 plan，任务与 command 状态不变

### Requirement: 已审计状态写回必须在 OUTCOME_RECORDED 到 FINALIZED 边界内原子且幂等
系统 SHALL 把 `AuditedStateMutation{auditRun, nextState, expectedStateVersion}` 作为 `RequirementStageExecutionPlan` schema v3 的可选字段，纳入 `RequirementStageExecutionPlanCodec` 的 canonical 编码与 digest；`RequirementStageFinalizationPort.finalize` 在同一 PostgreSQL 事务内追加 revision（`UNIQUE(task_id, state_version)`）并以 `expectedStateVersion` CAS 更新 head，然后才写任务 CAS 与 command 完成。恢复重放同一冻结 plan MUST 幂等：同 `state_version` 且同 `state_hash` 视为已应用；同 version 不同 hash MUST 拒绝并保留 `OUTCOME_RECORDED` 供人工处置。schema v1/v2 plan 解码为无写回。

#### Scenario: OUTCOME_RECORDED 后崩溃
- **WHEN** plan 已 `OUTCOME_RECORDED` 且含写回，进程在 finalize 前崩溃
- **THEN** 恢复只 `decodeOutcomePlan` 不重跑 stage，写回恰好应用一次，head 版本恰好 +1

#### Scenario: 并发写同一版本
- **WHEN** 两条 command 的 plan 都以 `expectedStateVersion=n` 试图写回
- **THEN** 只有一方成功，另一方 CAS 失败且不得写任务或 command 完成，保留可恢复的 `OUTCOME_RECORDED`

### Requirement: 最终输出与管理视图只依据已审计状态生成
系统 SHALL 在 `PUBLICATION` 生成的 PR 描述与 `REPORTING` 摘要中包含由 head 生成的「已审计验收清单」（记录 ID、状态、`auditRunId`、证据引用 URI），Agent 提交的 `prBody` 只能作为其后的叙述段并标注为未核验；并提供只读管理 API `GET /admin/rd-tasks/{taskId}/audited-state` 与 `GET /admin/rd-tasks/{taskId}/audit-runs`（双重归属校验，不返回 `prompt_snapshot`/`execution_result_json`），前端任务审计视图展示状态表与审计轮次卡。

#### Scenario: PR 描述含审计清单
- **WHEN** 任务进入 `PUBLICATION` 并创建 PR
- **THEN** PR body 首段为已审计验收清单，每条 `AC-*` 带状态与证据 URI，Agent `prBody` 在其后并标注「未核验叙述」

#### Scenario: 管理 API 归属校验
- **WHEN** 请求的 `taskId` 不属于 URL 中的 `projectId`
- **THEN** 返回 404，不泄露其他项目任务的状态

#### Scenario: 前端代理合同
- **WHEN** 前端新增对上述两个路由的请求
- **THEN** Vite proxy contract test 分别断言页面导航与嵌套 API 行为

### Requirement: 两条编排路径共用同一完成门，不存在绕过门的完成写入
系统 SHALL 让 durable command 路径（`planDeterministicReviewStage`/`planPublicationStage`/`planCompletionStage`）与 legacy 同步路径（`executeDeterministicReviewStage`/`executeCompletionStage`）调用同一 `AuditedCompletionGate` 与同一写回，`RequirementDeliveryEngine.submit()` 只允许作为测试与兼容入口，生产 HTTP/飞书入口 MUST 走 `RequirementDeliveryDispatchService.submit`。源码守卫测试 MUST 断言 `RdTaskStatus.COMPLETED` 作为需求任务目标状态只出现在上述完成阶段方法中。

#### Scenario: legacy 路径同样受门约束
- **WHEN** 通过 `RequirementDeliveryEngine.submit()` 运行且 head 不满足完成门
- **THEN** 任务不得写为 `COMPLETED`，结果与 durable 路径一致

#### Scenario: 新增完成写入者被守卫拒绝
- **WHEN** 任意新代码在完成阶段方法之外以 `RdTaskStatus.COMPLETED` 为目标写需求任务
- **THEN** 源码守卫测试失败
