## 真实入口与现状链路

需求交付侧（本 change 唯一接入点）：

```
RequirementDeliveryEngine.ensureRoleContexts
RequirementAgentStageOrchestrator.recordRetrieval
  → RequirementContextRetrievalRecorder.recordOnly（topK=8）
  → DeepRetrievalOrchestrator.retrieveWithPolicy / retrieveOnce
  → RequirementKnowledgeSearchPort.search(task, role, query, scope, topK)
  → ProjectScopedRequirementKnowledgeSearchAdapter（@ConditionalOnBean{VectorStore, RdProjectService}）
  → MultiChannelRetrievalEngine（intent 向量 + keyword 两通道）→ VectorStore
```

关键既有边界，本 change 必须保留：

- `ProjectScopedRequirementKnowledgeSearchAdapter` scope 为空时返回
  `ChannelAudit("ProjectScope", MISSING_SCOPE)` 且**不**调 VectorStore；未绑定知识库的项目是"跳过检索"，
  不是"全局检索"。
- 该适配器是 postgres-only（依赖 `RdProjectService`）；内存存储下需求检索退化为 `noop()`。
  Router 不得改变这个退化行为。
- 进角色上下文的最终形状是 `RoleContextEvidence(evidenceId, sourceType, sourceUri, title,
  contentHash, summary, relevanceScore, requiredEvidenceType, sharedRoot)`；
  OpenViking 侧必须产出同一记录类型，不新增并行结构。
- 审计链复用 `RetrievalRunLifecycle.appendArtifact` 与 `rd_rag_retrieval_runs/_events/_artifacts`，
  不新建表。

## 端口与适配器边界

### 读写端口物理分离

`ExternalKnowledgeIndexPort` 是投影**写**端口：它的每个方法都参与 settle/CAS 语义。
检索属于只读路径，另立端口：

```java
public interface ExternalKnowledgeNavigatorPort {
    ExternalNavigatorSearch searchAbstracts(ExternalNavigatorQuery query);            // L0
    ExternalNavigatorDocument readOverview(String resourceUri);                       // L1
    ExternalNavigatorContent readContent(String resourceUri, int offset, int limit);  // L2
    boolean ready();
}
```

约束：

- navigator 端口的任何实现**不得**依赖 `KnowledgeExternalIndexOutboxStore` 或
  `KnowledgeExternalIndexBindingStore` 的写方法；allowlist 的库查询发生在端口之外。
- `OpenVikingRestNavigatorAdapter` 复用现有 `OpenVikingHttpExchange`、共享 API key supplier
  与 owned-root 校验；不得新建第二套 HTTP 客户端或第二条 key 解析路径
  （WP-3 已因 `ready()` 与请求执行各判一套 key 出过事）。
- 越出 owned root 的 URI 不发请求，直接 `CONFIGURATION_BLOCKED`。
- 未启用时用 `DisabledExternalKnowledgeNavigatorPort` 全部返回 `CONFIGURATION_BLOCKED`，
  与写端口的 `DisabledExternalKnowledgeIndexPort` 对称。

### Router 作为装饰器

`KnowledgeRetrievalModeRouter implements RequirementKnowledgeSearchPort`，包住现有适配器。
`LOCAL` 直接委托且对 navigator 端口**零调用**（可断言）。这保证默认路径逐字节不变、
回退只需改一个环境变量。

## 持久化真值与状态

- PostgreSQL 是 desired/canonical 真值；OpenViking 是可重建投影。检索路径**只读**，
  不得写 binding/outbox/document 任何一行。
- Shadow 度量落在既有 retrieval run/artifact 表，属任务审计数据，不是投影状态。
- 证据准入判定的真值来源全部在库内：`knowledge_documents.enabled/deleted_at/superseded_by_document_id`、
  `knowledge_external_index_bindings.projection_status/observed_version/desired_version/knowledge_base_id`。
  远端返回什么都不构成准入依据。

## 证据 allowlist 判定顺序

对 OpenViking 返回的每个 URI 依次判定，任一步失败即丢弃并记录原因：

1. URI → binding（provider=OPENVIKING，按 `remote_uri` 前缀匹配）。缺失 → `NO_BINDING`。
2. `projection_status = IN_SYNC` 且 `observed_version = desired_version`。否则 → `VERSION_NOT_VERIFIED`。
3. 文档 `enabled = true` 且 `deleted_at IS NULL` 且 `superseded_by_document_id IS NULL`。
   否则 → `DOCUMENT_NOT_ACTIVE`。
4. 文档 `knowledge_base_id` ∈ 本次 scope。否则 → `OUT_OF_SCOPE`。

第 2 条是"不静默混用远端旧版本"的落点：远端可能仍存着上一版正文，
只有本地观测已核验等于期望版本时才允许引用。
第 4 条把"跨知识库证据"变成结构上不可能，而不是依赖调用方自律。

拒绝必须留痕（数量 + 原因分布进 artifact）。没有留痕的 allowlist 会让评测无法区分
"OpenViking 没召回"与"召回了但被拒"，这两件事的处置完全不同。

需要给 `KnowledgeExternalIndexBindingStore` 增加按 `remote_uri` 反查。
Mapper 若出现 `#{param} IS NULL` 必须带 `jdbcType`（RULE.md 与
`PersistenceImplementationPolicyTest#mapperSqlMustNotCompareAnUntypedParameterAgainstNull`
已因这个坑各挂过一次）。

## 三层导航的收敛与停止

循环：L0 检索候选 → 选择 → L1 overview → 证据门判定是否够 → 不够则 L2 正文 →
评估缺失证据类型与信息增益 → 预算允许且可改写则改写查询回到 L0 → 否则产出证据。

停止原因（枚举，每个都要有测试）：`EVIDENCE_SUFFICIENT`、`LOW_INFORMATION_GAIN`、
`DUPLICATE_CANDIDATES`、`ROUND_BUDGET_EXHAUSTED`、`REMOTE_CALL_BUDGET_EXHAUSTED`、
`TOKEN_BUDGET_EXHAUSTED`、`TIME_BUDGET_EXHAUSTED`、`REMOTE_UNAVAILABLE`、`NEEDS_USER_INPUT`。

预算耗尽必须返回**已获得的部分证据** + `stop_reason`，不得抛异常、不得无限追问。

并发与超时：RULE.md 已强制并发检索用 `Executors.newVirtualThreadPerTaskExecutor()`。
SHADOW 下 OpenViking 与 LOCAL 并发，OpenViking 超预算即丢弃本轮结果，
绝不延长角色派发的关键路径。

## Prompt injection 防线

- L0/L1/L2 文本进 `<untrusted_knowledge>` 数据区，与指令区分离。
- 导航只能输出 query / URI / 层级三类选择，不能修改工具权限或请求任意 URL。
- 每个 URI 过 owned-root 与 scope 校验后才允许读取。
- 文档中的指令性文本不得成为 system/developer/tool 指令。
- 对外证据保留 documentId、revision、checksum、URI 与引用范围。

## 评测设计

两套语料**分别**报告，禁止混算：

- 真实语料：当前 54 篇真实文档（含中文飞书文档与代码文件）。切流决策只看这一套。
- 合成语料：`rag/src/test/resources/openviking-baseline/`（32 篇含唯一 ASCII token），
  保留作回归并标注"系统性高估"。

每题记录：LOCAL 命中集、OPENVIKING 命中集、引用 URI/version 有效率、上下文 token、
端到端延迟、allowlist 拒绝数与原因分布、stop_reason。

报告必须声明嵌入档（`dashscope`），并写明"本报告不构成切流决定"。

## 验证命令

- `./mvnw -q -pl rag -am -Dtest='*Navigator*,*Allowlist*' -Dsurefire.failIfNoSpecifiedTests=false test`
- `./mvnw -q -pl engine -am -Dtest=KnowledgeRetrievalModeRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `./mvnw -q -pl bootstrap -am -Dtest=OpenVikingRestNavigatorAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 既有投影回归：`./mvnw -q -pl rag -am -Dtest=OpenVikingProjectionUrisTest,OpenVikingLocalRetrievalBaselineTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 真机：`OPENVIKING_EMBEDDING_PROFILE=dashscope scripts/openviking/up.sh` 后跑评测 runner，
  确认 `LOCAL` 模式下 navigator 端口零调用、`SHADOW` 模式下任务结果不变。
