## Why

WP-1～WP-6 让 PostgreSQL 的知识变更能可靠投影进 OpenViking，但**读路径至今与 OpenViking 完全无关**：
需求交付链路仍是 `ProjectScopedRequirementKnowledgeSearchAdapter` → `MultiChannelRetrievalEngine`
→ 本地 `VectorStore`（`bootstrap/.../rag/impl/ProjectScopedRequirementKnowledgeSearchAdapter.java:108-156`）。
`ExternalKnowledgeIndexPort` 上没有 search/find/overview/read
（`rag/.../knowledge/projection/ExternalKnowledgeIndexPort.java`），`POST /api/v1/search/find`
只出现在测试里，生产零调用。`RD_RAG_KNOWLEDGE_PROVIDER_MODE`（默认 `LOCAL`，注释已写
`LOCAL/SHADOW/OPENVIKING`）存在于 `application.yaml:172` 却**没有任何 Java 读取方**，是死配置。

也就是说：投影已经建成，但它带来的检索能力一次都没有被用过，而且没有任何机制能安全地
比较"用 OpenViking 检索"与"用本地检索"的差异。

前置条件在本轮已具备：OpenViking 的嵌入已从 32 维 mock 换成真实 `text-embedding-v4`（1024 维），
54 篇文档在新嵌入下全部重投影回 `IN_SYNC`，真实语料自查召回 Recall@8 = 52/52、Recall@1 = 44/52。
在此之前任何检索质量对比都只是在测量 mock 向量。

## What Changes

- 新增只读导航端口与 OpenViking 适配器（L0 search / L1 overview / L2 read），与投影**写**端口物理分离。
- 新增 `LOCAL / SHADOW / OPENVIKING` 读路径 Router，装饰现有需求检索适配器；默认 `LOCAL`，
  行为与今天一致；非法模式值启动即失败，不静默退回。
- SHADOW 下 OpenViking 与 LOCAL 并发执行，但只写 retrieval artifacts 与 metrics，
  返回值恒等于纯 LOCAL 结果；OpenViking 的失败与超时不得影响任务。
- 新增以 PostgreSQL 为唯一权威的证据 allowlist：只有 `enabled=true`、未删除、未被 supersede、
  binding `IN_SYNC` 且 `observed_version == desired_version`、且知识库落在本次 scope 内的
  版本才允许进入 Agent 上下文；每条被拒候选带原因留痕。
- 新增受预算约束的三层导航循环（L0 → L1 → 证据门 → L2 → 评估 → 查询改写），
  预算与停止门可配置，预算耗尽返回部分结果与明确 `stop_reason`。
- 新增 prompt injection 防线：外部正文一律进 `<untrusted_knowledge>` 数据区，
  导航只能选 query/URI/层级。
- 新增评测：真实语料与合成语料**分别**报告 Recall@K、引用 URI/version 有效率、
  上下文 Token、p50/p95 延迟与 allowlist 拒绝分布。

## Capabilities

### New Capabilities

- `knowledge/openviking-shadow-retrieval`: 读路径模式路由、只读导航端口、证据 allowlist、
  三层导航预算与停止门、Shadow 度量契约。

### Modified Capabilities

- `knowledge/openviking-projection-admin`: 不改既有接口行为；仅新增只读的 Shadow 度量视图（如有）。

## Impact

- 计划与实施细节：`docs/superpowers/plans/2026-08-13-wp7-shadow-retrieval-and-three-tier-agent-plan.md`
  （属计划类材料，不得直接当作主 spec 需求）。
- 上游范围定义：`docs/superpowers/plans/2026-08-13-openviking-rag-integration-implementation-plan.md`
  §14 与第 15 节 WP-7。
- 主要代码范围：`rag/.../retrieval/navigator/**`（新）、
  `bootstrap/.../openviking/impl/OpenVikingRestNavigatorAdapter.java`（新）、
  `engine/.../retrieval/KnowledgeRetrievalModeRouter.java`（新）、
  `rag/.../knowledge/projection/store/KnowledgeExternalIndexBindingStore.java`（加按 URI 反查）、
  `bootstrap/src/main/resources/application.yaml`。
- 主要风险：SHADOW 若同步执行会拖慢角色派发关键路径，因此必须并发 + 独立超时 + 超预算丢弃；
  allowlist 若不留痕会把"没召回"和"召回被拒"混为一谈，使评测失真；
  只用合成语料评测会系统性高估召回（RULE.md 已强制两档分别报告）。
- 明确不做：不切流（默认停在 `LOCAL`）；不做 WP-8 故障演练；不动 bug-fix 多通道链路；不引入 rerank。
