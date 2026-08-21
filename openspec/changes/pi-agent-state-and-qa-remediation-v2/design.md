## Context

参见 [proposal.md](./proposal.md) 的动机。本 change 只扩展 Requirement Delivery 的 PI executor 路径，核心现状来自当前代码而不是历史计划：

- `RequirementAgentStageOrchestrator` 生成角色 Prompt并根据 QA 结果决定是否创建补救 Attempt；当前 predicate 要求 `FAILED + PRODUCT_DEFECT|REGRESSION + CODING_AGENT`，默认只允许一轮。
- `EngineRequirementExecutionProfileResolver` 把可变 execution profile 解析为 `AgentExecutionProfileSnapshot`；`AgentExecutionProfile` 当前没有 capability 字段。
- `DockerPiAgentExecutor -> rd-pi-bridge.mjs` 负责 Pi request、bridge 生命周期、状态文件和结果校验；bridge 当前能在 settled-without-submit 后发送一次 bounded recovery。
- `agent-state-projector.mjs`、`agent-state-tools.mjs` 和 `context-state-injection.mjs` 已具备 v1 状态动作与上下文替换基础，但初始 TODO 由 Node 建成空集合，Java/Node 状态模型也不完全一致。
- `AgentRoleResultValidator` 做 Host 角色结果 schema 校验，`QaEvidenceBundleValidator` 在容器退出后根据实际 manifest/output 做文件证据权威校验。
- `RequirementDeliveryDispatchService` 调用 `RequirementStageFinalizationPort.recordOutcome/finalize`；生产事务边界是 `PostgresRequirementStageFinalizationAdapter`，不是新增旁路事务。
- `RdTaskRolePromptController` 只读真实 `PROMPT_SNAPSHOT` 和角色上下文；执行概览已有部分状态 sequence/hash，但没有完整 live state、注入 provenance 或 effective-context 合同。

本设计遵守模块方向：`bootstrap -> engine/exec/rag`，`engine/exec -> rag`。PostgreSQL 是共享控制状态真值；bridge event log 和终态 artifact 是执行证据，不以 JVM 递归计数或内存 Map 作为 remediation 真值。

历史资料只作为约束来源：

- `docs/superpowers/specs/2026-07-26-pi-agent-runtime-integration-design.md`
- `docs/superpowers/specs/2026-07-28-pi-qa-protocol-and-workspace-hygiene-spec.md`
- `docs/superpowers/specs/2026-07-25-gate-retry-qa-cache-remediation-design.md`
- `docs/superpowers/specs/2026-07-14-role-prompt-evidence-view-spec.md`
- `docs/superpowers/plans/2026-08-18-pi-agent-state-and-qa-remediation-backend.md`

它们分别属于 plan/decision、历史约束或已实现规范；本 change 只把经上述当前 source/test anchors 重新核验的行为写入 delta spec。历史分类方法依据 `docs/openspec/historical-spec-provenance-audit.md`。

## Goals / Non-Goals

**Goals:**

- Java 为 capability-gated PI Attempt 建立确定性、可验证的 v2 初始状态和验收 TODO。
- Pi 运行中受控更新状态，并在每次模型上下文末尾只注入一份最新状态。
- PostgreSQL 提供跨实例最新状态/注入投影，终态 artifact 保留审计证据。
- PI QA 可以用有验收和文件证据的明确请求打回 Coding，最多两轮，并把完整反馈传给 Coding。
- PI 协议失败以结构化 receipt 最多触发一次 QA→QA retry，永不误打回 Coding。
- 所有 remediation identity、计数、target profile snapshot、command 和恢复 intent 持久化且幂等。
- 后端 API 精确展示同一 Attempt 的静态 Prompt、最近实际注入上下文和最新状态。

**Non-Goals:**

- 不修改 React/TypeScript 前端；独立交接计划由另一 Agent 实施。
- 不为 Claude Code、MODEL_ONLY 或旧 BugFix 链路增加动态状态、v2 QA 字段或两轮 remediation。
- 不暴露 Provider 对话历史、隐藏 reasoning、raw Pi session、完整工具参数或外部对象存储 URL。
- 不增加无限 follow-up、跨 runtime 自动 fallback、H2/H3 reload 或同一 Attempt 的运行时漂移。
- 不重构整个 delivery state machine，也不修改无关的 OpenViking、publication 或 checkpoint 语义。

## Decisions

### 1. Execution profile capability 是唯一启用入口

在 `rd_agent_execution_profiles` 增加 `capabilities_json JSONB NOT NULL DEFAULT '[]'::jsonb`。Java 新增闭集 `AgentRuntimeCapability`，首批只有：

- `PI_AGENT_STATE_V2`
- `PI_QA_REMEDIATION_V2`

`AgentExecutionProfile`、Admin request/view、PG row/mapper/store 和内存 store 同步增加不可变、排序去重的 capabilities。未知 capability、非字符串、或给非 PI runtime 配置 PI capability均 fail-closed。现有无条件 upsert 拆成 create-only insert 和 `UPDATE ... WHERE profile_id=? AND version=?`；更新成功后 version+1，零行更新映射 409。

`AgentExecutionProfileSnapshot` canonical payload/hash 覆盖 sorted capabilities 和 profileVersion。旧 row/snapshot 缺字段时只解释为空 capability，绝不根据 runtime/name 推断启用。

选择 capability 而不是“所有新 PI 自动启用”，是为了保持历史请求字节、工具集合和 QA predicate 的兼容性，并允许 canary/kill switch 回滚。

### 2. Java-owned `rd-agent-state/v2` 使用同一 canonical 合同

新建明确版本的 Java `AgentStateSnapshotV2` 和 `PiAgentContextStateManager`。Java 在 stage 进入 RUNNING 前生成：

- task/stage/role/attempt/runtime/profile snapshot identity；
- currentGoal、taskStartedAt、stageStartedAt、phase；
- budget availability 与冻结值；
- 每条验收标准一个不可删除 Host TODO；
- remediation/protocol retry 产生的必做项；
- bounded acceptance attachment identity/hash。

Java 与 Node 对 v2 采用 RFC 8785 JCS 兼容 canonical JSON、SHA-256 和 JSON safe-integer 约束，共享 fixtures 覆盖 Unicode、转义、非法 surrogate、hash mismatch、未知预算和边界整数。Host 把 `protocol/json/hash` 端到端传给 `DockerPiAgentExecutor` 和 bridge；启用 v2 时 bridge 不允许 fallback empty state。

每个 Host TODO 保留完整验收内容或 hash-bound attachment，不允许为满足字节上限而截断/聚合/删除责任。超过项数、单项、总 attachment 或注入上限时在 RUNNING 前 fail-closed。

### 3. 状态提交与上下文注入分离 sequence

`AgentStateProjector` 接受 bounded action：TODO rewrite/status update、fact record。candidate 在 sequence 增长前完成 schema、identity、状态迁移、Host obligation、证据、sanitizer、字段/总量和 injectability 校验；失败不改变 sequence。action identity 支持相同 payload 幂等重放，冲突重放拒绝。

`context-state-injection.mjs` 每次构建模型上下文时删除旧 `rd-agent-state` custom message，把最新已提交状态作为唯一块放在末尾。状态 sequence 与 injection sequence 独立：状态未变但新模型调用发生时，injection sequence 仍前进。

bridge 发出两个 bounded normalized events：

- `STATE_SNAPSHOT_UPDATED`：identity、state sequence、canonical hash、sanitized snapshot；
- `STATE_CONTEXT_INJECTED`：独立 injection sequence、注入引用的 state sequence/hash、Prompt hash、准确 injected block/hash、时间和幂等键。

Java event sink 不信任“bridge 已脱敏”声明；它重新校验 schema/identity/hash、再次 sanitizer 并以独立 CAS 更新 PostgreSQL live projection。相同 sequence 只允许相同 hash 幂等重放，倒退或冲突告警。投影故障只影响观测，不回写或篡改 bridge 内部状态。

### 4. 有效上下文是 Prompt 加“最后实际注入块”

新增 `AGENT_EFFECTIVE_CONTEXT` artifact 和 `rd_agent_stage_state_latest` 投影。有效上下文严格等于：

1. 该 stageRunId 的真实 `PROMPT_SNAPSHOT` safe preview；
2. 最近一次 `STATE_CONTEXT_INJECTED` 记录的准确 safe block。

不能用 latest state 重新拼装历史 injection。API 分别返回 latest state 和 effective context，并包括 source、finalized、state/injection sequence、显式 hashes、composition order、length/truncated 与 Host 计算的 stale provenance。active projection 默认 stale threshold 30s；finalized archived artifact 不按墙钟标 stale。

### 5. QA 结果 schema 和文件证据分层校验

PI-v2 QA result 增加：

- `remediationRequest { requested, targetRole, reason, bugFindingIds }`
- `bugFindings[] { id, severity, acceptanceCriteriaId, reproductionSteps, expected, actual, evidenceArtifactIds, suspectedFiles }`

容器 `result-tool.mjs` 和 Host `AgentRoleResultValidator` 镜像校验 JSON schema、枚举与交叉字段；Host `QaEvidenceBundleValidator` 在拿到实际 output/manifest 后验证 evidence 文件存在、非空、bytes/sha256 一致，且每个 finding 的 evidence 同时被对应失败 acceptance 引用。只有两层都通过，orchestrator 才收到 verified remediation decision。

PI-v2 的路由依据是 QA 显式请求和有效 finding/evidence，不再是 failureCategory allowlist。环境/flaky 等失败仍可 `requested=false` 并转人工。没有 `PI_QA_REMEDIATION_V2` 的所有 runtime 继续走原 legacy predicate 与一轮上限。

### 6. 两轮产品修复使用 durable remediation coordinator

新增 `rd_agent_remediation_rounds`：

- kind：`QA_PRODUCT_FIX | QA_PROTOCOL_RETRY`；
- task/source stage/source command/round identity；
- target Coding/QA stage、first command、profile snapshot identity；
- bounded sanitized request JSON/hash；
- status、rowVersion、lease owner/expiry 和时间；
- unique `(task_id, kind, remediation_no)` 与 `(source_stage_run_id, kind)`。

PI-v2 coordinator 允许最多两个成功 `QA_PRODUCT_FIX` claim，另受每角色 attemptNo<=3 约束。legacy `AgentWorkflowPlan.DEFAULT_QA_REMEDIATION_PASSES=1` 不改。

Coding 输入使用 `qa-remediation/request.json` 受控 attachment；Prompt 只渲染 bounded 详细摘要、固定相对路径和每个 finding 的必做 TODO，不嵌入 raw session/大日志/外部 URL。

### 7. `OUTCOME_RECORDED` 冻结 immutable intent

扩展 `RequirementStageExecutionPlan/v2`，optional `piQaRemediationIntent` 包含：

- source task/stage/command/result/fence/version；
- remediation kind、request JSON/hash；
- 预分配 target stage/command IDs 与 attemptNo；
- side-effect-free prepared target profile snapshot ID/JSON/hash；
- source/target profile ID/version/capabilities；
- optional protocol failure receipt JSON/hash。

真实入口是 `RequirementDeliveryDispatchService -> RequirementStageFinalizationPort.recordOutcome/finalize`，生产实现为 `PostgresRequirementStageFinalizationAdapter`。

`recordOutcome` 事务锁顺序固定：

1. existing finalization marker row；
2. intent 中去重后的 source/target execution profile rows，按 `profile_id ASC` `SELECT FOR UPDATE`；
3. 校验 runtime/version/capability/canonical payload；
4. CAS 写 `OUTCOME_RECORDED`。

Admin update 使用同一 profile row 的 expected-version update。Admin 先提交则 recordOutcome 发现 drift 并回滚；recordOutcome 先提交则 immutable intent 获胜，Admin 可在其后更新，但 finalization/recovery 永不再次解析或比较 live profile。

`finalize` 在现有 command→task 锁/CAS 边界内原子推进来源 stage/command/task disposition，并插入 ledger、target stages、profile snapshot rows 和首 command。任一步失败整笔回滚，不预存 orphan snapshot。command 表增加 remediation generation identity，normal/checkpoint/remediation 三类互斥；同 round 的 Coding→QA continuation 沿用 remediation identity。

dispatcher/reconciler 只从 ledger、immutable intent、snapshot 与 command 恢复；commit-before-wakeup、重复消费和 lease 过期通过已持久化 identity 收敛，不从进程内 QA JSON 推断。

### 8. 协议失败 receipt 是完整且不可歧义的事实集合

`PiProtocolFailureReceipt/v1` 使用 canonical JSON/hash，至少包含 kind、exact missing facts、resultSubmitted、`resultSubmissionSource`、`roleSchemaAccepted`、`acceptedResultDigest`、agentSettled、eventStreamTrusted、containerTerminated、三项 recovery flags、last rejection kind/digest、diagnostic artifact IDs、source identity 与时间。

只允许三类一次 QA→QA retry：

1. settled 且一次 bridge recovery 后仍无 accepted result；
2. 来自 Agent `rd_submit_result` 的 schema-accepted result 已持久化，但缺 AGENT_SETTLED；
3. settled，ROLE_SCHEMA rejection 且一次 recovery 已耗尽。

synthetic bridge result 不能冒充 Agent submit。artifact、event stream、identity/state mismatch、未知/矛盾 receipt、第二次协议失败或目标 QA profile 不合格都转人工。此 kind 永不创建 Coding Attempt。

## Risks / Trade-offs

- [Java/Node schema 漂移] → v2 明确分版、共享 fixtures、JCS bytes/hash 双边 validator；v1 只读历史 artifact。
- [状态过大或秘密进入 Prompt/API] → Host/bridge 双 sanitizer、字段/单项/总量上限、pre-commit injectability、不可截断验收责任。
- [QA 自由文本导致误打回] → explicit request、finding→failed acceptance→manifest file 三重绑定、Host 权威证据校验。
- [两轮修复形成重试风暴] → PostgreSQL unique claim、PI-v2 常量 2、角色 attempt 3硬上限、协议重试独立 kind且仅一次。
- [崩溃后重跑 QA或 profile 漂移] → `OUTCOME_RECORDED` immutable intent、预分配 identities、marker→profile ASC 锁顺序、marker 后禁读 live profile。
- [事务范围扩大导致死锁] → 保留现有 finalizer 入口；profile 只在 recordOutcome 按升序锁，Admin 不持有其他业务 row lock时更新；真实双连接 PostgreSQL 竞态测试。
- [live projection 延迟被误称最新] → Host 计算 stale provenance；state/injection 分 sequence；final artifact和 live source 显式区分。
- [迁移回滚困难] → 新列默认空、新行为 capability-gated、kill switch、legacy decoder/route 保留；不自动回填历史 profile。

## Migration Plan

1. 新建 `p18_pi_agent_state_and_remediation.sql`：execution profile capabilities、state projection、remediation ledger、command generation identity与约束；历史 capabilities 默认空。
2. 先发布能够读取空 capability/legacy plan 的 Java代码与 Host validators，新能力默认关闭。
3. 发布同步的 bridge、result-tool 和两套 Pi image；记录 image ID/digest，避免旧容器协议继续运行。
4. 通过 Admin expected-version update仅给一个测试项目的 PI profiles 加 `PI_AGENT_STATE_V2`，验证状态/API。
5. 再为同一项目 Coding/QA PI profiles 加 `PI_QA_REMEDIATION_V2`，验证两轮产品修复、一次协议重试和恢复。
6. 观察 ledger/reconciler、projection stale、协议拒绝和人工转入指标后逐 profile 扩围。

Rollback：先关闭 Host kill switch并移除新 profile capabilities（version+1），使新 Attempt 回到 legacy；运行中的 v2 Attempt继续使用冻结 snapshot/intent。数据库列/表和 v2 artifacts保留只读，不在紧急回滚中 drop或重写；需要 bridge 回滚时使用上一 image digest。

## Verification Evidence and Planned Commands

规划阶段实际运行：

- `git status --short`、`rg`/`sed` 对上述 source/test anchors 做只读链路核验；
- `openspec list/status/instructions` 确认本 change 使用 repo-local `spec-driven` schema；
- Sol/High 对后端/前端计划做新鲜只读审查，最终 verdict 为 ship；这只是设计审查，不是实现验证。

实施阶段至少运行：

```bash
cd bootstrap/src/main/resources/executor/pi && npm test
./mvnw -pl rag,engine -am -Dtest='*AgentState*,*QaRemediation*,RequirementAgentStageOrchestratorTest,AgentWorkflowPlanTest' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl exec -am -Dtest=DockerPiAgentExecutorTest,AgentRoleResultValidatorTest,QaEvidenceBundleValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest='PiAgentExecutorPropertiesTest,RdTaskRolePromptControllerTest,*Remediation*,TransactionalProxyPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest=PostgresRequirementStageFinalizationRealSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
git diff --check
```

真实 PostgreSQL 测试使用两个连接和 latch 证明 Admin update 与 recordOutcome 只有两种线性化结果且无死锁。运行态验收使用全新 PI 任务保存 role-prompts/overview HTTP、stage/ledger/command/execution-plan、两轮 remediation、state/effective-context artifact 和协议 QA→QA 证据。未取得这些证据前不得声明已上线。

### Implementation verification snapshot (2026-08-20 waimai + hy3)

Next.js 任务 `7496048851825070080` 已 `POST /stop` 为 `CANCELLED`，残留 Pi/relay 容器已停。隔离实例 OpenCode 模型改为 `hy3`（yaml `rd.ai.chat` / docker `opencode-go` / `openai-chat`，以及 `rd_model_provider_profiles.opencode-go.model_id`）。18081 已重启。

新项目 `p18-verify-waimai`（`7496050931340021760`）仓库 `wanghehe123/rd-bot-waimai-acceptance-20260624-141045`。全新任务 `7496050931679760384` 已 `COMPLETED`：四角色 attempt 1 均 SUCCEEDED，QA result `PASSED`，PR https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045/pull/27 。request `opencode-go`/`hy3`/`rd-agent-state/v2`；QA latest `sequence=44` / effective `injectionSequence=42`。remediation ledger 为空：`QA_PRODUCT_FIX` used=0 remaining=2，`QA_PROTOCOL_RETRY` used=0 remaining=1。首轮交付成功，未出现两轮产品修复、协议 QA→QA 或上限转人工。task 8.4 保持未勾选。

### Implementation verification snapshot (2026-08-20 Next.js 8.4 vehicle)

未重构 QA agent。隔离 `127.0.0.1:18081` 新建项目 `p18-verify-nextjs`（`7496048851602771968`）指向已有 npm-lock Next.js 仓库 `wanghehe123/next-js-16-sqlite-drizzle-local-kbr-20260724-001`（`package-lock.json`，自动探测生产模式 `npm run build && npm run start`）。Profile `p18-next-*` 绑定 `opencode-go` + `PI_AGENT_STATE_V2` + `PI_QA_REMEDIATION_V2`。

全新任务 `7496048851825070080`：7 条浏览器/README AC（dashboard 标记、Sidebar、仪表盘、skip-link、375px banner、`/status`、README）。启动后 reviewer `LIVE_PROJECTION` `rd-agent-state/v2`，request 仍为 `rd-pi-request/v1`；latest `sequence=6` / effective `injectionSequence=4`；Host TODO=7；`estimatedInputTokens=2408`；无 `canonical hash mismatch`；relay `opencode-go` 200。REQUIREMENT_REVIEWER attempt 1 已 SUCCEEDED，SOLUTION_ARCHITECT 进行中。未勾选 8.4。

### Implementation verification snapshot (2026-08-20 OpenCode Go live)

隔离验收实例 `127.0.0.1:18081`（p18 DB `127.0.0.1:55433/rdbot_p18_verify`，未改云上 `ragent`；供应商 `opencode-go` / `deepseek-v4-flash` / `OPENCODE_API_KEY`）。全新任务 `7495901867017375744`（标记 `p18-v2-opencode-20260820`）：

- request 仍为 `rd-pi-request/v1`，`initialAgentStateProtocol` 与 `agentStateSchemaVersion` 均为 `rd-agent-state/v2`。relay `opencode-go POST /chat/completions` 全程 `status=200`；18081 日志无 `snapshot canonical hash mismatch`。
- REQUIREMENT_REVIEWER / SOLUTION_ARCHITECT / CODING_AGENT 均 attempt 1 `SUCCEEDED`。Coding 归一化事件顺序为 `RESULT_SUBMITTED` → `AGENT_SETTLED` → `RUNTIME_STOPPED`（`resultAccepted=true`）。`estimatedInputTokens` 为整数（reviewer 1678，coding 2579），不是 `[REDACTED]`。
- Coding workspace `/tmp/rd-bot/repair-workspaces/7495901867017375744`：latest `sequence=30`，effective `injectionSequence=28` / `stateSequence=30` / `stateHash=sha256:f1283f8f84848819ec0b094c14dc0e3881ef1721f2cebca44922c42ea9c011c5`。role-prompts：reviewer/architect `LIVE_PROJECTION` `stale=false` `protocol=rd-agent-state/v2`。Host ACCEPTANCE TODO 仍为 3 条 PENDING。
- QA 在 isolated agent 启动前被 `QaNpmInstallPlan` 的 `npm install --include=dev` 以 `ERESOLVE`（`dsh-settings@0.1.0-rc.6` vs peer `^0.1.0-rc.8`）打成 `ENVIRONMENT`，任务 `FAILED_NEEDS_HUMAN`。这是环境失败直转人工，不是产品修复轮次上限。deps 容器 `rd-bot-pi-7495901867017375744-qa_agent-deps` 使用 `rd-bot/pi-agent-qa:local`，exit 1，约 8s。
- 未出现 `QA_PRODUCT_FIX` 或 `QA_PROTOCOL_RETRY` ledger 行。task 8.4 保持未勾选。

### Implementation verification snapshot (2026-08-20 protocol crack)

已实际执行并通过（本机 Docker Desktop arm64，不使用 ECS 跑 Pi）：

- `cd bootstrap/src/main/resources/executor/pi && node --test test/*.test.mjs`：109 tests，109 passed（含 request v1 + Host v2 绑定、stale schema v1、以及 `estimatedInputTokens` 不得被 redact 成 `[REDACTED]`）。
- RULE.md §十一 Java focused matrix（上一轮）：rag 11 + engine 60 + exec 70（2 skipped）+ bootstrap 124 = 265 tests，0 failures。
- `./mvnw -pl exec,bootstrap,rag,engine -am --fail-at-end test`（2026-08-20T17:24Z，约 169s，已 `unset SPRING_CONFIG_ADDITIONAL_LOCATION` 且 `RD_EXECUTOR_AGENT_RUNTIME_ENABLED=false`）：rag 353/0/0/2，engine 600/0/0/0，exec 314/0/0/2，skill 7/0/0/0。bootstrap 在 `UserAdminControllerTest` 因 `InMemoryKnowledgeMutationTransactionAdapter` 无默认构造（`knowledgeDocumentMutationEngine`）未跑完模块；不扩展 OpenViking/知识变更 wiring。先前 16:59Z 那次是 live `SPRING_CONFIG_ADDITIONAL_LOCATION` 泄漏把 `agent-runtime` 打开后的 `agentRuntimeRouter` 失败。task 8.3 保持未勾选。
- `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`：8 passed。
- 后端 `git diff --check -- . ':!frontend/**' ':!bootstrap/src/main/resources/static/admin/**'`：通过。
- 本机 `rd-bot/pi-agent:local` Id=`sha256:9744415b199a869cc2629ad295f5b2ab8cc6882daee6973c769d4c361b9b85ec` Created=2026-08-19T17:01:03Z Arch=arm64（含 schema-v2 绑定与 `*Tokens` redact 豁免）。
- 隔离验收实例 `127.0.0.1:18081`（local p18 DB `127.0.0.1:55433/rdbot_p18_verify`，未改云上 `ragent`）。全新任务 `7495883299257192448`：reviewer `agentStateSchemaVersion=rd-agent-state/v2`，`agentStateAvailable=true`，Host TODO pending=3，`STATE_CONTEXT_INJECTED` injectionSequence 1–6 与 stateHash `sha256:c441e7a318625ec2ec498df5bbfda941225da975d860ce2c20aacc48ed7958ea`。`STATE_SNAPSHOT_UPDATED` 曾因 `estimatedInputTokens` 被误脱敏导致 `snapshot canonical hash mismatch`；已用 RED/GREEN 测试修复 redact，待额度恢复后用新镜像复验投影。
- 该任务随后因 LongCat `402 too_many_requests` / Token 额度不足进入 `FAILED_NEEDS_HUMAN`。OPENCODE/DEEPSEEK 未配置。未完成两轮产品修复或协议 QA→QA。task 8.4 保持未完成。
- 本机 `rd-bot/pi-agent-qa:local` Id=`sha256:2fb6c49631901550d2497590435787bad438ceb6faff5656895b232925ab2451` Created=2026-08-19T17:27:02Z Arch=arm64；`FROM` 当前 Pi `9744415b…`，容器内 `protocol.mjs` 含 `*Tokens` 豁免；`/ms-playwright` 含 `chromium-1232`、`chromium_headless_shell-1232`、`ffmpeg-1011`。`Dockerfile.qa` 增加 `BROWSER_CACHE_IMAGE` 以免 Playwright CDN 再阻断重建。镜像重建不得单独勾选 8.4。

### Implementation verification snapshot (2026-08-20 protocol crack)

已实际执行并通过（本机 Docker Desktop arm64，不使用 ECS 跑 Pi）：

- `cd bootstrap/src/main/resources/executor/pi && node --test test/*.test.mjs`：108 tests，108 passed。含 request v1 + Host v2 绑定、stale snapshot schema v1 仍绑定。
- RULE.md §十一 Java focused matrix：rag 11 + engine 60 + exec 70（2 skipped）+ bootstrap 124 = 265 tests，0 failures，0 errors。`DockerPiAgentExecutorTest` 2 skipped 仍为 aspirational QA isolation。
- `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`：8 passed。
- 后端 `git diff --check -- . ':!frontend/**' ':!bootstrap/src/main/resources/static/admin/**'`：通过。
- 本机 `rd-bot/pi-agent:local` Id=`sha256:32f0a130e44afb12bd42e4b9dc144123a0ffbe374e6491519164b51413e45266` Created=2026-08-19T16:33:33Z Arch=arm64。容器内 `protocol.mjs` 已无 `requires request v2` gate。
- 隔离验收实例 `127.0.0.1:18081`（local p18 DB `127.0.0.1:55433/rdbot_p18_verify`，未改云上 `ragent`）。全新任务 `7495883299257192448`：reviewer `agentStateSchemaVersion=rd-agent-state/v2`，`agentStateAvailable=true`，Host TODO pending=3，`STATE_CONTEXT_INJECTED` injectionSequence 1–6 与 stateHash `sha256:c441e7a318625ec2ec498df5bbfda941225da975d860ce2c20aacc48ed7958ea`。先前 `injectionSequence must be a non-negative integer` 与 missing `agent-effective-context-latest.json` 未再出现。
- 该任务随后因 LongCat `402 too_many_requests` / Token 额度不足进入 `FAILED_NEEDS_HUMAN`（`RESULT_SUBMITTED` 后缺 `AGENT_SETTLED` 生命周期）。未完成两轮产品修复或协议 QA→QA。task 8.4 保持未完成。
- `STATE_SNAPSHOT_UPDATED` 曾告警 `snapshot canonical hash mismatch`（injection 投影成功）；未在额度恢复前当作 8.4 完成证据。
- `rd-bot/pi-agent-qa:local` 于本次 Pi base 上重建中（Playwright Chromium Headless Shell 下载缓慢）；完成前不得用旧 QA digest 勾选 8.4。

### Implementation verification snapshot (2026-08-19)

已实际执行并通过：

- `cd bootstrap/src/main/resources/executor/pi && npm test`：106 tests，106 passed，0 failed。
- RULE.md §十一 Java focused matrix（含 `RequirementDeliveryDispatchServiceTest`）：263 tests，0 failures，0 errors，2 skipped（`DockerPiAgentExecutorTest`）。覆盖 unique remNo advisory lock、`fromFrozen` jsonb recanonicalize、writer 抢号拒绝、dispatcher `decodeOutcomePlan` 以及既有 capability/state/projection/QA/remediation/API 回归。
- `./mvnw -pl bootstrap -am -Drd.integration.stage-finalization.enabled=true -Dtest=PostgresRequirementStageFinalizationRealSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test`：6 tests，0 failures，0 errors。throwaway `pgvector/pgvector:pg16` 于 `127.0.0.1:55432/rdbot_acceptance`；`PostgresClasspathSchemaInitializer` 整文件应用 `p0`/`p1`/`p4`/`p8`/`p18`。覆盖 Admin/outcome profile latch、two-source remNo `{1,2}`、same-source replay 拒绝、`decodeOutcomePlan` 重启 finalize、commit-before-wakeup 续体、Admin 抢先提交后 stale claim 拒绝。未对共享 `rdbot:5432` 执行 `scripts/bootstrap-db.sh`。task 6.8/8.5 完成。未启用 canary。
- `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`：8 items passed，0 failed。
- 后端范围 `git diff --check -- . ':!frontend/**' ':!bootstrap/src/main/resources/static/admin/**'`：通过。

已执行但尚未通过的门槛：

- `./mvnw -pl exec,bootstrap,rag,engine -am test`：rag 353/0/0/2、engine 599/0/0/0、exec 313/0/0/2、skill 7/0/0/0；bootstrap 在 `UserAdminControllerTest` 因缺少 `CodingBenchmarkTrialStore` bean 失败（仓库既有 Spring 装配债），随后 Surefire 条件报告打断完整汇总。task 8.3 保持未完成。
- 全仓 `git diff --check` 仍被本 change 范围外的 `frontend/test/roleWorkbenchModel.test.ts` EOF 空行阻断；未修改或回退该前端工作。
- 目标拓扑已对齐：ECS 只跑 postgres/redis/rustfs/openviking；Pi 镜像与 RD-Bot 只在本机。本机 Docker Desktop 29.1.3 aarch64 已恢复；本机未启动 postgres/redis/rustfs/openviking。
  - 本机 `rd-bot/pi-agent:local` Id=`sha256:7244df1e3494b1252b43162d819a6441bbdb2bc33e31dff1acf426b1b92b98ff` Created=2026-08-19T14:07:47Z Arch=arm64。`docker run --entrypoint node` 输出 `pi-ok arm64 v22.19.0`。
  - 本机 `rd-bot/pi-agent-qa:local` 已按当前 Pi base 重建：Id=`sha256:139c4a50331fd9b067703d0eb554020d2a73f694c885f35e10b359ad6d531601` Created=2026-08-19T15:35:47Z Arch=arm64；`/ms-playwright` 含 `chromium-1232`、`chromium_headless_shell-1232`、`ffmpeg-1011`。杭州 ECS 上的 amd64 镜像仅作远端备份，不用于本机执行。
  - 本机 `18080` 已用 `SPRING_PROFILES_ACTIVE=local` 连云上 `ragent`/Redis 启动（`Started RdBotApplication`，Hikari/Redisson 指向 `121.199.79.122`）。本机未跑 postgres/redis/rustfs/openviking。`/admin/index.html` 与 `/admin/model-provider-profiles` 200。`/admin/rd-tasks` 与 `/admin/overview` 在 15–30s 内无响应（OpenViking `:1933` 从本机仍 empty reply；RustFS `:9000` 无密钥时 403 属服务已通）。云上 `ragent` 仍无 p18。未创建全新 PI 任务；未启用 canary。
