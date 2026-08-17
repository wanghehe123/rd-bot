# WP-4/WP-5：删除收敛、死信恢复、Reconciler 与管理面实施计划

> **For agentic workers:** 按任务逐条 TDD 实施（先写失败测试，再最小实现，跑绿后提交）。
> 每完成一个 Task 用一个聚焦 commit。禁止一次性大提交。
> 实施前必须通读：`RULE.md` §3.5.6～§3.5.9、§3.6，
> `docs/superpowers/specs/2026-08-13-openviking-projection-protocol-spec.md`（冻结契约），
> `docs/superpowers/plans/2026-08-13-openviking-rag-integration-implementation-plan.md` §7/§8/§10/§13。

**Goal:** 让删除/禁用文档收敛到远端墓碑（不可复活），死信可人工恢复，漂移可对账发现；
并交付管理 API 与 `/admin/knowledge/:kbId/openviking` 前端页面，重启后完整可观测、可操作。

**Architecture:** 沿用 WP-3 的账本模型：所有远端意图都是 outbox 行，Worker 只做提交侧，
Poller 只做查询收敛，settle 经 `KnowledgeProjectionSettlePort` 与 binding 观测同事务提交。
WP-4 在同一状态机上实现 `DELETE_DOCUMENT`/`REBUILD_DOCUMENT` 派发、防复活排序、死信 requeue 与
Reconciler 扫描。WP-5 的管理 API 全部只读账本或复用既有原语（enqueue/settle/verify），
Controller 永不直接发 REST。前端是既有 React18+Vite+Radix+Tailwind 管理台里的新页面。

**Tech Stack:** Java 21 + Spring Boot + MyBatis（bootstrap），纯 Java 引擎（rag），
React 18 + TypeScript + Vite + react-router 6 + Radix UI + Tailwind + axios + zustand（frontend）。

---

## 0. 不可破坏约束（违反任何一条 = 实施失败）

1. **发送边界**（RULE.md 3.5.9）：`remote_operation_id` 先入库再发 HTTP；越过边界的行永不回提交路径。
   删除也一样：`DELETE /api/v1/fs` 发出前必须先 settle 发送意图。
2. **查询收敛**：`UNKNOWN_REMOTE_RESULT` 只能靠只读查询（`inspectTask`/`verifyResource`/`inspectResource`）收敛，
   禁止重发。锚在发送时刻的墙钟截止是唯一截止（不要再加第二个时钟，理由见 RULE.md 3.5.9）。
3. **版本排序防复活**（plan §7.2 行 430）：同 `(provider, documentId)` 有行处于
   `SUBMITTED/WAITING_REMOTE/UNKNOWN_REMOTE_RESULT/VERIFYING` 时，更高版本（含 DELETE）只能排队等待；
   晚完成的低版本 op 核验时发现 `binding.desiredVersion != op.syncVersion` 只能记 `DRIFTED`/`SUPERSEDED`，
   永远不能把 binding 推回 `IN_SYNC` 或复活已删除文档。
4. **desired 列归属**：Worker/Poller/Reconciler/Admin 只能经 `updateObservationIfVersionMatches` 写
   `observed_*`/`projection_status`；`desired_*` 只属于本地 mutation 事务。
5. **owned root**：一切远端写（含删除、递归删除）前必须校验目标 URI 以该 KB 的 owned root 为前缀；
   foreign ownership 的资源只告警展示，永不覆盖/删除。
6. **脱敏**：所有落库/日志/DTO 错误信息过 `OpenVikingErrorTranslator.safeMessage`；
   API key 只从环境变量读；DTO 不透传原始 OpenViking 响应。
7. **默认关闭**：新调度器（Reconciler、purge）与投影一样默认 OFF，配置模式对齐
   `rd.knowledge.projection.*`。
8. **包位置**：record/enum 进 `model/`，接口实现进 `impl/`（聚合根豁免见
   `ImplementationPackageIsolationPolicyTest.AGGREGATE_ROOT_EXEMPTIONS`）。
9. **前端**（RULE.md 行 410/411/431）：新增 `/admin/*` 请求必须同步更新 `frontend/test/viteProxy.test.ts`
   的页面导航与嵌套 API 断言；提交前跑全部 Node 测试 + `npm run typecheck` + `npm run build`。
10. **RULE.md 是受保护文件**：只能追加/修订，不得删除既有条款。

冻结契约要点（来自 `OpenVikingRealContractSmokeTest`，禁止臆造端点）：

- 删除：`DELETE /api/v1/fs?uri=<uri>&recursive=<bool>`，响应 `result.estimated_deleted_count`；
  幂等（重复删除 200 + count=0）。
- 列目录：`GET /api/v1/fs/ls?uri=<uri>`。
- 属性：`GET /api/v1/fs/attrs?uri=<uri>`（`rd.owner/rd.kb_id/rd.doc_id/rd.sync_version/rd.checksum` tags）。
- 任务：`GET /api/v1/tasks/{taskId}`；readiness：`GET /ready`（无 result 包裹）。

---

## Stage A：WP-4 删除、防复活、死信恢复与 Reconciler（纯后端）

### Task A1：端口扩展 removeResource / inspectResource / listTree

**Files:**
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/ExternalKnowledgeIndexPort.java`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/model/ExternalKnowledgeRemoval.java`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/model/ExternalResourceProbe.java`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/model/ExternalTreeListing.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/impl/OpenVikingRestIndexAdapter.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/impl/DisabledExternalKnowledgeIndexPort.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/OpenVikingHttpExchange.java`
  （补 `delete(String path, Map<String,String> query)`，实现进 `JdkOpenVikingHttpExchange`）
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/openviking/OpenVikingRestIndexAdapterTest.java`

端口签名（provider-neutral，禁止泄露 HTTP DTO）：

```java
ExternalKnowledgeRemoval removeResource(String remoteUri, boolean recursive, String expectedOwnedRoot);
ExternalResourceProbe inspectResource(String remoteUri);
ExternalTreeListing listTree(String ownedRootUri);
```

- `ExternalKnowledgeRemoval(boolean removed, int deletedCount, ExternalIndexFailureClass failureClass, String errorCode, String errorMessage, boolean requestIssued)`。
- `ExternalResourceProbe(boolean exists, Map<String,String> tags, ExternalIndexFailureClass failureClass, ...)`：
  基于 `fs/attrs`（404 => exists=false，不是失败）。
- `ExternalTreeListing(List<Entry> entries, failureClass...)`，`Entry(String uri, String name, boolean directory, String owner)`。
- 适配器内：uri 不在 `expectedOwnedRoot` 前缀下 => 不发请求，直接 `CONFIGURATION_BLOCKED`。
- `DisabledExternalKnowledgeIndexPort` 全部返回 `CONFIGURATION_BLOCKED`。

必测（adapter 单测，mock `OpenVikingHttpExchange`，fixture 用契约冒烟里的真实形状）：
- 删除成功返回 removed=true 且 deletedCount 来自 `result.estimated_deleted_count`；
- 重复删除（count=0）仍 removed=true（幂等语义）；
- 越界 URI 不发请求（验证 exchange 零调用）且 `CONFIGURATION_BLOCKED`；
- 传输失败 => `requestIssued` 如实透传（这决定 UNKNOWN 还是 RETRY）；
- `inspectResource` 404 => exists=false 且无失败分类。

验证：`./mvnw -pl bootstrap -am -Dtest=OpenVikingRestIndexAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

### Task A2：Worker 派发 DELETE_DOCUMENT / REBUILD_DOCUMENT

**Files:**
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexSyncEngine.java`
- Test: `rag/src/test/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexSyncEngineTest.java`

行为契约：
- `DELETE_DOCUMENT`：claim 后先做与 UPSERT 相同的前置检查（binding 存在、无低版本在途、
  root 前缀校验）。写发送意图（`remote_operation_id`）→ `removeResource(binding.remoteUri(), false, ownedRoot)`。
  - removed=true → settle `VERIFYING`（交给 Poller 确认 absent），binding 观测 `projection_status=DELETING`；
  - 明确失败且 `requestIssued=false`（连接未建立/429/409 path_busy）→ `retryWait` 退避，**绝不 SUCCEEDED**；
  - `requestIssued=true` 且结果不明 → `unknownRemote`（查询收敛）。
- `REBUILD_DOCUMENT`：与 UPSERT 相同的上传+add_resource 流程（复用现有私有方法，抽出共享路径），
  desired version 不变。
- `DELETE_KNOWLEDGE_BASE`：本 WP 只允许 `recursive=true` 删除该 KB 的 owned root 前缀；
  发送前逐项校验 root 前缀。
- 同文档排序：DELETE 的 syncVersion 高于在途 UPSERT 时同样 `deferredLocally`（refundAttempt）。

必测（沿用现有 in-memory store + fake port 风格）：
- 删除成功进入 VERIFYING 且 binding=DELETING；
- `path_busy` 时 settle RETRY_WAIT 且**断言 binding 未被标成任何成功/删除完成状态**（计划必测："删除 busy 不得标成功"）；
- 在途 v1 UPSERT 时 v2 DELETE 被本地推迟且退还 attempt；
- REBUILD 走 upsert 流程且不推进 desired version；
- 非 owned root 的 remoteUri => NEEDS_HUMAN（CONFIGURATION_BLOCKED），不发请求。

### Task A3：Poller 确认删除与防复活

**Files:**
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexPollEngine.java`
- Test: `rag/src/test/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexPollEngineTest.java`

行为契约：
- DELETE op 的收敛用 `inspectResource`：exists=false → settle `SUCCEEDED`，
  binding 观测 `observed_state=ABSENT, projection_status=DELETED`（CAS）；exists=true → 按年龄退避继续等，
  超墙钟截止 → NEEDS_HUMAN。
- 防复活（**计划必测原文："v1 晚完成也不能复活"**）：UPSERT op 核验通过但
  `binding.desiredState==ABSENT`（或 desiredVersion > op.syncVersion）时，settle `SUPERSEDED`，
  观测只写 `DRIFTED`，绝不 `IN_SYNC`。为此 binding 领域模型需要暴露 `desiredState()`（已有列）。

必测：
- 删除后 probe 不存在 → DELETED；
- probe 仍存在且未超时 → 继续等待（行状态不变、退避拉长）；
- **v1 UPSERT 晚完成 + binding.desired 已是 v2 DELETE（ABSENT）→ v1 op=SUPERSEDED、binding 不回 IN_SYNC**；
- probe 查询抛错 → defer，不写任何观测。

### Task A4：墓碑不可被抹掉（SQL/结构性回归）

**Files:**
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/OpenVikingProjectionSqlPolicyTest.java`

断言（静态检查 p11 + mutation engine 源码）：
- `knowledge_documents` 软删列存在（`deleted_at`），且 p11 中 bindings/outbox 无 `ON DELETE CASCADE`
  （已有断言，补充：`knowledge_document_revisions` 也必须 RESTRICT）——计划必测
  "FK cascade 不能抹掉墓碑"。
- `KnowledgeDocumentMutationEngine` 删除路径写 tombstone（源码包含 `DELETE_DOCUMENT`）且不物理删行
  （删除方法源码不得出现 `deleteById(`、`DELETE FROM knowledge_documents`）。

### Task A5：死信 requeue 原语（store 层）

**Files:**
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexOutboxStore.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/impl/InMemoryKnowledgeExternalIndexOutboxStore.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/KnowledgeExternalIndexOutboxMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresKnowledgeExternalIndexOutboxStore.java`
- Test: `rag/src/test/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexOutboxStoreContractTest.java`

签名与语义（CAS，人工操作是并发写者之一，不能例外）：

```java
Optional<KnowledgeExternalIndexOperation> requeueDeadLetter(long eventId, long expectedRowVersion, long nowEpochMillis);
```

- 仅当 `status='DEAD_LETTER'` 且 `row_version` 匹配才生效；
- `remote_operation_id=''`（未越过发送边界）→ `PENDING`，`attempt_count=0`，`next_visible_at=now`；
- `remote_operation_id!=''`（越过边界）→ `UNKNOWN_REMOTE_RESULT`，attempt 不清零，交 Poller 查询收敛——
  **绝不回提交路径**；
- 两种情况都清 lease，`row_version+1`。

契约测试同时跑 in-memory 与（既有模式下的）SQL 断言：SQL 里必须含
`status = 'DEAD_LETTER'`、`row_version = #{expectedRowVersion}`，且 PENDING 分支才清 attempt。

### Task A6：Reconciler 引擎与 findings 账本

**Files:**
- Create: `bootstrap/src/main/resources/sql/postgres/p12_openviking_reconcile.sql`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeReconcileFindingStore.java`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/impl/InMemoryKnowledgeReconcileFindingStore.java`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/model/ReconcileFinding.java`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/model/ReconcileFindingType.java`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/model/ReconcileReport.java`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexReconcileEngine.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/KnowledgeReconcileFindingMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/KnowledgeReconcileFindingRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresKnowledgeReconcileFindingStore.java`
- Test: `rag/src/test/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexReconcileEngineTest.java`

`p12` 表（新迁移允许；不改 p11）：

```sql
CREATE TABLE IF NOT EXISTS knowledge_reconcile_findings (
    id             BIGINT PRIMARY KEY,
    provider       VARCHAR(32) NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    finding_type   VARCHAR(40) NOT NULL,   -- MISSING_REMOTE/STALE_REMOTE/ORPHAN_REMOTE/FOREIGN_OWNER/DRIFTED_MARKER/LEASE_EXPIRED
    remote_uri     TEXT NOT NULL DEFAULT '',
    document_id    BIGINT,
    detail         TEXT NOT NULL DEFAULT '',
    status         VARCHAR(32) NOT NULL DEFAULT 'OPEN', -- OPEN/AUTO_REPAIRED/QUARANTINED/RESOLVED
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, knowledge_base_id, finding_type, remote_uri, document_id)
);
```

引擎 `runOnce(kbId, ownedRoot, nowEpochMillis)` 三段扫描（全部有界，batch 上限入参）：
1. local→remote：`desired_state=PRESENT` 且 binding 非 IN_SYNC 超过阈值、无非终态 op → enqueue
   `REBUILD_DOCUMENT`（复用 outbox 唯一键幂等吸收）；记 `MISSING_REMOTE`/`STALE_REMOTE` finding。
2. remote→local：`listTree(ownedRoot)` 对照 bindings：远端有而本地无 binding → `ORPHAN_REMOTE`
   finding（status=QUARANTINED），**本 WP 不自动删除**；owner tag 非 RD-Bot → `FOREIGN_OWNER`
   finding（永不修复）；binding IN_SYNC 但 probe 缺失 → `MISSING_REMOTE` + 观测 CAS 改 DRIFTED。
3. control-plane：`lease_until < now - workerLease` 的非终态行记 `LEASE_EXPIRED` finding（只记账，
   恢复仍由 claim 谓词完成）。

产出 `ReconcileReport(counts by type, findings)`。**引擎绝不直接调 removeResource**（孤儿删除
是后续 WP 的显式管理操作）。

必测：孤儿只隔离不删除；foreign 永不产生任何 outbox 行；IN_SYNC+缺失 → DRIFTED 观测 + REBUILD 入队；
重复 runOnce 幂等（findings upsert 更新 last_seen，outbox 唯一键吸收重复 REBUILD）。

### Task A7：装配、调度与策略回归

**Files:**
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/OpenVikingProjectionConfiguration.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/KnowledgeProjectionScheduler.java`
  （reconcile tick 独立开关 `rd.knowledge.projection.reconcile.enabled`，默认 false，间隔
  `interval-millis` 默认 300000——键名已在 application.yaml 预留）
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/OpenVikingProductionBoundaryPolicyTest.java`
- Modify: `RULE.md`（3.5.9 追加：删除语义、requeue 语义、Reconciler 边界，含验证命令）

策略测试新增断言：
- SyncEngine 的 DELETE 分支先写发送意图再调 `removeResource`（源码顺序断言，沿用现有手法）；
- `KnowledgeExternalIndexReconcileEngine` 源码不含 `removeResource(`、`submitUpsert(`；
- requeue SQL 不含 `SET status = 'PENDING'` 于 `remote_operation_id != ''` 分支（防未来回归）；
- application.yaml `reconcile.enabled` 默认 false。

**显式延期（不做，不许子代理顺手实现）**：墓碑 purge job（到期硬删 `knowledge_documents` 行）。
硬删除是破坏性操作且与 FK RESTRICT、审计留痕相互作用，需要单独的设计评审；本 WP 内
墓碑只读可见（Stage B `tombstones` 接口）即满足"删除后的记录仍然可以查看和恢复"。

Stage A 完成门槛：
`./mvnw -pl rag -am -Dtest='KnowledgeExternalIndex*Test,KnowledgeMutationTransactionPortTest' -Dsurefire.failIfNoSpecifiedTests=false test`
与 `./mvnw -pl bootstrap -am -Dtest='OpenViking*Test,PrometheusMetricsControllerTest,ImplementationPackageIsolationPolicyTest,ModelPackageIsolationPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false test`
全绿（隔离测试允许的失败仅限 `c230ee00` 遗留 7 项）。

---

## Stage B：WP-5 管理 API（后端）

### Task B1：KnowledgeProjectionAdminEngine（rag，聚合只读 + 复用原语）

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeProjectionAdminEngine.java`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/model/ProjectionAdminActionResult.java`
- Test: `rag/src/test/java/com/wish/rd/rag/knowledge/projection/KnowledgeProjectionAdminEngineTest.java`

能力（每个方法先做 KB 归属校验：docId 必须属于 kbId，越界抛 IllegalArgumentException）：
- `retry(kbId, docId)`：找该 doc 最新非终态 op：RETRY_WAIT/NEEDS_HUMAN → CAS 置
  `next_visible_at=now`（NEEDS_HUMAN 先 requeue 语义：未越界→PENDING，越界→UNKNOWN_REMOTE_RESULT）；
  无非终态 op → 幂等返回"nothing to retry"。**不新建版本，不直接发 REST。**
- `verify(kbId, docId)`：读 binding → `verifyResource`（只读）→ 观测 CAS 更新
  （IN_SYNC/DRIFTED），返回核验明细。
- `rebuild(kbId, docId)`：为当前 desired version 入队 `REBUILD_DOCUMENT`（唯一键吸收重复）。
- `requeueDeadLetter(kbId, eventId)`：Task A5 原语 + KB 归属校验。
- `reconcile(kbId)`：调 Task A6 引擎 runOnce，返回 ReconcileReport。
- 查询：`overview(kbId)`（binding/outbox 分状态计数 + 最老未收敛年龄 + ready）、
  `documents(kbId, status?, page, size)`、`documentDetail(kbId, docId)`（binding + 该 doc 的
  op timeline 列表 + 最近错误）、`deadLetters(kbId, page, size)`、`tombstones(kbId, page, size)`
  （`deleted_at IS NOT NULL` 的文档 + binding 状态）、`tree(kbId, uri?)`（强制 owned root 前缀，
  越界抛错，不代理任意 URI）、`health(kbId)`（ready、settings、semantic fingerprint、
  reconcile findings 计数）。

Store 需要的新查询方法（同步补 in-memory 与 Postgres 实现 + mapper）：
- outbox：`findByDocument(provider, documentId, limit)`、`findByStatus(provider, kbId, status, offset, limit)`、
  `countByStatus(provider, kbId)`；
- binding：`findByKnowledgeBase(provider, kbId, projectionStatus?, offset, limit)`、
  `countByProjectionStatus(provider, kbId)`。

必测（in-memory）：越界 docId 拒绝；retry 对 NEEDS_HUMAN 走 requeue 语义（越界行只会去
UNKNOWN_REMOTE_RESULT）；rebuild 幂等；verify 失败时观测写 DRIFTED；tree 越界 URI 拒绝。

### Task B2：KnowledgeProjectionAdminController + DTO

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/knowledge/KnowledgeProjectionAdminController.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/knowledge/KnowledgeProjectionAdminControllerTest.java`

端点（对齐总计划 §13.1，Controller 只组装 Engine 结果为 DTO）：

```text
GET  /admin/knowledge-base/{kbId}/openviking/overview
GET  /admin/knowledge-base/{kbId}/openviking/documents?status=&page=&size=
GET  /admin/knowledge-base/{kbId}/openviking/documents/{docId}
GET  /admin/knowledge-base/{kbId}/openviking/tree?uri=
GET  /admin/knowledge-base/{kbId}/openviking/health
GET  /admin/knowledge-base/{kbId}/openviking/dead-letters?page=&size=
GET  /admin/knowledge-base/{kbId}/openviking/tombstones?page=&size=
POST /admin/knowledge-base/{kbId}/openviking/documents/{docId}/retry
POST /admin/knowledge-base/{kbId}/openviking/documents/{docId}/verify
POST /admin/knowledge-base/{kbId}/openviking/documents/{docId}/rebuild
POST /admin/knowledge-base/{kbId}/openviking/dead-letters/{eventId}/requeue   body: {"expectedRowVersion": n}
POST /admin/knowledge-base/{kbId}/openviking/reconcile
```

- 响应统一 `{ "data": ... }`，错误经 `@ExceptionHandler` 翻译（400 归属/参数错、409 CAS 失败、
  502 远端不可用），禁止栈信息与原始远端响应外泄（RULE.md 行 371）。
- DTO 全部为 controller 内 record（跟 `PrometheusMetricsController` 一致的包内风格），字段用
  camelCase；错误消息字段只能来自已 `safeMessage` 的持久化值。
- 投影关闭（Disabled port）时：读接口正常（账本还在），`verify/reconcile` 返回 409 + 明确文案。

必测（MockMvc + in-memory 装配，勿依赖 Postgres）：每个端点的 200 形状；越界 docId → 400；
requeue rowVersion 过期 → 409；tree 越界 uri → 400；错误体无 `viking://` 之外的内部细节、无堆栈。

### Task B3：SPA 路由与代理契约

**Files:**
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/AdminFrontendController.java`
  （把 `/admin/knowledge/{kbId}/openviking` 加入页面导航路由；嵌套 API 不得被 SPA bypass 吞掉）
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/AdminFrontendControllerTest.java`
- Modify: `frontend/vite.config.ts`（若代理规则需要）与 `frontend/test/viteProxy.test.ts`

断言：`GET /admin/knowledge/123/openviking`（Accept: text/html）返回 index.html；
`GET /admin/knowledge-base/123/openviking/overview` 永远代理后端 JSON，绝不返回 index.html。

Stage B 完成门槛：
`./mvnw -pl bootstrap -am -Dtest='KnowledgeProjectionAdmin*Test,AdminFrontendControllerTest' -Dsurefire.failIfNoSpecifiedTests=false test` 全绿
（AdminFrontendControllerTest 若因既有 `CodingBenchmarkTrialStore` 缺 Bean 而上下文失败，
维持既有失败面，不新增失败即可；新断言用切片测试实现，避免全上下文）。

---

## Stage C：WP-5 前端页面

### Task C1：service 与类型

**Files:**
- Create: `frontend/src/services/openVikingKnowledgeService.ts`（模式对齐 `knowledgeService.ts`：
  `api.get/post`、导出 TS 类型与拉取函数）
- Test: `frontend/test/openVikingKnowledgeModel.test.ts`（纯函数：状态→徽标映射、
  轮询判定 `shouldKeepPolling(summary)`、错误脱敏兜底展示）

类型对齐 B2 的 DTO；状态徽标色板：IN_SYNC=green、PENDING/PROCESSING=blue、DRIFTED=amber、
FAILED/DEAD_LETTER=red、NEEDS_HUMAN=orange、DELETING/DELETED=slate。

### Task C2：页面与组件

**Files:**
- Create: `frontend/src/pages/admin/knowledge/OpenVikingKnowledgePage.tsx`
- Create: `frontend/src/components/admin/knowledge/OpenVikingStatusBadge.tsx`
- Create: `frontend/src/components/admin/knowledge/OpenVikingOperationTimeline.tsx`
- Modify: `frontend/src/App.tsx`（`<Route path="knowledge/:kbId/openviking" ...>`）
- Modify: `frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx`（页头加入口按钮
  "OpenViking 投影"）

页面结构（对齐总计划 §13.2；用既有 Radix Tabs/Dialog/Tooltip 与 Tailwind 风格）：
1. 顶部健康卡（ready、fingerprint、最后 reconcile、卡住计数）；
2. 汇总徽标卡（各 projection_status 计数，点击过滤表格）；
3. 文档映射表（分页；列：文档、desired/observed version、checksum 简写、URI、task、
   attempt、最后验证、状态徽标；行点开抽屉）；
4. 抽屉：operation timeline（A6/B1 的 op 列表按时间倒序，含错误码/脱敏消息）、手工操作按钮
   retry/verify/rebuild（成功后**重新拉取服务端账本**再更新 UI，禁止只凭 Toast 判定成功）；
5. Remote Tree 页签（只读；foreign/orphan 行用醒目警示色）；
6. 死信页签（requeue 按钮带 `expectedRowVersion`，409 时提示刷新重试）；
7. 墓碑页签（已删除文档只读可见）。

轮询：存在非终态 op 时每 5s 拉 overview+documents；连续两轮无非终态 op 停止轮询。

### Task C3：Node 测试、typecheck、build

- Modify: `frontend/test/viteProxy.test.ts`（新增导航路由与 12 个 API 路径断言）
- 全量验证（Stage C 完成门槛）：

```bash
cd frontend && npm test && npm run typecheck && npm run build
```

三者全部通过；任何一个失败都必须修复后重跑。

---

## 真机验收（由主会话执行，不在子代理范围）

1. Stage A 后：扩展/新增 live 冒烟（`rd.openviking.smoke=true`）覆盖
   upsert→IN_SYNC→delete→DELETED→重复删除幂等；真实容器验证。
2. Stage C 后：`RD_KNOWLEDGE_PROJECTION_MODE=ON RD_OPENVIKING_ENABLED=true` 启动后端 + 前端 build，
   浏览器实测页面（含 retry/verify 按钮、死信 requeue、刷新不 404）、截图留证；
   并做密钥扫描：前端 bundle、浏览器网络面板、后端日志中不得出现 API key 或
   `X-API-Key` 值（WP-5 必测项）。
3. 全量回归对比基线（已知失败面：`CodingBenchmarkTrialStore` 上下文族、
   `RdTaskControllerTest`、`RequirementDeliveryDispatchServiceTest`、Python fixture、
   host-verifier、隔离策略 7 项遗留）。
