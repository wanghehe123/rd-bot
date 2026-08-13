# WP-7 实施计划：Shadow 检索、三层 Agent RAG 与评测

> 上游计划：`docs/superpowers/plans/2026-08-13-openviking-rag-integration-implementation-plan.md` §14、WP-7
> 前置：WP-0～WP-6 已完成并归档；Stage 0（真实嵌入）已完成，见本文 §2

## 1. 目标与退出标准

目标：在不动现有本地检索的前提下，新增 `LOCAL / SHADOW / OPENVIKING` 读路径路由，
把 OpenViking 的 L0/L1/L2 接成一个受预算约束、每步留证的三层导航循环，
并用真实语料的评测数据决定是否按知识库切流。

退出标准（全部可验证）：

1. `RD_RAG_KNOWLEDGE_PROVIDER_MODE` 有真实读取方，默认 `LOCAL`，默认行为与今天逐字节一致。
2. SHADOW 下 OpenViking 结果**永不**进入任务上下文，只落 retrieval artifacts；
   OpenViking 侧任何失败/超时都不得改变 LOCAL 的结果或让角色派发失败。
3. 每条 OpenViking evidence 都过 PostgreSQL allowlist：`enabled=true`、未删除、未被 supersede、
   binding `IN_SYNC` 且 `observed_version == desired_version`。跨知识库证据数必须为 0。
4. 三层循环的预算与停止门可配置且被测试钉住；预算耗尽返回部分结果 + 明确 `stop_reason`，不得无限追问。
5. 评测产出一份可复现报告：Recall@K、引用 URI/version 有效率、上下文 Token、p50/p95 延迟、
   以及 LOCAL 与 OPENVIKING 的逐题对照。
6. `openspec validate --all --strict` 通过；`RULE.md` 记录新增约束与验证命令。

**不做**（明确排除）：

- 不切流。默认停在 `LOCAL`，切流是 WP-7 之后由评测报告驱动的独立决定。
- 不做 WP-8 故障演练（用户已确认个人项目无必要）。
- 不动 bug-fix 那条多通道检索链路（`MultiChannelRetrievalEngine`），本 WP 只接需求交付链路。
- 不引入 rerank 模型；`RetrievalStepType.RERANK` 继续留空。

## 2. Stage 0 已完成：真实嵌入（前提）

WP-7 的评测如果跑在 mock 嵌入上就没有决策效力，所以先换真实模型。已完成：

- 新增 `deploy/openviking/ov.conf.dashscope.template`：`provider=openai`、
  `api_base=https://dashscope.aliyuncs.com/compatible-mode/v1`、`model=text-embedding-v4`、
  `dimension=1024`。密钥只以 `${DASHSCOPE_API_KEY}` 出现，由 `scripts/openviking/up.sh`
  渲染成 `ov.conf.rendered`（已 gitignore、mode 600）。compose 挂载改为
  `${OPENVIKING_CONF_FILE:-./ov.conf}`，`mock` 档保持为离线默认。
  多模态 `qwen3-vl-embedding` 走不了 OpenAI 兼容端点（官方明确不支持），且语料是纯文本，用不上。
- 维度 32 → 1024 使旧向量全部失效，按"OpenViking 是可重建投影"清卷重建：
  删 `rd-bot-openviking-data` → 重建 account → 对账发现 `MISSING_REMOTE` → 入队 `REBUILD_DOCUMENT`
  → 54 篇全部回到 `IN_SYNC`，`unconvergedCount=0`。
- 顺带修掉两个投影缺陷（已写入 RULE.md，见 §7）：
  `listTree` 把 owned root 的 404 当失败导致对账在整卷丢失时全盲；
  `PostgresKnowledgeDocumentRevisionStore#findById("")` 抛 `NumberFormatException`
  导致 54 条重建一次性挂成 `INVALID_PAYLOAD`。
- 真实语料召回实测（52 篇 IN_SYNC 文档，每篇取正文中段 200 字自查，`limit=8`）：
  **Recall@8 = 52/52、Recall@1 = 44/52、自命中平均排名 1.35、0 未命中 0 报错**，
  中文文档 top score 0.97~0.98。这推翻了 RULE.md 里"中文正文查询 61 秒 0 命中"的旧证据
  ——那是 mock 向量的产物。settle 路径禁用 `search/find` 的结论不变（它证明不了版本）。

## 3. 现状锚点（读路径今天长什么样）

必须先接受一个事实：**今天的读路径与 OpenViking 完全无关**。

- 需求交付链路：`RequirementDeliveryEngine` / `RequirementAgentStageOrchestrator`
  → `RequirementContextRetrievalRecorder.recordOnly`（topK=8）
  → `DeepRetrievalOrchestrator.retrieveWithPolicy` → `RequirementKnowledgeSearchPort.search`
  → `ProjectScopedRequirementKnowledgeSearchAdapter`（`bootstrap/.../rag/impl/`）
  → `MultiChannelRetrievalEngine`（仅 intent 向量 + keyword 两通道）→ `VectorStore`。
- `ProjectScopedRequirementKnowledgeSearchAdapter` 已硬绑定单知识库：scope 为空时直接返回
  `ChannelAudit("ProjectScope", MISSING_SCOPE)` 且**不**调 VectorStore。这个边界必须保留。
- `ExternalKnowledgeIndexPort` 上**没有** search/find/overview/read。`verifyResource` 内部私有地
  GET `/content/abstract`、`/overview`、`/read` 仅用于版本核验。
  `POST /api/v1/search/find` 只出现在测试里，生产零调用。
- `RD_RAG_KNOWLEDGE_PROVIDER_MODE`（默认 `LOCAL`，注释已写 `LOCAL/SHADOW/OPENVIKING`）
  存在于 `application.yaml` 但**没有任何 Java 读取方**，是一条死配置。本 WP 给它接上。
- 现成的审计链可以直接复用，不要另造：`RetrievalRunLifecycle.appendArtifact` 写
  `rd_rag_retrieval_runs` / `_events` / `_artifacts`；需求侧已在用
  `QUERY`/`RETRIEVAL_PLAN`/`SEARCH_CHANNEL`/`MATERIAL_EVIDENCE`/`SELECTED_EVIDENCE`/
  `QUALITY_REPORT`/`RETRIEVAL_OUTCOME` 这些 artifact 类型。
- `RoleContextEvidence(evidenceId, sourceType, sourceUri, title, contentHash, summary,
  relevanceScore, requiredEvidenceType, sharedRoot)` 是进角色上下文的最终形状，
  OpenViking 侧必须产出同一个记录类型，不得新增并行结构。

## 4. 关键设计决定

### 4.1 读端口与写端口分离

不把 search/read 加到 `ExternalKnowledgeIndexPort`（那是投影写端口，语义是"让远端与库一致"）。
新增只读端口：

```java
public interface ExternalKnowledgeNavigatorPort {
    ExternalNavigatorSearch searchAbstracts(ExternalNavigatorQuery query);   // L0
    ExternalNavigatorDocument readOverview(String resourceUri);              // L1
    ExternalNavigatorContent readContent(String resourceUri, int offset, int limit); // L2
    boolean ready();
}
```

理由：写端口的每个方法都要参与 settle/CAS 语义，读端口一次调用都不许碰 binding/outbox。
两者混在一个接口里，早晚有人在检索路径里调 `submitUpsert`。

实现 `OpenVikingRestNavigatorAdapter` 复用现有 `OpenVikingHttpExchange`、
共享的 API key supplier 与 owned-root 校验，**不得**新建第二套 HTTP 客户端或第二个 key 解析路径。

### 4.2 Router 挂在 `RequirementKnowledgeSearchPort` 这一层

`LOCAL` 就是今天的 `ProjectScopedRequirementKnowledgeSearchAdapter` 原样。Router 是它的装饰器，
不改被装饰者。这样 LOCAL 路径逐字节不变，回退只需把配置改回 `LOCAL`。

SHADOW 的硬约束：

- OpenViking 侧在虚拟线程里与 LOCAL **并发**执行（RULE.md 已强制并发检索用
  `Executors.newVirtualThreadPerTaskExecutor()`），带独立超时预算。
- 返回给调用方的永远是 LOCAL 的 `SearchResult`，逐字节相同。
- OpenViking 侧的异常、超时、空结果一律**只**写 artifact + metrics，绝不冒泡。
- SHADOW 不得延长角色派发的关键路径：OpenViking 超预算就丢弃它这一轮的结果。

`OPENVIKING` 模式下 OpenViking 为主，超时/不可用按策略降级 LOCAL，并在 Run 里写明
`DEGRADED` 与降级原因。降级是记录事件，不是静默兜底。

### 4.3 证据 allowlist 以 PostgreSQL 为唯一权威

OpenViking 返回的每个 URI 必须反查回本地文档才允许进上下文：

1. URI → binding（按 `remote_uri` 前缀匹配，provider=OPENVIKING）；查不到 → 丢弃并记
   `EVIDENCE_REJECTED(reason=NO_BINDING)`。
2. binding 必须 `projection_status=IN_SYNC` 且 `observed_version == desired_version`；
   否则丢弃，`reason=VERSION_NOT_VERIFIED`。
3. 文档必须 `enabled=true`、`deleted_at IS NULL`、`superseded_by_document_id IS NULL`；
   否则丢弃，`reason=DOCUMENT_NOT_ACTIVE`。
4. 文档的 `knowledge_base_id` 必须落在本次 scope 内；否则丢弃，`reason=OUT_OF_SCOPE`。
   这条把"跨知识库证据"从"希望不会发生"变成"结构上不可能"。

被丢弃的候选必须留痕（数量 + 原因分布），否则 allowlist 变成静默黑洞，
评测时会把"检索没召回"和"召回了但被拒"混为一谈。

需要新增 binding 的按 URI 反查能力（现有 store 只有 by-provider+documentId）。

### 4.4 三层导航循环与预算

按上游计划 §14.2 实现 L0 → 选候选 → L1 → 证据门 → L2 → 评估 → 改写 → 回到 L0。
预算取 §14.3 默认值，全部可配置：

| 限制 | 默认 | 配置键 |
| --- | ---: | --- |
| 最大轮数 | 3 | `rd.rag.navigator.max-rounds` |
| 每轮 L0 候选 | 20 | `rd.rag.navigator.l0-candidates` |
| L1 展开 | 6 | `rd.rag.navigator.l1-expansions` |
| L2 展开 | 3 | `rd.rag.navigator.l2-expansions` |
| 总远端请求 | 15 | `rd.rag.navigator.max-remote-calls` |
| 总检索 Token | 8000 | `rd.rag.navigator.token-budget` |
| 总耗时 | 20s | `rd.rag.navigator.time-budget` |

停止条件（每个都要有对应的 `stop_reason` 枚举值并被测试覆盖）：
证据类型已满足、连续一轮信息增益低、候选重复、预算耗尽（分轮数/请求/token/时间四种）、
远端不可用、需要用户补充输入。

Prompt injection 防线（上游 §14.4）：L0/L1/L2 文本一律包进 `<untrusted_knowledge>` 数据区；
导航只能选 query/URI/层级，不能改工具权限或调任意 URL；每个 URI 过 owned-root 与 scope 校验；
文档里的指令性文本不得成为 system/developer/tool 指令。

### 4.5 评测必须用真实语料，且两种语料分别报告

`rag/src/test/resources/openviking-baseline/`（32 篇含唯一 ASCII token 的合成文档）**不能**
单独作为切流依据：它偏向精确 token 匹配，会系统性高估。评测必须同时给出：

- 真实语料集（当前 54 篇真实文档，含中文飞书文档与代码文件）；
- 合成语料集（保留作回归，标注"高估"）。

每题记录：LOCAL 命中集、OPENVIKING 命中集、引用 URI/version 是否有效、上下文 token、
端到端延迟、以及被 allowlist 拒绝的候选数与原因。

## 5. 分阶段任务

### Stage A：读端口 + Router + 模式配置（阻塞后续）

**Files:**
- New: `rag/src/main/java/com/wish/rd/rag/retrieval/navigator/ExternalKnowledgeNavigatorPort.java`
- New: `rag/src/main/java/com/wish/rd/rag/retrieval/navigator/model/`（query/search/document/content 记录）
- New: `bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/impl/OpenVikingRestNavigatorAdapter.java`
- New: `bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/impl/DisabledExternalKnowledgeNavigatorPort.java`
- New: `engine/src/main/java/com/wish/rd/engine/retrieval/KnowledgeRetrievalModeRouter.java`
  （实现 `RequirementKnowledgeSearchPort`，装饰现有 adapter）
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/rag/RetrievalRunConfiguration.java`（或新增配置类）
- Modify: `bootstrap/src/main/resources/application.yaml`
- Test: Router 三模式行为、adapter 契约（mock exchange，fixture 用真实响应形状）

行为契约：
- 模式解析：`LOCAL`（默认）/`SHADOW`/`OPENVIKING`，非法值启动即失败并列出合法取值，
  不许静默退回默认——静默会让"以为在 SHADOW"变成长期误判。
- `LOCAL`：Router 直接委托，**零**远端调用（测试断言 navigator 端口零交互）。
- `SHADOW`：返回值必须与纯 LOCAL 逐字节相同；OpenViking 抛异常时也相同。
- navigator adapter 越出 owned root 的 URI 不发请求，直接 `CONFIGURATION_BLOCKED`。
- `ready()` 与写端口共用同一个 key supplier，不得各判一套。

验证：`./mvnw -q -pl engine -am -Dtest=KnowledgeRetrievalModeRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`
和 `-pl bootstrap -am -Dtest=OpenVikingRestNavigatorAdapterTest`

### Stage B：证据 allowlist

**Files:**
- New: `rag/src/main/java/com/wish/rd/rag/retrieval/navigator/KnowledgeEvidenceAllowlist.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/projection/store/KnowledgeExternalIndexBindingStore.java`
  （加按 `remote_uri` 反查）
- Modify: `bootstrap/.../persistence/impl/PostgresKnowledgeExternalIndexBindingStore.java`
  + mapper（注意：`#{param} IS NULL` 必须带 `jdbcType`，见 RULE.md 与
  `PersistenceImplementationPolicyTest`）
- Test: 四条拒绝理由各一例 + 跨知识库必被拒 + 拒绝留痕

必测：desired≠observed 的 binding 必被拒；`enabled=false`、tombstone、superseded 文档必被拒；
另一个 KB 的文档必被拒；每种拒绝都出现在 artifact 里。

### Stage C：三层导航循环 + 预算停止门

**Files:**
- New: `rag/src/main/java/com/wish/rd/rag/retrieval/navigator/ThreeTierNavigationEngine.java`
- New: 预算/停止原因模型 + `NavigatorSettings`
- Modify: Router 在 SHADOW/OPENVIKING 下调用本引擎
- Modify: artifact 记录（复用 `RetrievalRunLifecycle`，不新建表）
- Test: 每个 stop_reason 一例；预算耗尽返回部分结果；`<untrusted_knowledge>` 包装；
  L1/L2 展开数受限；总请求数受限

必测：远端每轮都返回新候选时，请求数不得超过 `max-remote-calls`；
时间预算到点必须返回已有证据而不是抛异常；文档正文里写着"忽略以上指令"不得改变任何工具行为。

### Stage D：评测与报告

**Files:**
- New: 真实语料 gold questions（从当前 54 篇真实文档生成，人工确认答案文档）
- New: 评测 runner（比较 LOCAL vs OPENVIKING，输出 Recall@K/引用有效率/token/p50/p95）
- New: `docs/superpowers/qa/` 下的评测报告
- Modify: `RULE.md` 记录评测基线与复现命令

报告必须显式声明嵌入档（`dashscope`）与语料集（真实/合成分开），
并明确写出"本报告不构成切流决定"。

## 6. 风险与对策

| 风险 | 对策 |
| --- | --- |
| SHADOW 拖慢角色派发 | 并发 + 独立超时；超预算丢弃 OpenViking 结果；测试断言 LOCAL 返回值不受影响 |
| allowlist 变成静默黑洞 | 每条拒绝带原因入 artifact，评测报告必须给出拒绝分布 |
| 用合成语料自我感觉良好 | 真实/合成两套分别报告，切流只看真实语料 |
| 导航循环无界追问 | 四类预算 + 六种 stop_reason 全部被测试钉住 |
| 读路径误碰写路径 | 读写端口物理分离；navigator 端口无任何 binding/outbox 依赖 |
| 远端旧版本混入上下文 | allowlist 第 2 条：`observed_version == desired_version` 才放行 |

## 7. Stage 0 已写入 RULE.md 的新约束

- 谈检索质量必须先声明嵌入档；`mock` 档数字无决策效力；`dashscope` 档实测数据与复现方式。
- `fs/ls` / `fs/attrs` 的 404 是确定的否定观测，`listTree` 返回空列表；
  IN_SYNC 缺失探针独立于列目录成败。
- 返回 `Optional` 的 `findById` 对空 id 返回空，不抛。
- 管理台"死信"页只列 `DEAD_LETTER`，`NEEDS_HUMAN` 要走 `/documents/{id}/retry`。
