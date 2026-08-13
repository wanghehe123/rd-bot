## Context

WP-1～WP-5 只覆盖"新发生的变更"。存量文档从未进入投影视野，且现有机制在结构上看不见它们：

- 对账器本地扫描来自 `bindingStore.listAll()`（`rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexReconcileEngine.java:314-317`），无绑定的文档不在集合里。
- 管理面 `rebuild` 要求已有绑定（`rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeProjectionAdminEngine.java:165-167`）。
- 本地 `ragent` 只读统计：167 篇文档、1 篇有绑定、`source_identity_key` 非空 0 篇；两篇 FEISHU 文档虽同 token 但分属不同知识库，因此当前重复分组为 0。

三条既有实现细节直接决定设计边界（均已读码核实）：

- `KnowledgeDocumentMutationEngine#indexDocument` 在 `sourceMutation=true` 时重跑 ingestion 并把 `sync_version` +1（`:456-489,499-501`）。用它回填会重切块、重算向量、销毁现有 chunk 标识与手工覆盖语义。
- 绑定写入是 `ON CONFLICT (provider, document_id) DO UPDATE`，会覆盖 `observed_*`（`bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/KnowledgeExternalIndexBindingMapper.java:29-48`），且 `projectionBinding` 恒造 `observed=UNKNOWN`（`KnowledgeDocumentMutationEngine.java:628-661`）。复用它回填会把已同步行打回未知并对全库重发。
- 文档行是无条件整行 `ON CONFLICT DO UPDATE`，没有 CAS（`bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/KnowledgeDocumentMapper.java:43-51`）。整行写回会与并发内容更新竞态并静默回滚对方。

历史资料溯源：本变更的范围来自 `docs/superpowers/plans/2026-08-13-openviking-rag-integration-implementation-plan.md` 第 15 节（**计划类**材料），实施细节见 `docs/superpowers/plans/2026-08-13-wp6-inventory-audit-and-backfill-plan.md`（同为计划类）。二者都不作为主 spec 需求来源；主 spec 只接受本轮代码与测试证据。既有约束来自 `RULE.md` §3.5.7、§3.5.8 与 `docs/superpowers/specs/2026-08-13-openviking-projection-protocol-spec.md`。

## Goals / Non-Goals

Goals：

- 让每篇文档的投影状态可判定、可求和校验，消除"不可见"这一状态。
- 在不触碰内容真值的前提下把存量文档接入既有投影收敛链路。
- 让历史重复身份可审计、可由操作员确认收敛，并最终由数据库强制活动身份唯一。

Non-Goals：

- 不改 `SourceIdentityKeys` 的 trim-only 规范化语义（`rag/src/main/java/com/wish/rd/rag/knowledge/SourceIdentityKeys.java:27-50`）。增强 URL 规范化会让既有身份键全部失效，属独立变更。
- 不做墓碑物理清理（WP-4 已显式延期，涉及外键与不可逆删除）。
- 不做跨知识库去重：身份按知识库划分，跨库同名不是重复。
- 不引入 Shadow 检索或切流（WP-7/WP-8）。

## Decisions

**回填走独立窄原语，不复用索引路径。** 聚合根新增 `backfillProjection`，事务端口新增 `commitBackfill`：窄 CAS 更新身份与当前修订 → 幂等插入修订 → 绑定 `ON CONFLICT DO NOTHING` → 入队操作。分块与向量传空集合且不删除既有分块。理由：避免上文三条事故；同时保持 `RULE.md` §3.6「组合写入必须经聚合根」不被绕过。

**跳过必须显式命名。** 回填结果是枚举而非布尔：已绑定跳过、不符合条件跳过、并发修改跳过、已应用。报告里逐类计数并给出原因文本，满足"没有静默跳过项"的退出门槛。

**进度由数据表达。** 候选查询按文档标识键集分页取"符合条件且无绑定"，回填成功后该行自然退出候选集。不引入进度检查点表：检查点会与真实数据漂移，而候选集本身就是幂等的恢复点。

**去重不自动化。** 存活文档由确定性规则（最近同步时间 → 创建时间 → 标识）**提出**，由操作员带期望行版本**确认**。选错存活文档会导致正确副本被远端删除，这类不可逆动作不接受启发式决策。

**唯一索引作为最后一道门。** 新迁移先用 `DO` 块在存在未解决重复时报错并指向管理页，再创建带 `deleted_at IS NULL AND superseded_by_document_id IS NULL` 谓词的唯一索引。同时为新建写入路径加显式重复预检 → 冲突响应，避免约束冲突以未处理异常形式泄露。`p11_openviking_projection.sql` 仍不得包含该索引，保序约束由既有 policy test 继续守卫。

**限流是硬要求。** 单批上限、未收敛操作上限、按知识库触发、自动调度默认关闭。一次性把 166 篇入队会把写放大压到与本地 mutation 事务同一张表上。

## Verification

```bash
./mvnw -q -pl rag -am -Dtest='KnowledgeInventoryAuditEngineTest,KnowledgeProjectionBackfillEngineTest,KnowledgeInventoryAuditStoreContractTest,KnowledgeMutationTransactionPortTest' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -q -pl bootstrap -Dtest='OpenVikingProjectionSqlPolicyTest,OpenVikingProductionBoundaryPolicyTest,KnowledgeProjectionAdminControllerTest,PrometheusMetricsControllerTest' -Dsurefire.failIfNoSpecifiedTests=false test
cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
```

真机验收场景与计数备份步骤见 `docs/superpowers/plans/2026-08-13-wp6-inventory-audit-and-backfill-plan.md` 第 6 节。
