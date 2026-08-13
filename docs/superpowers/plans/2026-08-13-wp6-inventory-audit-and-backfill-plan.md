# WP-6：存量审计、回填与身份唯一化实施计划

> 状态：计划（2026-08-13）。上游计划 `docs/superpowers/plans/2026-08-13-openviking-rag-integration-implementation-plan.md` 第 15 节 WP-6。
> 前置：WP-1～WP-5 已实现并真机验收（最近提交 `6837cf8b`、`2752173e`）。
> OpenSpec 变更：`openspec/changes/openviking-inventory-backfill/`。本文是实施细节，delta spec 是规范来源。
> 按 `AGENTS.md`「OpenSpec spec maintenance」：本文属于**计划/决策**类材料，不得直接当作主 spec 需求；只有代码与测试证据可以进主 spec。

## 1. 目标与退出门槛

上游 WP-6 退出标准：**每个 active eligible 文档只有一个 logical identity 和 deterministic URI；没有静默跳过项。**

拆成可验证的四条：

1. **穷尽分类**：每一篇 `knowledge_documents` 行都落入且只落入一个审计分类，`sum(分类计数) == count(*)`。这是"无静默跳过"的可断言形式。
2. **可回填**：eligible 且无 binding 的文档能被限流批量补上 `source_identity_key`、revision、binding 与 `UPSERT_DOCUMENT`，最终到 version-verified `IN_SYNC`。
3. **可去重**：同一知识库同一 source identity 的多个可见文档，能按确定规则提出 survivor，由操作员确认后把 loser 标 `superseded_by_document_id` 并让远端副本消失；历史行与 revision 保留。
4. **可上锁**：审计清干净后建立 active-only 唯一身份索引，并让重复写入显式失败而不是静默造重复。

不在本 WP 范围：Shadow 检索与三层 Agent（WP-7）、切流与回滚演练（WP-8）、墓碑物理 purge（已在 WP-4 明确延期，涉及 FK 与不可逆删除，需独立设计评审）。

## 2. 现状事实（全部已核实，禁止再凭印象改）

### 2.1 数据现状（本地 `ragent`，只读查询）

| 事实 | 值 |
| --- | --- |
| `knowledge_documents` 总行数 | 167 |
| 可见 / 墓碑 / 已 superseded | 166 / 1 / 0 |
| 有 binding 的文档 | 1（WP-5 验收那篇墓碑，`projection_status=DELETED`） |
| 无 binding 的文档 | 166 |
| `source_identity_key` 非空 | **0** |
| 来源分布 | LOCAL 165（token/url 全空）、FEISHU 2（有 token/url，但分属不同知识库） |
| 当前重复组 | 0（两篇 FEISHU 的 `knowledge_base_id` 不同） |

结论：**当前没有历史重复组要解，主要工作是 166 篇的身份补齐与首次投影**；去重路径必须靠合成场景与单测钉住，真机验收用合法 API 造一组重复再解决。

### 2.2 代码现状与硬约束

| # | 事实 | 锚点 |
| --- | --- | --- |
| F1 | `SourceIdentityKeys.from` 只做 trim + type 大写，token 优先、否则 url，两者皆空返回 `""`；**没有 URL 规范化**（无 host 小写、无去尾斜杠、无去 query） | `rag/src/main/java/com/wish/rd/rag/knowledge/SourceIdentityKeys.java:27-50` |
| F2 | Postgres 侧 `blankToNull(identity)`，空身份存 NULL；`superseded_by_document_id` 是 `Long`，空即 NULL | `bootstrap/.../impl/PostgresKnowledgeDocumentStore.java:139,142` |
| F3 | p11 只有**非唯一**部分索引 `idx_knowledge_documents_identity ... WHERE source_identity_key IS NOT NULL`；policy test 明确禁止在 p11 建 active-only 唯一索引 | `p11_openviking_projection.sql:35-37`；`OpenVikingProjectionSqlPolicyTest.java:26-30` |
| F4 | `KnowledgeDocumentStore` 无分页、无 active 过滤、无计数；批量扫描没有键集游标 | `rag/.../store/KnowledgeDocumentStore.java:11-31` |
| F5 | `indexDocument(sourceMutation=true)` 会**重跑 ingestion**（重切块 + 重向量）并把 `sync_version` +1 | `KnowledgeDocumentMutationEngine.java:456-489,499-501` |
| F6 | binding upsert 是 `ON CONFLICT (provider, document_id) DO UPDATE`，**会覆盖 `observed_*`** | `KnowledgeExternalIndexBindingMapper.java:29-48` |
| F7 | `projectionBinding` 恒造 `observed=UNKNOWN` + `PENDING`，不合并已有观测 | `KnowledgeDocumentMutationEngine.java:628-661` |
| F8 | 文档行是无条件整行 `ON CONFLICT DO UPDATE`，无 CAS | `KnowledgeDocumentMapper.java:43-51` |
| F9 | 对账器本地扫描来自 `bindingStore.listAll()`，**看不见没有 binding 的文档**；`rebuild` 与 admin `rebuild` 都要求已有 binding | `KnowledgeExternalIndexReconcileEngine.java:314-317`；`KnowledgeProjectionAdminEngine.java:165-167` |
| F10 | `superseded_by_document_id` 有列有字段，**没有任何 Java 写入路径**；`copy()` 只做原值透传 | `KnowledgeDocument.java:32,383` |
| F11 | 所有 store 组合写入必须经聚合根 `KnowledgeDocumentMutationEngine` | `RULE.md` 3.6；`OpenVikingProductionBoundaryPolicyTest.java:17-40` |
| F12 | 唯一原子提交入口是 `KnowledgeMutationTransactionPort.commit(bundle)`；outbox `enqueue` 用唯一键吸收重复，终端行直接抛错 | `PostgresKnowledgeMutationTransactionAdapter.java:57-84`；`PostgresKnowledgeExternalIndexOutboxStore.java:33-59` |

由 F5～F8 直接得到三条**必须避开的事故**：

- 用 `indexDocument` 回填 = 把 166 篇重新切块、重算向量，并销毁现有 chunk id 与手工编辑（`LOCAL_ONLY_OVERRIDE` 的语义会被抹掉）。
- 用 `projectionBinding` + binding upsert 回填 = 第二次点回填就把已 `IN_SYNC` 的 `observed_*` 打回 `UNKNOWN`，并对全库重发 UPSERT。
- 整行写回文档 = 与并发内容更新竞态时静默回滚对方的写入（F8 无 CAS）。

## 3. 设计决策

**D1 回填不重切块。** 新增窄原语，只补 `source_identity_key`、`current_revision_id`、revision 行、binding、`UPSERT_DOCUMENT`；chunks/vectors/`chunk_count`/`status` 一律不动（bundle 传空 chunk 列表且 `removeStoredChunks=false`）。依据 F5。

**D2 文档行只做窄 CAS 更新。** 回填不走整行 upsert，而是 `UPDATE knowledge_documents SET source_identity_key=?, current_revision_id=?, row_version=row_version+1, updated_at=? WHERE id=? AND row_version=? AND source_identity_key IS NULL`。CAS 失败**不静默**：该文档记为 `SKIPPED_CONCURRENT_MODIFICATION` 并计入报告。依据 F8。

**D3 已有 binding 的文档绝不回填。** 双重保险：候选查询要求 `binding IS NULL`；binding 插入用 `ON CONFLICT (provider, document_id) DO NOTHING`（不要复用 F6 的覆盖式 upsert）。必测：对已 `IN_SYNC` 的行复跑回填，`observed_state`、`observed_version`、`row_version` 全部不变且不新增 outbox 行。

**D4 回填不递增 `sync_version`。** 回填不是内容变化。revision 落在文档当前 `sync_version` 上、checksum 用文档现有 `checksum`；命中 `findByDocumentIdAndChecksum` 时复用既有 revision（满足 `UNIQUE (document_id, sync_version)` 与 `UNIQUE (document_id, checksum)`）。binding 的 `desired_version` = 文档 `sync_version`。

**D5 分类穷尽且互斥。** 按下列顺序判定，先命中即归类：

| 顺序 | 分类 | 判据 |
| ---: | --- | --- |
| 1 | `TOMBSTONE` | `deleted_at IS NOT NULL` |
| 2 | `SUPERSEDED` | `superseded_by_document_id IS NOT NULL` |
| 3 | `DUPLICATE_UNRESOLVED` | 可见 + identity 非空 + 同 `(kb, identity)` 可见行数 > 1 |
| 4 | `EXCLUDED_BASE_INACTIVE` | 所属知识库 `lifecycle_status` 非 ACTIVE |
| 5 | `EXCLUDED_LOCAL_ONLY` | `local_only_override = true` |
| 6 | `EXCLUDED_EMPTY` | `chunk_count = 0` 或 `checksum` 为空 |
| 7 | `FAILED` | 有 binding 且 `projection_status ∈ {FAILED, DEAD_LETTER, NEEDS_HUMAN}` |
| 8 | `IN_SYNC` | 有 binding 且 `projection_status = IN_SYNC` |
| 9 | `PROJECTING` | 有 binding，其余非终态（含 `DELETING`/`DELETED`） |
| 10 | `PENDING_BACKFILL` | 可见、eligible、无 binding |

`sum == count(*)` 必须成立，API 与页面都要显示这个校验结果。漂移单独成表、不进主分类求和：`DRIFT_LOCAL_ORPHAN` = binding `desired_state=PRESENT` 但文档不可见（与 1/2 交叉）。

**D6 去重由操作员确认，不自动执行。** 审计按 `last_synced_at DESC → created_at DESC → id DESC` 提出 survivor（确定性，可复算）；解决动作必须带 `expectedSurvivorId` 与每个 loser 的 `expectedRowVersion`，CAS 失败返回 409。loser 处理：标 `superseded_by_document_id`（窄 CAS 更新），若 loser 有 binding 则同事务入队 `DELETE_DOCUMENT` 并把 binding desired 改 ABSENT（复用 WP-4 删除语义，含递归删除）。行、revision、chunk 全部保留。禁止把 supersede 做成自动策略：选错 survivor 会让远端删掉正确副本。

**D7 游标即数据，不引入 checkpoint 表。** 候选查询按 `id` 键集分页取"eligible 且无 binding"，回填成功后该行自然退出候选集，天然幂等可恢复。依据 F4/F9。

**D8 限流三重。** 单批上限（默认 20）、未收敛 outbox 上限（in-flight cap，默认 200，超过则本批不入队并说明原因）、按知识库触发。scheduler 独立开关默认关；操作员也能从管理页跑一批。沿用 `KnowledgeProjectionScheduler` 既有形态（`AtomicBoolean` 跳过繁忙、独立 interval）。

**D9 唯一索引是最后一道门。** 顺序不可颠倒：审计清干净 → 解决重复组 → 才建索引。`p13_openviking_identity_backfill.sql` 内先用 `DO` 块在存在未解决重复组时 `RAISE EXCEPTION` 并指出管理页路径，再建
`uk_knowledge_documents_active_identity ON (knowledge_base_id, source_identity_key) WHERE source_identity_key IS NOT NULL AND deleted_at IS NULL AND superseded_by_document_id IS NULL`。
同时 `writeDocument`（永远新建 id 的那条路径）必须对非空 identity 做显式预检并抛领域异常 → HTTP 409，而不是等唯一约束把 `DataIntegrityViolationException` 泄露成 500。

**D10 指标只从账本读。** 沿用 RULE 既有约束，新增 gauge 由 SQL 聚合提供，不用进程内计数器。

## 4. 分阶段任务

### Stage A：审计只读模型 + 回填/去重写入原语（子代理 A）

- **A1** `rag` 新增只读审计端口 `KnowledgeInventoryAuditStore`（`projection` 包）：
  - `InventoryCategoryCounts countByCategory(String knowledgeBaseId)`（按 D5 顺序，SQL 单次扫描）
  - `long countDocuments(String knowledgeBaseId)`（求和校验用）
  - `List<KnowledgeDocument> nextBackfillCandidates(String kbId, String afterDocumentId, int limit)`（键集分页，eligible 且无 binding）
  - `List<DuplicateIdentityGroup> listDuplicateGroups(String kbId, int limit)`（含成员、提出的 survivor）
  - `List<InventoryDriftEntry> listLocalOrphanDrift(String kbId, int limit)`
  - Postgres 实现放 `bootstrap/.../persistence/impl/`，Mapper 走 MyBatis 注解（禁止 `JdbcTemplate`，见 `PersistenceImplementationPolicyTest`）；内存实现放 `rag/.../projection/impl/`，两者行为必须一致（写共享合同测试）。
  - 所有新 record/enum 放 `.model` 子包（`ModelPackageIsolationPolicyTest`）。
- **A2** 聚合根新增回填原语 `KnowledgeDocumentMutationEngine#backfillProjection(String documentId, long nowEpochMillis)`，返回 `InventoryBackfillOutcome`（`APPLIED` / `SKIPPED_ALREADY_BOUND` / `SKIPPED_NOT_ELIGIBLE` / `SKIPPED_CONCURRENT_MODIFICATION`，附原因文本）。严格执行 D1/D2/D3/D4。
- **A3** 扩展事务端口：`KnowledgeMutationTransactionPort#commitBackfill(KnowledgeProjectionBackfillBundle)`，一个事务内做窄 CAS 更新 → revision 插入（幂等）→ binding `ON CONFLICT DO NOTHING` → outbox enqueue；返回是否 applied。Postgres 适配器保持非 `final`（`TransactionalProxyPolicyTest`）。内存适配器同语义（含 CAS 失败分支）。
- **A4** 聚合根新增去重原语 `#supersedeDuplicate(String survivorId, List<SupersedeTarget> losers, long now)`：窄 CAS 标 superseded；loser 有 binding 时同事务 binding→ABSENT + `DELETE_DOCUMENT` 入队。任一 CAS 失败整体不提交并返回冲突。
- **A5** `rag` 新增 `KnowledgeInventoryAuditEngine`（只读聚合 + 报告）与 `KnowledgeProjectionBackfillEngine`（限流批处理，D7/D8）。后者只调 A2，不直接碰 store。
- **A6** RULE.md 追加 3.5.9 节（只写本阶段规则）：回填不重切块/不递增版本/窄 CAS/不覆盖 observed/分类穷尽/去重需操作员确认。给出真实路径与验证命令。
- **A7** 必测（新增测试类，先写测试）：
  - 分类穷尽：随机构造 N 篇覆盖 10 类，断言 `sum == N`，且顺序判定符合 D5。
  - 回填不动 chunk：回填后 chunk id 集合、`chunk_count`、向量数量不变。
  - 回填不递增 `sync_version`；revision 落在当前版本；同 checksum 复用既有 revision。
  - **复跑幂等**：对已 `IN_SYNC` 行复跑，`observed_state/observed_version/row_version` 不变、outbox 不增（钉死 F6/F7 事故）。
  - CAS 失败不静默：并发改动后回填返回 `SKIPPED_CONCURRENT_MODIFICATION` 并出现在报告里。
  - 去重：三成员组按 D6 规则选出 survivor；loser 标 superseded 且有 binding 者入队 `DELETE_DOCUMENT`；`expectedRowVersion` 不匹配整体不提交。
  - 内存/Postgres 审计合同一致（SQL 列名与迁移对齐，仿 `ProjectionMetrics` 的列名对齐策略测试）。

### Stage B：管理 API + 指标 + 装配（子代理 B）

API 全部挂在既有前缀 `/admin/knowledge-base/{knowledgeBaseId}/openviking` 下，沿用 `DataResponse<T>` / `PageView` / 错误只含脱敏 `message` 的约定：

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/inventory` | 分类计数 + 总数 + `sumMatchesTotal` 布尔 + in-flight 与候选剩余 |
| GET | `/inventory/candidates?after=&size=` | 待回填清单（键集分页） |
| GET | `/inventory/duplicates?size=` | 重复组与提出的 survivor |
| GET | `/inventory/drift?size=` | `DRIFT_LOCAL_ORPHAN` 列表 |
| POST | `/inventory/backfill` | body `{limit}`，跑一批，返回逐项 outcome 计数与被跳过的原因 |
| POST | `/inventory/duplicates/resolve` | body `{identityKey, survivorDocumentId, losers:[{documentId,expectedRowVersion}]}`，CAS 失败 409 |

- **B1** `KnowledgeProjectionAdminEngine` 扩展只读聚合与两个动作；Controller 禁止直接引用 `OpenVikingHttpExchange`，本阶段所有接口**都不调远端**（回填只入队）。
- **B2** `PrometheusMetricsController` 新增 gauge（SQL 来源）：`rd_bot_knowledge_inventory_documents_total{category=...}`、`rd_bot_knowledge_inventory_backfill_pending`。补列名对齐测试。
- **B3** scheduler 装配：`rd.knowledge.projection.backfill.enabled`（默认 false）、`.interval-millis`（默认 60000）、`.batch-size`（默认 20）、`.max-in-flight`（默认 200）、`.knowledge-base-allowlist`（默认空 = 不自动跑）。`application.yaml` 全部走环境变量占位。
- **B4** 扩展 `OpenVikingProductionBoundaryPolicyTest`：新接口不得调远端写；backfill 不得走 `writeDocument`/`indexDocument`；resolve 必须带 `expectedRowVersion`。
- **B5** `AdminFrontendController` / SPA 路由无需新增（沿用 `/admin/knowledge/{kbId}/openviking`），但要确认新 API 前缀不被 SPA 吞掉，更新 `frontend/test/viteProxy.test.ts` 断言。
- **B6** RULE.md 追加管理面与指标约束（只写本阶段规则，避免与 A6 冲突）。

### Stage C：前端存量审计页签（子代理 C）

- **C1** `openVikingKnowledgeService.ts` 增加六个新接口与类型；`openVikingKnowledgePresentation.ts` 增加分类中文标签、分类严重度着色、`sumMatchesTotal` 失败时的显著告警文案。
- **C2** 页面新增「存量审计」页签：分类计数表（含总数与求和校验徽标）、待回填计数与「回填一批」（可填批量，默认 20，执行后重新拉取账本，不以 Toast 为成功依据）、重复组卡片（成员列表 + 提出的 survivor + 「确认解决」二次确认对话框，提交时回传 `expectedRowVersion`）、漂移列表。
- **C3** 测试：`frontend/test/openVikingInventory*.test.ts` 覆盖分类标签映射、求和校验失败时的告警、resolve 请求体构造（必须带 expectedRowVersion）；更新 `viteProxy.test.ts`；`npm run typecheck` 与 `npm run build` 必须通过。

### Stage D：唯一索引门与重复预检（我本人执行，最后做）

- **D1** `p13_openviking_identity_backfill.sql`：防御性 `source_identity_key = '' → NULL`；`DO` 块在存在未解决重复组时 `RAISE EXCEPTION` 并指出管理页；建 active-only 唯一索引。
- **D2** `writeDocument` 对非空 identity 做重复预检 → 领域异常 → 409；补测试。
- **D3** 更新 `OpenVikingProjectionSqlPolicyTest`：p11 仍不得含该索引（保序），p13 必须含且谓词必须同时包含 `deleted_at IS NULL` 与 `superseded_by_document_id IS NULL` 且带审计守卫。
- **D4** 更新 `RULE.md` 3.5.7/3.5.8 中两处「禁止在 WP-6 审计完成前创建唯一索引」，改为记录已完成条件与守卫位置。

## 5. 验证命令

```bash
# rag 聚焦
./mvnw -q -pl rag -am -Dtest='KnowledgeInventoryAuditEngineTest,KnowledgeProjectionBackfillEngineTest,KnowledgeInventoryAuditStoreContractTest,KnowledgeDocumentIdentityMutationTest,KnowledgeMutationTransactionPortTest' -Dsurefire.failIfNoSpecifiedTests=false test

# bootstrap 聚焦（多个测试用逗号分隔，用 + 会静默匹配不到）
./mvnw -q -pl bootstrap -Dtest='OpenVikingProjectionSqlPolicyTest,OpenVikingProductionBoundaryPolicyTest,KnowledgeProjectionAdminControllerTest,PrometheusMetricsControllerTest,ImplementationPackageIsolationPolicyTest,ModelPackageIsolationPolicyTest,TransactionalProxyPolicyTest,PersistenceImplementationPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false test

# 前端
cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build

# 规范
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
```

## 6. 真机验收场景（Stage D 之后）

1. 打开投影页「存量审计」，确认 167 篇穷尽分类且求和校验通过。
2. 触发 `limit=5` 回填，观察 5 篇 `PENDING → SUBMITTED → IN_SYNC`（真机 OpenViking 容器），其余保持 `PENDING_BACKFILL`。
3. 再触发一次回填，验证已 `IN_SYNC` 的 5 篇 `observed_*` 与 `row_version` 不变、outbox 不增（幂等）。
4. 用合法导入 API 在同一知识库对同一飞书 token 写两次造出重复组 → 审计出现 `DUPLICATE_UNRESOLVED` 与提出的 survivor → 确认解决 → loser `superseded`、远端副本被递归删除、`fs/attrs` 返回 `NOT_FOUND`。
5. 在仍有未解决重复时应用 p13，确认迁移**明确报错**并指出管理页；解决后再应用，索引建成。
6. 索引生效后，再次尝试对同一 identity 走 `writeDocument`，确认返回 409 而非 500。
7. 计数验证：回填前后 `knowledge_documents` 行数不变、`knowledge_document_revisions` 只增、`knowledge_external_index_bindings` 只增；执行前后各做一次
   `pg_dump -t knowledge_documents -t knowledge_document_revisions -t knowledge_external_index_bindings` 备份并记录路径。

## 7. 风险与显式不做

- **误删风险**：去重会让 loser 的远端副本被删。因此 supersede 必须操作员确认 + CAS，且 survivor 由确定性规则提出而非模型判断。
- **写放大**：166 篇一次性入队会把 outbox 压到 worker 上。因此 in-flight cap 与批量上限是硬要求，默认关闭自动 scheduler。
- **手工覆盖语义**：`local_only_override` 文档不自动投影，只归类上报，等人决策。不得为了"分类好看"把它塞进回填队列。
- **不做**：URL 规范化增强（F1 的 trim-only 语义保持不变；改动会让既有 identity 全部失效，属独立变更）、墓碑物理 purge、跨知识库去重（identity 按 KB 划分，跨库同名不是重复）。
