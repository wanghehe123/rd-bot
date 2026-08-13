## Stage A：审计只读模型与写入原语

- [x] A1 先写失败测试 `rag/src/test/java/com/wish/rd/rag/knowledge/projection/KnowledgeInventoryAuditStoreContractTest.java`：十类分类顺序判定、`sum == total`、键集分页候选、重复分组与提出的存活文档、本地孤儿漂移列表；内存与 Postgres 实现共用同一套断言。
- [x] A2 新增只读端口 `rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeInventoryAuditStore.java` 与 `.model` 下的计数/分组/漂移记录；内存实现放 `.../projection/impl/`。
- [x] A3 新增 Postgres 实现与 MyBatis Mapper（`bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/{impl,mapper}/`），禁止 `JdbcTemplate`（`PersistenceImplementationPolicyTest`）；列名必须与 `p11_openviking_projection.sql`、`p0_knowledge_productionization.sql` 对齐并加列名对齐测试。
- [x] A4 先写失败测试 `rag/src/test/java/com/wish/rd/rag/knowledge/KnowledgeDocumentBackfillMutationTest.java`：回填后分块集合/分块计数/`sync_version` 不变；修订落在当前版本且同 checksum 复用；对已同步行复跑不改 `observed_*`/`row_version` 且不新增操作；并发修改返回并发跳过。
- [x] A5 扩展 `rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeMutationTransactionPort.java` 增加 `commitBackfill`，并在 `bootstrap/.../impl/PostgresKnowledgeMutationTransactionAdapter.java`（保持非 final）与内存适配器实现窄 CAS 更新 → 幂等修订 → 绑定 `ON CONFLICT DO NOTHING` → 入队操作。
- [x] A6 在 `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeDocumentMutationEngine.java` 增加 `backfillProjection` 与 `supersedeDuplicate`，返回显式结果枚举；不得调用 `indexDocument`。
- [x] A7 先写失败测试再实现 `rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeInventoryAuditEngine.java` 与 `KnowledgeProjectionBackfillEngine.java`（有界批量、未收敛上限、按知识库）。
- [x] A8 `RULE.md` 追加 3.5.9：回填不重切块/不递增版本/窄 CAS/不覆盖观测/分类穷尽/去重需操作员确认，附真实路径与验证命令。
- [x] A9 运行 `./mvnw -q -pl rag -am -Dtest='KnowledgeInventoryAuditStoreContractTest,KnowledgeInventoryAuditEngineTest,KnowledgeProjectionBackfillEngineTest,KnowledgeDocumentBackfillMutationTest,KnowledgeMutationTransactionPortTest' -Dsurefire.failIfNoSpecifiedTests=false test`。

## Stage B：管理 API、指标与装配

- [x] B1 先写失败测试扩展 `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/knowledge/KnowledgeProjectionAdminControllerTest.java`：六个新接口的响应封套、分页、409 冲突、脱敏错误。
- [x] B2 扩展 `KnowledgeProjectionAdminEngine` 与 `KnowledgeProjectionAdminController`：`GET /inventory`、`/inventory/candidates`、`/inventory/duplicates`、`/inventory/drift`、`POST /inventory/backfill`、`POST /inventory/duplicates/resolve`；全部不调远端。
- [x] B3 `PrometheusMetricsController` 增加 SQL 来源 gauge：`rd_bot_knowledge_inventory_documents_total{category}`、`rd_bot_knowledge_inventory_backfill_pending`；补列名对齐测试。
- [x] B4 `bootstrap/.../openviking/KnowledgeProjectionScheduler.java` 增加回填 tick 与配置（`rd.knowledge.projection.backfill.enabled` 默认 false、`interval-millis`、`batch-size`、`max-in-flight`、`knowledge-base-allowlist`），`application.yaml` 全部用环境变量占位。
- [x] B5 扩展 `OpenVikingProductionBoundaryPolicyTest`：新接口不得调远端写、回填不得走 `writeDocument`/`indexDocument`、resolve 必须带 `expectedRowVersion`。
- [x] B6 更新 `frontend/test/viteProxy.test.ts` 断言新 API 前缀不被 SPA 吞掉。
- [x] B7 `RULE.md` 追加管理面与指标约束（只写本阶段规则）。
- [x] B8 `KnowledgeProjectionBackfillEngine.backfillBatch` 捕获单篇失败，记为命名的 `FAILED` 并继续处理其余文档；禁止整批中止或静默跳过。

## Stage C：前端存量审计页签

- [x] C1 `frontend/src/services/openVikingKnowledgeService.ts` 增加六个接口与类型；`openVikingKnowledgePresentation.ts` 增加分类标签、严重度着色与求和校验失败文案。
- [x] C2 `frontend/src/pages/admin/knowledge/OpenVikingKnowledgePage.tsx` 新增「存量审计」页签：分类计数表与求和校验徽标、回填一批（默认 20，执行后重新拉取账本）、重复分组卡片（提出的存活文档 + 二次确认 + 回传 `expectedRowVersion`）、漂移列表。
- [x] C3 新增 `frontend/test/openVikingInventoryPresentation.test.ts` 覆盖标签映射、求和校验失败告警、resolve 请求体必须带 `expectedRowVersion`。
- [x] C4 运行 `cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build`。

## Stage D：唯一索引门与重复预检

- [x] D1 新增 `bootstrap/src/main/resources/sql/postgres/p13_openviking_identity_backfill.sql`：空串身份归一为 NULL；`DO` 块在存在未解决重复时 `RAISE EXCEPTION` 并指向管理页；建 `uk_knowledge_documents_active_identity`（谓词含 `deleted_at IS NULL AND superseded_by_document_id IS NULL`）。另含 `knowledge_source_identity_key` immutable 函数，供"有效身份"分组用。
- [x] D2 `writeDocument` 对非空身份做重复预检并抛领域异常 → HTTP 409（`KnowledgeAdminController#duplicateSourceIdentity`）；补控制器与引擎测试。另修并发首建竞态：`PostgresKnowledgeMutationTransactionAdapter` 把唯一索引冲突翻成 `DuplicateSourceIdentityException`，`writeDocumentIfChanged` 捕获后重扫并原地更新赢家（`shouldAdoptTheWinnerWhenAConcurrentWriterCreatesTheSameSourceFirst`）。
- [x] D3 更新 `bootstrap/src/test/java/com/wish/rd/bootstrap/OpenVikingProjectionSqlPolicyTest.java`：p11 仍不得含该索引；p13 必须含索引、必须含双重谓词、必须含审计守卫。
- [x] D4 更新 `RULE.md`：新增 §3.5.12「身份唯一索引门与重复新建预检」，含有效身份定义、SQL/Java 一致性要求与 p13 部署顺序。
- [x] D5 真机验收：分类穷尽、限流回填到已同步、复跑幂等、迁移在脏数据下报错（p13-guard-probe）、潜在重复被识别为 `DUPLICATE_UNRESOLVED` 且排除出回填候选、并发同源导入两次都成功且只留一行。
- [x] D6 `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`（2026-08-13：2 passed, 0 failed）。
