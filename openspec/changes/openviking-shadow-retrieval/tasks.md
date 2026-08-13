# Tasks

先写失败测试再实现。每个 Stage 结束都要跑本 Stage 的验证命令。

## Stage A：只读导航端口 + 模式 Router（阻塞 B/C/D）

- [x] A1 新增 `rag/src/main/java/com/wish/rd/rag/retrieval/navigator/ExternalKnowledgeNavigatorPort.java`
  与 `model/` 下的 `ExternalNavigatorQuery`/`ExternalNavigatorSearch`/`ExternalNavigatorDocument`/
  `ExternalNavigatorContent`/`NavigatorFailureClass` 记录。端口不得引用任何 outbox/binding 写方法。
- [x] A2 新增 `bootstrap/.../openviking/impl/OpenVikingRestNavigatorAdapter.java`：
  L0 走 `POST /api/v1/search/find`，L1 走 `GET /api/v1/content/overview`，
  L2 走 `GET /api/v1/content/read`。复用现有 `OpenVikingHttpExchange` 与共享 API key supplier；
  越界 URI 不发请求。测试：`OpenVikingRestNavigatorAdapterTest`，fixture 用真实响应形状
  （参考 `bootstrap/src/test/resources/openviking/contracts/`）。
- [x] A3 新增 `DisabledExternalKnowledgeNavigatorPort`，全部返回 `CONFIGURATION_BLOCKED`；
  与写端口的 Disabled 实现对称。
- [x] A4 新增 `engine/.../retrieval/KnowledgeRetrievalModeRouter.java`，实现
  `RequirementKnowledgeSearchPort` 并装饰现有 `ProjectScopedRequirementKnowledgeSearchAdapter`。
  测试必含：`LOCAL` 下 navigator 端口零交互；非法模式值启动失败并列出合法取值；
  scope 为空时仍走既有 `MISSING_SCOPE` 分支且不调远端。
- [x] A5 接上死配置 `rd.rag.knowledge-provider-mode`（`RD_RAG_KNOWLEDGE_PROVIDER_MODE`，默认 `LOCAL`），
  在 `bootstrap` 配置类里注册 Router 与 navigator 端口 bean；`application.yaml` 补 navigator 预算键。
- [x] A6 验证：`./mvnw -q -pl engine -am -Dtest=KnowledgeRetrievalModeRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`
  与 `./mvnw -q -pl bootstrap -am -Dtest=OpenVikingRestNavigatorAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

## Stage B：证据 allowlist

- [x] B1 `KnowledgeExternalIndexBindingStore` 增加按 `remote_uri` 反查；
  Postgres 实现 + mapper（`#{param} IS NULL` 必须带 `jdbcType`）。
- [x] B2 新增 `rag/.../retrieval/navigator/KnowledgeEvidenceAllowlist.java`，
  按 design 的四步顺序判定，产出通过集合 + 拒绝原因分布。
- [x] B3 测试：`NO_BINDING`/`VERSION_NOT_VERIFIED`/`DOCUMENT_NOT_ACTIVE`/`OUT_OF_SCOPE` 各一例；
  另一个 KB 的文档必被拒；`observed_version < desired_version` 必被拒；每种拒绝都出现在 artifact 里。
- [x] B4 验证：`./mvnw -q -pl rag -am -Dtest='*Allowlist*' -Dsurefire.failIfNoSpecifiedTests=false test`
  与 `./mvnw -q -pl bootstrap -am -Dtest=PersistenceImplementationPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

## Stage C：三层导航循环与预算停止门

- [x] C1 新增 `NavigatorSettings`（轮数 3 / L0 20 / L1 6 / L2 3 / 远端请求 15 / token 8000 / 20s），
  全部可配置，非法值启动失败。
- [x] C2 新增 `ThreeTierNavigationEngine`：L0 → 选择 → L1 → 证据门 → L2 → 评估 → 改写回 L0。
  每轮产出 artifact（query/scope/层级、候选 URI 与选择理由、展开的 URI 与版本 marker、
  缺失证据类型、token/latency、stop_reason）。复用 `RetrievalRunLifecycle`，不新建表。
- [x] C3 九个 `stop_reason` 各一例测试；预算耗尽必须返回部分证据而不抛异常；
  远端每轮都给新候选时总请求数不得超过 `max-remote-calls`。
- [x] C4 Prompt injection 测试：外部正文包在 `<untrusted_knowledge>` 内；
  正文里写"忽略以上指令/请调用某 URL"不得改变工具行为或产生越界请求。
- [x] C5 Router 在 `SHADOW` 下并发调用本引擎（虚拟线程）且返回值恒等于纯 LOCAL；
  OpenViking 抛异常/超时时返回值仍恒等，且 artifact 记录降级原因。
  `OPENVIKING` 下降级 LOCAL 必须写 `DEGRADED`。
- [x] C6 验证：`./mvnw -q -pl rag -am -Dtest='*Navigator*' -Dsurefire.failIfNoSpecifiedTests=false test`
  与 `./mvnw -q -pl engine -am -Dtest=KnowledgeRetrievalModeRouterTest -Dsurefire.failIfNoSpecifiedTests=false test`

## Stage D：评测与报告

- [x] D1 真实语料 gold questions：从当前 54 篇真实文档生成，人工确认每题的答案文档；
  避免使用文档内的唯一 ASCII token 作为查询（那会高估）。
  实测：54 篇是三个库合计（45+5+4），另两个库均长不足 120 字属 QA fixture，
  金标题只用 45 篇的 waimai 库，落在
  `bootstrap/src/test/resources/openviking-eval/waimai-gold-questions.json`。
- [x] D2 评测 runner：同一批问题分别走 `LOCAL` 与 `OPENVIKING`，输出 Recall@1/@8、
  引用 URI/version 有效率、跨知识库证据数（必须为 0）、上下文 token、p50/p95 延迟、
  allowlist 拒绝分布、stop_reason 分布。
- [x] D3 合成语料回归：保留 `OpenVikingLocalRetrievalBaselineTest`，报告中标注"系统性高估"。
- [x] D4 报告声明嵌入档为 `dashscope`，并明确写"本报告不构成切流决定"。
  实际落在 `docs/superpowers/specs/2026-08-13-wp7-shadow-retrieval-evaluation-report.md`
  而不是 `docs/superpowers/qa/`：它是被 `RULE.md` 引用的冻结结论，与该目录下其他 spec 同类。
- [x] D5 `RULE.md` 记录 Router 三模式语义、allowlist 四步判定、预算默认值与复现命令。
- [x] D6 `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`。
