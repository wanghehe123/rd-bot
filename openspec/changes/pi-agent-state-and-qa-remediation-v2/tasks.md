## 1. Execution Profile Capability Foundation

- [x] 1.1 先在 `rag/src/test/.../AgentExecutionProfileServiceTest.java` 与内存 store 测试写 RED：unknown/non-PI capability 拒绝、排序去重、expected-version 冲突和 version+1；再新增 `AgentRuntimeCapability` 并扩展 `AgentExecutionProfile`、service/store，运行 `./mvnw -pl rag -Dtest=AgentExecutionProfileServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [x] 1.2 先在 `bootstrap/src/test/.../AgentExecutionProfileAdminControllerTest.java` 和 PostgreSQL store 测试写 RED：Admin request/view capabilities、409、旧行默认空；再扩展 row/mapper/store/controller，并把无条件 upsert 拆为 create insert + expected-version update，运行对应 bootstrap focused tests。
- [x] 1.3 先在 `EngineRequirementExecutionProfileResolverTest` 写 RED：sorted capabilities/profileVersion 进入 snapshot canonical JSON/hash且旧 snapshot解码为空；再扩展 `AgentExecutionProfileSnapshot`、resolver/codec，运行 `./mvnw -pl bootstrap -am -Dtest=EngineRequirementExecutionProfileResolverTest -Dsurefire.failIfNoSpecifiedTests=false test`。

## 2. Java-Owned PI State Protocol

- [x] 2.1 建立 Java/Node 共用 `rd-agent-state/v2` fixtures；先写 RED 覆盖 JCS Unicode/转义/safe integer/非法 surrogate/hash mismatch/未知预算，再实现明确版本的 Java/Node codec，分别运行 focused Maven test 与 `cd bootstrap/src/main/resources/executor/pi && npm test`。
- [x] 2.2 先写 `PiAgentContextStateManagerTest` RED：角色目标、任务/阶段时间、phase、预算 availability、每条验收独立 Host TODO、remediation TODO、稳定 ID/hash；再新增 `PiAgentContextStateManager` 和 v2 model，运行 engine focused test。
- [x] 2.3 先写 RED：Host obligation 超 32、单项/总 attachment超限、无法安全注入时在 RUNNING 前失败且不截断；再实现 bounded acceptance attachment和 pre-dispatch validation，运行 engine focused test。
- [x] 2.4 先扩展 `RequirementExecutionRequest`/adapter/executor 测试为 RED，证明 `initialAgentStateProtocol/Json/Hash` 端到端传到 PI request且非 PI/无 capability PI 字节不变；再实现 Java→`EngineRequirementExecutorAdapter`→`DockerPiAgentExecutor`传输并运行 engine/exec/bootstrap focused tests。

## 3. Pi Bridge State Actions and Injection

- [x] 3.1 先在 `bootstrap/src/main/resources/executor/pi/test` 写 RED：启用 v2 时缺失/非法 Host initial state必须拒绝且不得 fallback empty；再修改 `protocol.mjs`、`rd-pi-bridge.mjs`、`agent-state-projector.mjs`，运行 `npm test`。
- [x] 3.2 先写 Node RED：Host TODO不可删除、DONE需本验收 evidence、非法迁移/越界/secret/不可注入 candidate不增长 sequence、相同 action幂等且冲突重放拒绝；再实现 `agent-state-tools.mjs`/projector 的 pre-commit校验，运行 `npm test`。
- [x] 3.3 先写 Node RED：每次上下文只保留一份最新状态且位于末尾，state不变但新注入使 injectionSequence前进并产生显式 block hash；再实现 `context-state-injection.mjs`和 normalized events，运行 `npm test`。
- [x] 3.4 先扩展 `DockerPiAgentExecutorTest` RED：收集 v2 state/effective-context artifacts、identity/hash mismatch失败、无 capability PI保持现状；再修改 `DockerPiAgentExecutor`、`PiWorkspaceArtifactCollector`、`RepairArtifactType`，运行 `./mvnw -pl exec -am -Dtest=DockerPiAgentExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test`。

## 4. Live State Projection and Read-Only Audit API

- [x] 4.1 先写 rag/engine port tests RED：state/injection独立单调 CAS、同 sequence同 hash幂等、冲突/倒退拒绝、finalized；再新增 live projection model/store port和内存实现，运行 focused tests。
- [x] 4.2 先写 PostgreSQL adapter/policy tests RED：`rd_agent_stage_state_latest`字段、外键/check、state/injection CAS和 stale不持久化；再在 `p18_pi_agent_state_and_remediation.sql`及 bootstrap row/mapper/store实现，运行 bootstrap focused tests。
- [x] 4.3 先写 normalized event sink tests RED：Host重新校验 canonical hash/identity、再次 sanitizer、projection写失败只告警不改变 Agent状态；再把 sink接入 Pi normalized events，运行 focused Maven tests。
- [x] 4.4 先扩展 `RdTaskRolePromptControllerTest` 和 `RdTaskExecutionOverviewControllerTest`为 RED：同 stage精确绑定、latest vs injected sequence、explicit injectedBlockHash、injection-only刷新、active stale/finalized non-stale、legacy unavailable；再实现 Controller/view和 artifact回退，运行 controller focused tests。

## 5. PI QA Remediation Result Contract

- [x] 5.1 先在 Node `result-tool` tests写 RED：`remediationRequest`/`bugFindings` schema、FAILED交叉字段、requested target/IDs、非FAILED禁用；再实现 PI capability-gated容器 pre-validation，运行 `npm test`。
- [x] 5.2 先在 `AgentRoleResultValidatorTest`写相同 fixture RED，再扩展 Host schema validator并保持 legacy contract不变，运行 `./mvnw -pl exec -am -Dtest=AgentRoleResultValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [x] 5.3 先在 `QaEvidenceBundleValidatorTest`写 RED：finding关联失败 acceptance、evidence双向引用、文件存在/非空/bytes/sha256一致；再实现权威 output/manifest验证并让 adapter只传 verified decision，运行 `./mvnw -pl exec -am -Dtest=QaEvidenceBundleValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [x] 5.4 先在 `RequirementAgentStageOrchestratorTest`写 RED：PI-v2任意 failureCategory +有效显式请求可路由，环境/flaky requested=false不路由，伪造请求拒绝，legacy predicate不变；再拆分 legacy/v2 decision，运行 engine focused test。

## 6. Durable Remediation Coordinator and Finalization

- [x] 6.1 先写 migration policy tests RED：`rd_agent_remediation_rounds` unique/check/FK/lease/request hash字段、command remediation generation identity、profile capabilities列；再完成 `p18_pi_agent_state_and_remediation.sql`和 SQL README，运行 bootstrap migration policy tests。
- [x] 6.2 先写 engine model/store tests RED：同 source幂等、round kind独立、PI产品修复最多2、协议重试最多1、attemptNo<=3；再新增 remediation model/store/coordinator port与内存实现，运行 rag/engine focused tests。
- [x] 6.3 先写 `RequirementStageExecutionPlan` codec RED：v1兼容、v2 immutable intent完整 round/request/source/预分配 IDs/target canonical snapshots/profile claims/optional receipt并验证 hash；再扩展 model/codec，运行 engine/bootstrap focused tests。
- [x] 6.4 先写 resolver tests RED：`prepareSnapshot`对预分配 target IDs无副作用、非PI/缺 capability失败、真正 snapshot row尚不存在；再实现 side-effect-free prepare API，运行 resolver focused tests。
- [x] 6.5 先扩展 `PostgresRequirementStageFinalizationAdapterTest`为 RED：marker→去重 profile_id升序锁、version/runtime/capability漂移回滚、marker后禁止 live profile重查；再扩展 `RequirementStageFinalizationPort`和生产 adapter的 `recordOutcome`，运行 focused test。
- [x] 6.6 先写 finalization transaction RED：来源 disposition、task保持EXECUTING、ledger/target stages/snapshot rows/first command原子插入，任一步失败全部回滚且无 orphan；再扩展 `PostgresRequirementStageFinalizationAdapter.finalize`和相关 mapper，运行 focused test。
- [x] 6.7 先写 command/reconciler RED：normal/checkpoint/remediation identity互斥、Coding→QA沿用 round、commit-before-wakeup、重复消费、过期lease和重启恢复不增轮次；再实现 dispatcher command generation与ledger reconciler，运行 `RequirementDeliveryDispatchServiceTest`及 focused persistence tests。
- [x] 6.8 在 `PostgresRequirementStageFinalizationRealSmokeTest`先写两个真实 connection/latch RED：Admin先提交则无 intent，recordOutcome先提交则恢复用原 snapshot，两个以上 profiles按ID升序且无死锁；再完成 mapper锁查询和事务装配，运行该真实 PostgreSQL test。

## 7. Coding Remediation Package and Protocol Retry

- [x] 7.1 先写 package builder tests RED：bounded sanitized `qa-remediation/request.json`、request hash、finding逐项Prompt、固定相对路径、secret/外部URL/超限失败；再替换一行 QA remediation摘要并接入 Coding input attachment，运行 engine/bootstrap focused tests。
- [x] 7.2 先为 `PiProtocolFailureReceipt/v1`写 Java/Node fixture RED：exact missing facts、submission source、roleSchemaAccepted、accepted/rejection digest、recovery flags、identity/canonical hash；再实现 bridge receipt和Host validator，运行 `npm test`与exec focused tests。
- [x] 7.3 先写 orchestrator/coordinator RED：三个eligible kind只创建一次 QA→QA，synthetic result/矛盾 receipt/artifact-event-identity failure/第二次失败转人工且Coding attempt不变；再实现 `QA_PROTOCOL_RETRY`路由和受控上一轮协议Prompt，运行 engine focused tests。
- [x] 7.4 先写告警/API view tests RED：remediation kind、round、source stage、计划/已占用/剩余上限和人工原因可见；再更新飞书/工作台后端消息与只读views，运行 bootstrap focused tests。

## 8. Compatibility, Verification, and Release Gates

- [x] 8.1 增加兼容矩阵 tests：历史/新无 capability PI、Claude Code、MODEL_ONLY和旧 BugFix的request/state tools/effective-context/legacy QA一轮行为不变；运行对应 engine/exec/bootstrap suites。
- [x] 8.2 运行全部聚焦命令：Pi `npm test`，rag/engine state+remediation tests，exec executor/result/evidence tests，bootstrap controller/finalizer/remediation/config tests；修复任何回归且把最终精确类名更新进 change验证记录。
- [ ] 8.3 运行 `./mvnw -pl exec,bootstrap,rag,engine -am test`、`OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`和`git diff --check`；记录测试数、失败数、耗时和未运行原因。
- [ ] 8.4 重建 `bootstrap/src/main/resources/executor/pi/Dockerfile`与`Dockerfile.qa`镜像并记录ID/digest；用全新 PI任务验证首次状态、TODO更新、effective/latest差异、两轮产品修复、一次协议QA→QA及上限人工转入。
- [x] 8.5 用真实 PostgreSQL保存 same-source replay、two-source concurrency、`OUTCOME_RECORDED`前/后profile竞态、commit-before-wakeup与重启恢复证据；只在这些证据齐全后启用canary profiles。
- [x] 8.6 更新 `RULE.md`追加本 change 已验证的真实路径、协议护栏和精确验证命令，并运行 `openspec status --change pi-agent-state-and-qa-remediation-v2`确认所有实际完成任务已勾选；不得提前修改 main specs或归档。
