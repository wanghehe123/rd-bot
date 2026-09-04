## 1. 领域契约与数据库骨架

- [x] 1.1 在 `rag/src/test/java/com/wish/rd/rag/project/memory/` 先增加失败的领域契约测试，覆盖项目强制绑定、revision lifecycle、QUARANTINED 与当前 ACTIVE head 并存、不可变 revision、单一 ACTIVE head、来源快照、canonical operation key，以及 task 引用缺失后来源仍可审计；再在 `rag/src/main/java/com/wish/rd/rag/project/memory/` 实现最小 model、Port 和 in-memory contract 实现使测试通过。
- [x] 1.2 在 `bootstrap/src/test/java/com/wish/rd/bootstrap/ProjectAgentMemorySqlPolicyTest.java` 先固定迁移契约，覆盖新迁移顺序、`rd_projects(id)` 强 FK、task/stage/artifact 的 `ON DELETE SET NULL`、逻辑身份唯一约束、revision lifecycle、head/supersedes 同 memory 复合 FK、revision/operation 幂等约束、检索与 claim 索引；再新增 `bootstrap/src/main/resources/sql/postgres/p19_project_agent_memory.sql`，不得改写既有 p0-p18 迁移。
- [x] 1.3 在 `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/ProjectAgentMemoryPostgresStoreTest.java` 先增加 PostgreSQL Store contract，覆盖严格 String-to-BIGINT 项目解析、head CAS、revision/source 原子写入、重复 operation key、lease/fencing settle 和软删除过滤；再在 `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/{entity,mapper,impl}/` 实现对应 Row、Mapper 和 Postgres adapters。
- [x] 1.4 扩展 `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresRequirementStageFinalizationRealSmokeTest.java` 的真实 PostgreSQL 场景，证明任务删除不会级联删除项目记忆来源快照、并发推进不会产生双 head、旧 fencing token 不能 settle；记录实际数据库版本和测试命令。

## 2. 可靠捕获与巩固状态机

- [x] 2.1 在 `engine/src/test/java/com/wish/rd/engine/project/memory/ProjectMemoryCandidateValidatorTest.java` 先增加失败测试，覆盖 extractor Schema/长度/敏感字段/项目/来源校验和 deterministic redaction；再在 `engine/src/main/java/com/wish/rd/engine/project/memory/` 实现 host-owned validator，确保模型输出不能决定项目、授权、source identity 或最终状态。
- [x] 2.2 在 `engine/src/test/java/com/wish/rd/engine/project/memory/ProjectMemoryPromotionPolicyTest.java` 先固化激活门槛：阶段输出只生成 CANDIDATE，失败/取消输出不能自动成为 PROCEDURAL，完整交付+QA 或管理员确认才可激活程序记忆；再实现 promotion policy。
- [x] 2.3 先扩展 `engine/src/test/java/com/wish/rd/engine/requirement/job/InMemoryRequirementStageFinalizationPortTest.java` 和 `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementStageFinalizationAdapterTest.java`，证明 stage 最终化与确定性 memory operation/`SKIPPED_BY_POLICY` marker 登记同事务、重复最终化只登记一次、`ON CONFLICT` 后核对不可变输入且 mismatch fail closed、worker 故障不回滚交付；再同步修改 `RequirementStageFinalizationPort`、in-memory port、Mapper/Row 和 Postgres adapter。
- [x] 2.4 在 `engine/src/test/java/com/wish/rd/engine/project/memory/ProjectMemoryConsolidationEngineTest.java` 先覆盖 `ADD/NOOP/UPDATE/SUPERSEDE/QUARANTINE`、等价内容补来源、CAS 失败后只重读一次、不可裁决冲突隔离、崩溃重放不重复 revision；再实现 consolidation engine 和 resolver。
- [x] 2.5 在 `engine/src/test/java/com/wish/rd/engine/project/memory/ProjectMemoryOperationWorkerTest.java` 先覆盖有界 attempt/backoff、claim lease、fencing、checkpoint、过期 owner 拒绝、终态/人工处理；再实现 worker 编排，并在 `bootstrap` 增加默认关闭、有界并发的调度配置和配置测试。
- [x] 2.6 在 `engine/src/test/java/com/wish/rd/engine/project/memory/ProjectMemoryReconciliationTest.java` 先模拟“终态 artifact 已提交但 operation 缺失”的崩溃窗口和重复扫描；再实现只补登记 deterministic operation、绝不重跑交付的 reconciliation scanner。

## 3. 有界检索、共享预算与安全注入

- [x] 3.1 在 `rag/src/test/java/com/wish/rd/rag/project/memory/ProjectMemorySearchPortTest.java` 和 PostgreSQL Store 测试中先覆盖 SQL 层的精确项目、角色、ACTIVE head、有效期、脱敏/质量谓词、candidate limit、examined-row audit；再实现有界 lexical search，禁止加载项目全部 revision 后在 JVM 过滤。
- [x] 3.2 在 `rag/src/test/java/com/wish/rd/rag/retrieval/ProjectMemoryRetrievalChannelTest.java` 先覆盖 `rd-memory://projects/{projectId}/memories/{memoryId}/revisions/{version}`、排序解释字段、失效/冲突排除和错误隔离；再实现独立 `ProjectMemoryRetrievalChannel`，不得把新记忆包装成 `WorkflowExperienceEntry`。
- [x] 3.3 扩展 `bootstrap/src/test/java/com/wish/rd/bootstrap/rag/ProjectScopedRequirementKnowledgeSearchAdapterTest.java`，先证明 knowledge、project memory、legacy experience 共享总预算，历史通道初始不超过 topK 30%，并按 source artifact/hash、content hash、logical identity 跨通道去重；再修改 `ProjectScopedRequirementKnowledgeSearchAdapter` 或其上层 aggregator。
- [x] 3.4 扩展 `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineInjectionTest.java` 和 `RequirementAgentStageOrchestratorTest.java`，先证明 `materialsWithReusableExperience()` 与 orchestrator legacy 预取不再绕过 scoped aggregator、不会重复注入且切回 legacy 可即时生效；再收敛两条旧入口。
- [x] 3.5 在 `rag/src/test/java/com/wish/rd/rag/context/ProjectMemoryUntrustedContextTest.java`、`engine/src/test/java/com/wish/rd/engine/requirement/RoleExecutionInputManifestBuilderTest.java` 和相关 role contract 测试中先固定 `UNTRUSTED_PROJECT_MEMORY` block、memory/revision/source hash 和优先级；再修改 `RoleContextBuilder`、input manifest 及宿主 prompt，证明记忆不能改变工具 allowlist、凭证/网络/审批/QA 策略或单独满足验收。

## 4. 按项目模式、Legacy 清点与 Shadow 门禁

- [x] 4.1 在 rag/bootstrap contract 测试中先覆盖独立 `capture_mode=OFF|SHADOW|ACTIVE`、`read_mode=LEGACY|SHADOW|DUAL|PRIMARY` 及 OFF/SHADOW/DUAL_READ/PRIMARY 策略映射、默认 OFF marker、项目禁用/删除时捕获和检索 fail closed、模式切换不删数据也不隐式回放旧 marker；再实现配置 Store、管理 service 和读取路由。
- [x] 4.2 在 `engine/src/test/java/com/wish/rd/engine/project/memory/LegacyExperienceInventoryTest.java` 先用固定 fixture 覆盖 eligible/duplicate/ambiguous/rejected、空/非法项目、repository 冲突、未脱敏、失败或不完整交付、重复批次幂等；再实现只清点/建 link/生成候选的迁移 service，禁止模糊记录自动激活。
- [x] 4.3 在 `engine/src/test/resources/project-memory-evaluation/` 新增版本化 corpus、queries、expected sources 和 legacy baseline，并在 `engine/src/test/java/com/wish/rd/engine/project/memory/ProjectMemoryShadowEvaluationTest.java` 先计算 cross-project leakage、Recall/Precision@K、stale/conflict、abstention、duplicate-context、source coverage、p95、examined rows、token share；门槛配置与数据集 revision 必须进入结果审计。
- [x] 4.4 在模式路由与 shadow 测试中先证明 leakage 非零或任一冻结门槛失败会阻止 PRIMARY、gate revision 不匹配会拒绝切换、单项目 canary/回滚不影响其它项目；再实现 gate binding 和高优先级安全告警。

## 5. 管理治理与显式 Purge

- [x] 5.1 在 `engine/src/test/java/com/wish/rd/engine/admin/projectmemory/ProjectMemoryAdminServiceTest.java` 先覆盖按项目查看 head/revision/source/状态/role/audit、确认/纠正/失效/软删除、expected row version 冲突、task 删除不触发 memory 删除；同时增加 fail-closed `ProjectMemoryMutationAuthorizer` contract，覆盖可信 principal、`PROJECT_MEMORY_GOVERN`、项目范围、请求体 actor 不可信和 allow/deny 动作审计，再实现 engine 管理 service 和 View model。当前部署若无可信 principal provider，mutation service 必须禁用。
- [x] 5.2 在 `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/projectmemory/ProjectMemoryAdminControllerTest.java` 先覆盖请求校验、host principal 注入、项目授权、分页、404/409/403/安全错误翻译和禁用项目只读；再实现现行 `/admin/projects/{projectId}/memories` 路径下的 controller/DTO，不暴露凭证、原始 Pi event 或无界工具输出。
- [x] 5.3 在 `frontend/test/projectMemoryAdministration.test.ts`、`projectMemoryModel.test.ts` 和 `viteProxy.test.ts` 先覆盖项目选择、状态/来源/revision 展示、冲突反馈、治理动作和 API proxy；再实现 frontend service、model、路由和管理页面，并保持现有 admin visual contract。
- [x] 5.4 在 engine/bootstrap 测试中先设计项目级 purge 的 authorize(`PROJECT_MEMORY_PURGE`) → preview → 显式确认 token → re-authorize → execute 协议，固定可信操作者、原因、项目、预计/实际行数、过期确认、动作审计、越项目拒绝和禁止由 task/project 普通删除调用；确认 token 不得替代权限。完成单独高风险评审后再实现 purge command，任何物理删除不得先于该评审和测试。
- [x] 5.5 对治理页面执行真实 HTTP 与浏览器验收：验证桌面/移动端查看、纠正/失效/软删除、并发 409、禁用项目只读和 purge 预览；在 change evidence 中引用请求/响应、数据库前后状态、console/network/trace/screenshot 证据，而不是只记录“页面可打开”。

## 6. 后续 OpenViking Projection 边界（本 change 不实现）

- [x] 6.1 在 `rag`/`bootstrap` 配置与 architecture tests 中固定本 change 不创建/启用 OpenViking memory outbox、binding、adapter 或 worker，PostgreSQL 为唯一上线 retrieval；同时固定 future projection SPI 默认关闭，OpenViking 故障不能影响本地 head 或交付。
- [ ] 6.2 PostgreSQL shadow 门禁通过后，创建独立 OpenSpec change；该 change 必须重新追踪 `KnowledgeProjectionAdminController → KnowledgeProjectionAdminEngine → stores/ports` 和 frontend `App → page → service → Vite proxy`，以当前代码/测试决定独立 owned root、URI、desired/observed、发送边界、`UNKNOWN_REMOTE_RESULT` 核验和 rebuild 方案。本 change 不运行 OpenViking memory live smoke。

## 7. 分层验证、证据与交付门禁

- [x] 7.1 运行 rag/engine 聚焦测试：`./mvnw -q -pl rag,engine -am -Dtest='*ProjectMemory*,RequirementDeliveryEngineInjectionTest,RequirementAgentStageOrchestratorTest,RoleExecutionInputManifestBuilderTest' -Dsurefire.failIfNoSpecifiedTests=false test`，记录通过数、失败和精确 commit SHA。
- [x] 7.2 更新 `PostgresClasspathSchemaInitializer` 和 `PostgresRequirementStageFinalizationRealSmokeTest` 的 schema 断言以显式加载/验证 p19；先准备仅用于测试的 `127.0.0.1:55432` PostgreSQL，再运行 `./mvnw -q -pl bootstrap -am -Drd.integration.stage-finalization.enabled=true -Dtest='*ProjectMemory*,ProjectScopedRequirementKnowledgeSearchAdapterTest,PostgresRequirementStageFinalizationAdapterTest,PostgresRequirementStageFinalizationRealSmokeTest' -Dsurefire.failIfNoSpecifiedTests=false test`。报告必须证明 real smoke 实际 executed（非 skipped），并包含重复、并发、crash replay、task delete 和 fencing 证据。
- [x] 7.3 运行 frontend contract：`cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build`；若改变 role input/bridge 资源，再运行 `npm test` 于 `bootstrap/src/main/resources/executor/pi` 及 AGENTS.md 规定的 Pi/Bootstrap 验证，并重建受影响的 Pi images 后做目标环境复验。
- [x] 7.4 用冻结 shadow 数据集执行 legacy 对比和跨项目泄漏探针；只有 leakage=0、门槛全部通过、来源覆盖与预算审计完整，才允许把指定 canary 项目从 SHADOW 提升到 DUAL_READ/PRIMARY。
- [x] 7.5 在 `proposal.md`/`design.md` 的证据段补记实际变更文件、当前代码/测试 anchors、执行命令和结果，并运行 `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`；实现、真实验证与 change artifacts 全部一致前不得 archive。
