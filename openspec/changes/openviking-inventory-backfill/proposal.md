## Why

WP-1～WP-5 让**新发生的**知识变更能可靠投影到 OpenViking，但存量文档仍在投影视野之外：本地 `ragent` 有 167 篇文档，其中 166 篇没有 `knowledge_external_index_bindings` 行，全部 167 篇的 `source_identity_key` 为空。对账器只扫描已有 binding 的行（`KnowledgeExternalIndexReconcileEngine.java:314-317`），管理面 `rebuild` 也要求已有 binding（`KnowledgeProjectionAdminEngine.java:165-167`），所以这 166 篇不会被任何现有机制发现——它们既不是"同步中"，也不是"失败"，而是**不可见**。

同时 `superseded_by_document_id` 列自 WP-1 起就存在，却没有任何 Java 写入路径（`KnowledgeDocument.java:32,383`），历史重复身份既无法审计也无法收敛；`(knowledge_base_id, source_identity_key)` 的 active-only 唯一索引被 `RULE.md` 与 `OpenVikingProjectionSqlPolicyTest.java:26-30` 明确禁止在审计完成前创建。

本变更补齐存量审计、限流回填与身份唯一化，让"每篇文档的投影状态"从"未知"变成"有明确分类且可断言不遗漏"。

## What Changes

- 新增只读存量审计：把每一篇文档归入穷尽且互斥的分类，并把"分类计数之和等于文档总数"作为可断言的不遗漏证明。
- 新增回填原语：为 eligible 且无 binding 的文档补 `source_identity_key`、revision、binding 与 `UPSERT_DOCUMENT`，**不重切块、不递增 `sync_version`、不覆盖已有观测状态**。
- 新增重复身份审计与操作员确认的 supersede 收敛路径；loser 的远端副本经既有 WP-4 删除语义移除，本地行与 revision 保留。
- 新增限流控制（单批上限、未收敛 outbox 上限、按知识库触发）与默认关闭的调度开关。
- 新增管理 API、Prometheus gauge 与前端「存量审计」页签。
- 审计清干净后建立 active-only 唯一身份索引，并让重复身份的新建写入显式 409 失败而不是静默造重复。

## Capabilities

### New Capabilities

- `knowledge/openviking-inventory-backfill`: 存量文档投影审计、限流回填、重复身份收敛与身份唯一化契约。

### Modified Capabilities

- `knowledge/openviking-projection-admin`: 管理面新增存量审计与回填接口；既有接口行为不变。

## Impact

- 计划与实施细节：`docs/superpowers/plans/2026-08-13-wp6-inventory-audit-and-backfill-plan.md`（属计划/决策类材料，不得直接当作主 spec 需求）。
- 上游范围定义：`docs/superpowers/plans/2026-08-13-openviking-rag-integration-implementation-plan.md` 第 15 节 WP-6。
- 主要代码范围：`rag/.../knowledge/KnowledgeDocumentMutationEngine.java`、`rag/.../knowledge/projection/**`、`bootstrap/.../persistence/{mapper,impl}/**`、`bootstrap/.../controller/admin/knowledge/KnowledgeProjectionAdminController.java`、新迁移 `p13_openviking_identity_backfill.sql`、`frontend/src/{services,pages,components}/**`。
- 主要风险：去重会删除 loser 的远端副本，因此 supersede 必须操作员确认且带 `expectedRowVersion` CAS；回填复跑必须不能把 `IN_SYNC` 的观测状态打回 `UNKNOWN`。
