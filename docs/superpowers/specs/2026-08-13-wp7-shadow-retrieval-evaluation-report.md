# WP-7 Stage D 检索质量评测报告

日期：2026-08-13
状态：评测已跑完；**本报告不构成切流决定**。切流建议见 §12，效力仅限本快照。
范围：同一批 20 道金标题，对照 LOCAL（pgvector 哈希词袋）与 OpenViking 三层导航（`ThreeTierNavigationEngine` + allowlist）。不改投影、不写 PostgreSQL、不重索引、不重启容器。

相关文档：

- 实施计划：`docs/superpowers/plans/2026-08-13-wp7-shadow-retrieval-and-three-tier-agent-plan.md`
- 变更设计：`openspec/changes/openviking-shadow-retrieval/design.md`
- 质量报告要求：`openspec/changes/openviking-shadow-retrieval/specs/knowledge/openviking-shadow-retrieval/spec.md`（「检索质量报告必须声明嵌入档与语料集」）
- 仓库约束：`RULE.md` §3.5.13；检索质量声明见 §3.5.6 附近「谈检索质量必须先声明嵌入档」
- 金标题：`bootstrap/src/test/resources/openviking-eval/waimai-gold-questions.json`
- 真机原始 JSON（gitignore，不入库）：`tmp/wp7-shadow-eval-results.json`

## 0. 一句话结论

在这份 45 篇、单域、外卖演示应用语料上，OpenViking 三层路径的文档召回明显高于 LOCAL 哈希词袋（Recall@8 **0.8922** vs **0.2353**），但两条路径对 3 道故意不可答题的误引率都是 **1.0**，OpenViking 从未以 `EVIDENCE_SUFFICIENT` 停下来（修掉词面门与改写器后重跑仍是 0），上下文 Token 约 5 倍、延迟约三个数量级。**默认读路径应继续停在 LOCAL**；SHADOW 值得开，用来继续积累对照，而不是把 OPENVIKING 设成默认。

这组数字不能外推到多域、多语言或「中文飞书文档」语料。`RULE.md` 里那次 Recall@8 = 52/52 是正文自查，测的是自检索，不是问答，本报告不引用为质量主张。

## 1. 语料与嵌入档

| 项 | 值 |
| --- | --- |
| 评测日 | 2026-08-13 |
| 嵌入档 | `dashscope` |
| 模型 | `text-embedding-v4` |
| 维度 | 1024 |
| 知识库 | `7475766492030701568`（名称 `waimai`） |
| 文档数 | **45**，全部 `projection_status='IN_SYNC'`，均长约 3600 字 |
| 为何选它 | 另外两个库 `7474332748799414272`、`7474342409749532672` 各 4–5 篇、均长不足 120 字，是 QA fixture，不能代表检索质量 |
| 与「54 篇」的关系 | design.md / 计划里的 54 = 三个已投影库合计（45+5+4），不是本题集的语料 |
| 内容 | 外卖（waimai）TypeScript / React 演示：README、启动脚本、SQLite schema、支付回调手册与推荐修复代码、订单/优惠券/骑手路由、鉴权中间件、前端代理 |
| 不是什么 | 不是「中文飞书文档」语料 |

LOCAL 侧向量实现是生产代码 `PostgresVectorStore#embed`：对文本做哈希词袋，维度 **1536**，与 OpenViking 的 1024 维 DashScope 嵌入不是同一套向量。对照的是「今天需求交付实际在用的本地检索」对「Stage C 三层导航」，不是「同一嵌入模型的两种召回器」。

## 2. 金标题：如何避免抄正文、如何避免唯一 token

题集 20 道，提交在 `bootstrap/src/test/resources/openviking-eval/waimai-gold-questions.json`。先 `SELECT` 45 篇 `raw_content`，再按工程师会问的故障/对照来写，期望文档 id 全部来自读过的正文，没有编造文档。

构成：

| 类型 | 题号 | 数量 |
| --- | --- | --- |
| `SINGLE_DOCUMENT` | Q01–Q11 | 11 |
| `MULTI_DOCUMENT`（答案跨 2–3 篇，含语料内部不一致） | Q12–Q17 | 6 |
| `UNANSWERABLE`（域内合理、语料里没有） | Q18–Q20 | 3 |

防高估的硬约束：

- 问句禁止整段或近邻改写答案文档的连续片段。例如支付手册写「回调确认成功后必须改订单状态」，Q01 写成「用户已经看到第三方扣款成功，但订单详情还停在等付款」——场景语言，不是手册句子。
- 禁止把源码里的独特 ASCII 标识写进问句。结构门 `WaimaiGoldQuestionsTest` 拒绝 `RD_WP0_`、`PENDING_PAYMENT`、`markPaid`、开发密钥字面量、手册英文标题句。Q02 问「用哪个订单方法把状态写成已付」，不点方法名。
- 多文档题问的是对照，不是复述其中一篇。Q12 问 README 与 seed 的顾客账号是否同名（`customer1` vs `user1`）；Q13 问 SQLite CHECK 列表与支付手册三态是不是同一套词；Q17 问 README 路径与 `riders.ts` 实际路由是否同一条。
- 不可答题选微信支付证书轮换、骑手超时改派 SLA、个保法注销删除期限——外卖演示里没有这些流程，但问法在支付/配送语境里说得通，用来测误引。

这不是「每篇摘 200 字再搜回去」。那种探针在 `RULE.md` 已记录 Recall@8 = 52/52，测的是自检索。

## 3. 评测装置

门控与仓库既有真机合同相同：`@EnabledIfSystemProperty(named = "rd.openviking.smoke", matches = "true")`，与 `OpenVikingRealContractSmokeTest` 同一开关。普通 `./mvnw test` 跳过。

**不启动 Spring Boot。** `rd.knowledge.projection.mode=ON` 时投影 Worker 会写 outbox/binding，和「评测只读」冲突。

两条路径、同一批题：

| 路径 | 实际调用的生产代码 | 没有调用什么 |
| --- | --- | --- |
| LOCAL | `PostgresVectorStore`（哈希词袋，1536 维）+ `ProjectScopedRequirementKnowledgeSearchAdapter` 两参构造（经验通道关闭，见该文件 :50–55）→ `MultiChannelRetrievalEngine` | 没有另写一套本地检索 |
| OPENVIKING | `ThreeTierNavigationEngine` + `KnowledgeEvidenceAllowlist` + `OpenVikingRestNavigatorAdapter` | 没有绕过 allowlist 的裸 HTTP；也没有走 `KnowledgeRetrievalModeRouter` 的 OPENVIKING 模式 |

不走 Router 的原因：`KnowledgeRetrievalModeRouter#shouldDegrade`（`:151–157`）在证据为空或 `NOTHING_ADMITTED` / `REMOTE_UNAVAILABLE` 时回落 LOCAL。若评测走 Router，空结果会被 LOCAL 命中填上，切流数字会说谎。本次 20 题实际上从未走到这条降级（证据非空且停止原因不是那两个），但装置仍直接打引擎，避免将来默认门一变就把对照污染。

延迟：每题每路径 **1 次预热不计入** + **5 次计时**；百分位与 WP-0 基线同一 nearest-rank：`ceil(p * n) - 1`。LOCAL 与 OPENVIKING 各 **n = 100**（20 题 × 5 次）。延迟按整毫秒截断；LOCAL 大量样本是 0/1 ms，p50=0 表示亚毫秒到 1 ms，不是「零耗时」。

Token：两边都用 `NavigatorTokenEstimator`（`(length + 3) / 4`）。LOCAL 估的是候选 `summary()` 之和；OPENVIKING 估的是引擎累计的围栏 L1/L2 正文。公式相同，计入的文本层级不同，OPENVIKING 更高一部分来自「展开了 overview/read」，不只是「召回了更多相关文档」。

K = 8，与需求交付 `recordOnly(topK=8)` 一致。

密钥只从环境变量 `OPENVIKING_API_KEY` 或 gitignore 文件 `tmp/openviking-admin.key` 读取；Surefire 的 `user.dir` 是 `bootstrap/`，harness 用仓库根上的 `RULE.md` 定位该文件。本报告不抄密钥内容。

## 4. 复现命令与通过计数

金标题结构门 + 指标单元（不连真机）：

```bash
./mvnw -pl bootstrap -am \
  -Dtest=WaimaiGoldQuestionsTest,ShadowRetrievalEvaluationMetricsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

**结果：Tests run: 6, Failures: 0, Errors: 0, Skipped: 0。BUILD SUCCESS。**

未开真机开关时，评测类必须跳过：

```bash
./mvnw -pl bootstrap -am \
  -Dtest=WaimaiGoldQuestionsTest,ShadowRetrievalEvaluationMetricsTest,OpenVikingShadowRetrievalEvaluationRealSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

**结果：Tests run: 7, Failures: 0, Errors: 0, Skipped: 1。BUILD SUCCESS。** 跳过的是 `OpenVikingShadowRetrievalEvaluationRealSmokeTest`。

真机评测（需本机 Postgres `ragent`、OpenViking `http://127.0.0.1:1933`、dashscope 档已投影）：

```bash
./mvnw -pl bootstrap -am \
  -Dtest=OpenVikingShadowRetrievalEvaluationRealSmokeTest \
  -Drd.openviking.smoke=true \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

可选：`-Drd.openviking.eval.reps=5`（默认 5）、`-Drd.openviking.eval.output=...`、`OPENVIKING_API_KEY` 或默认读 `tmp/openviking-admin.key`。

**结果：Tests run: 1, Failures: 0, Errors: 0, Skipped: 0。BUILD SUCCESS。** 墙钟约 162.5 s。写出 `tmp/wp7-shadow-eval-results.json`。跨知识库准入断言为 0。五次计时之间排序不一致次数为 0。

合成语料回归（必须标注系统性高估，不得单独作切流依据）：

```bash
./mvnw -pl rag -am \
  -Dtest=OpenVikingLocalRetrievalBaselineTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

**结果：Tests run: 1, Failures: 0。BUILD SUCCESS。** 该测试在 WP-0 合成 fixture 上要求 Recall@8 = 1.0，见 §9。

包隔离政策（证明本 Stage 没有新增违规）：

```bash
./mvnw -pl bootstrap -am \
  -Dtest=ModelPackageIsolationPolicyTest,ImplementationPackageIsolationPolicyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

**结果：Tests run: 3, Failures: 2。** 失败类与 HEAD 上既有 `engine/retrieval/iterative`、`engine/evaluation` 违规相同，见 §13。未改那些模块。

## 5. 指标总表（真实语料，dashscope）

Recall 是文档级 `|prefix ∩ expected| / |expected|`，只对 17 道有期望文档的题求平均；3 道不可答题不进入 Recall，避免空期望把召回抬成 1。引用有效率是 `|ranked ∩ expected| / |ranked|`，**含**不可答题（返回了文档且期望为空 → 0）。误引率 = 不可答题中「返回了任意文档」的比例。

| 指标 | LOCAL | OPENVIKING |
| --- | ---: | ---: |
| Recall@1 | 0.0000 | 0.5196 |
| Recall@5 | 0.0882 | 0.8039 |
| Recall@8 | 0.2353 | 0.8922 |
| 引用有效率（相对金标题期望集） | 0.0442 | 0.1313 |
| 不可答题误引率 | **1.0000**（3/3） | **1.0000**（3/3） |
| 平均上下文 Token | 727.85 | 3678.7 |
| 延迟 p50（ms） | 0 | 1119 |
| 延迟 p95（ms） | 1 | 2568 |
| 延迟样本数 | 100 | 100 |
| 跨知识库准入 | — | **0** |

引用有效率低，主要是结构原因，不是「OpenViking 乱指」：引擎按预算展开后通常交出约 8 篇去重文档，单文档题期望集大小为 1，即便 top-1 正确，有效率也约 1/8 = 0.125。Q01–Q11 的 OPENVIKING 引用有效率几乎全是 0.125，与此一致。不可答题的 0 把总平均从约 0.154（仅 17 道可答题）拉到 0.1313。

「引用 URI/version 有效率」若按 allowlist 语义理解（返回的都是 `IN_SYNC` 且版本对齐的本库文档），OPENVIKING 侧是 **1.0（由构造保证）**：未准入的 URI 进不了 `NavigatorRunResult.evidence()`。本表报告的是相对金标题期望集的精确率，因为切流要回答的是「引的是不是能答题的那几篇」，不是「引的是不是合法投影」。两种数字都需要；只报构造性的 1.0 会掩盖误引。

LOCAL Recall@1 = 0：哈希词袋在改写问句上几乎排不出正确答案到第一位。它不是「坏了」，是这种嵌入对释义不敏感。

## 6. 停止原因与 allowlist 拒绝

20 题、第一次计时（预热之后）的 `NavigatorStopReason`：

| stop_reason | 次数 |
| --- | ---: |
| `REMOTE_CALL_BUDGET_EXHAUSTED` | 20 |
| `DUPLICATE_CANDIDATES` | 0 |
| `EVIDENCE_SUFFICIENT` | **0** |
| `LOW_INFORMATION_GAIN` | 0 |
| `ROUND_BUDGET_EXHAUSTED` | 0 |
| `TOKEN_BUDGET_EXHAUSTED` | 0 |
| `TIME_BUDGET_EXHAUSTED` | 0 |
| `REMOTE_UNAVAILABLE` | 0 |
| `NOTHING_ADMITTED` | 0 |
| `NEEDS_USER_INPUT` | 0 |

对应远端次数：`DUPLICATE_CANDIDATES` 的题 `remoteCalls=11`；`REMOTE_CALL_BUDGET_EXHAUSTED` 的题 `remoteCalls=15`（默认上限）。

allowlist 拒绝 tally（全程合计）：

| 原因 | 次数 |
| --- | ---: |
| `NO_BINDING` | 0 |
| `VERSION_NOT_VERIFIED` | 0 |
| `DOCUMENT_NOT_ACTIVE` | 0 |
| `OUT_OF_SCOPE` | 0 |

本快照 45 篇全 `IN_SYNC`、评测 scope 只有这一个 KB，拒绝为 0 说明「召回后被拒」没有发生；质量差异来自「远端召回了什么 / 默认停止门何时认为够了」，不是 allowlist 误杀。跨库准入 0 与 `OUT_OF_SCOPE=0` 一致：L0 没有把另外两个 QA 库的短文档送进来。

## 7. 逐题对照

R@k 为该题文档召回。不可答题 Recall 为 NaN，不参与 §5 平均。

| 题 | 类型 | LOCAL R@1/@5/@8 | OV R@1/@5/@8 | OV 停止 | 备注 |
| --- | --- | --- | --- | --- | --- |
| Q01 | 单 | 0/0/0 | 1/1/1 | DUP | 回调手册排第一 |
| Q02 | 单 | 0/0/0 | 0/1/1 | DUP | 手册压过 `PaymentCallbackService`，第二才对 |
| Q03 | 单 | 0/0/1 | 1/1/1 | DUP | LOCAL 第六才碰到支付字段短文 |
| Q04 | 单 | 0/0/0 | 0/1/1 | REMOTE | 支付三态短文压过 `schema.sql` |
| Q05 | 单 | 0/0/0 | 1/1/1 | REMOTE | `start.sh` |
| Q06 | 单 | 0/0/0 | 1/1/1 | REMOTE | `auth` 中间件 |
| Q07 | 单 | 0/1/1 | 1/1/1 | REMOTE | LOCAL 唯一 Recall@5=1 的单文档题 |
| Q08 | 单 | 0/0/1 | 1/1/1 | DUP | LOCAL 第八才到 `database.ts` |
| Q09 | 单 | 0/0/0 | 0/1/1 | REMOTE | `start.sh` 压过 `vite.config.ts` |
| Q10 | 单 | 0/0/0 | 1/1/1 | REMOTE | `coupons.ts` |
| Q11 | 单 | 0/0/0 | 0/0/1 | DUP | 骑手前端页排前，`riders.ts` 第七 |
| Q12 | 多 | 0/0/0.5 | 0.5/0.5/0.5 | DUP | 两边都只拿到 seed，README 的 `customer1` 未入围 |
| Q13 | 多 | 0/0/0 | 0.33/0.67/0.67 | DUP | 缺 `schema.sql`；支付手册 + `orders.ts` |
| Q14 | 多 | 0/0/0 | 0/1/1 | REMOTE | top-1 是中间件，@5 收齐三篇 |
| Q15 | 多 | 0/0.5/0.5 | 0.5/0.5/1 | REMOTE | OV @8 才补上 schema |
| Q16 | 多 | 0/0/0 | 0/0.5/0.5 | REMOTE | `orders.ts` 第五；schema 未入围 |
| Q17 | 多 | 0/0/0 | 0.5/0.5/0.5 | DUP | 命中 `riders.ts`，未命中 README |
| Q18 | 不可答 | — | — | REMOTE | 两边都引了支付/订单相关篇 |
| Q19 | 不可答 | — | — | DUP | 两边都引了骑手相关篇 |
| Q20 | 不可答 | — | — | DUP | 两边都引了订单/鉴权相关篇 |

单文档 11 题：OPENVIKING 在 @8 全中（11/11），@1 仅 7/11。LOCAL @8 只中 3/11（Q03、Q07、Q08）。

需要「两篇对着看才发现不一致」的题（Q12、Q17）两边都只召回了代码/种子，没把 README 带上。语义近邻会偏向实现文件。

## 8. 失败与退化（不只报成功）

1. **默认证据门即便改成词面覆盖率，在真语料上仍然一次都没触发。** 首轮评测时
   `defaultGate` 要求 L1/L2 围栏正文原样包含整句 query，那等于永不触发；已改为
   原子术语（ASCII 术语 + 中文 2-gram）覆盖率 ≥ 0.4，单元测试证明同义改写
   「商品保存时库存怎么校验」对「管理员可以保存商品并校验库存」可以判足、对无关正文判不足。
   但本轮 20 题重跑后 `EVIDENCE_SUFFICIENT` 仍然是 **0**：真实问句术语多、被收进来的
   L1/L2 摘录有截断上限，覆盖率到不了 0.4。**结论是词面门判不了「答上没答上」**，
   继续压阈值只会让无关证据也判足，属于对 20 道题过拟合。真正的出口是替换注入的
   `NavigatorEvidenceGate`（判定模型），不是调这个常数。
2. **默认改写已修好，重复候选归零。** 原实现把 query 改成 `原问句 + " 补充" + 轮次`，
   对嵌入向量几乎没有扰动；已改为转向「未被覆盖的最长术语」。真机对照：
   `DUPLICATE_CANDIDATES` 从 **10 → 0**，即第二三轮确实换了检索方向。
   代价是每题都跑满预算：`REMOTE_CALL_BUDGET_EXHAUSTED` 从 10 → **20**，
   平均 Token 从 2944 → **3679**（延迟略降，p50 1234 → 1119 ms，p95 2754 → 2568 ms）。
   换句话说，修掉「白转一圈」之后，成本变成了「认真转三圈」，而由于第 1 条门判不了足够，
   循环没有提前退出的机会。
3. **没有弃权。** 45 篇同域时 L0 总能返回「看起来相关」的文档。Q18–Q20 语料里没有证书轮换、自动改派、个保法注销，两条路径仍各引 4–8 篇。切到 OPENVIKING 不会让 Agent 学会说「库里没有」。
4. **语义近邻 ≠ 能答题的那一篇。** Q11 问未上线拉待抢列表的行为，排在前面的是骑手前端页，`riders.ts` 第七才出现。Q02/Q04/Q09 top-1 是同主题的手册或启动脚本，正确答案在 @2–@3。
5. **对照类多文档题召回不完整。** Q12/Q13/Q16/Q17 的 OPENVIKING Recall@8 停在 0.5 或 0.67。实现文件进了，README / `schema.sql` 经常不进。LOCAL 更差，但不能据此说 OPENVIKING 已经能做跨文档对账。
6. **LOCAL 在改写问句上接近失效。** Recall@1=0、@8=0.2353。这是哈希词袋的预期行为，不是本次回归。切流决策必须把「LOCAL 很弱」和「OPENVIKING 已经可当默认」分开：前者成立，后者不成立。

## 9. 合成语料（系统性高估）

`OpenVikingLocalRetrievalBaselineTest` 对 `rag/src/test/resources/openviking-baseline/`（含唯一 ASCII token 的 WP-0 样本）要求 Recall@8 = 1.0，2026-08-13 仍通过。

按 spec 与 `RULE.md`：这类 fixture 偏向精确 token 匹配，**会系统性高估召回**，不得单独作为切流依据。它只证明 LOCAL 重叠检索在合成样本上没有回退。真实语料上同一套 LOCAL 的 Recall@8 是 0.2353。两个数字不可混算、不可互相引用。

`RULE.md` 记载的 dashscope 自查 Recall@8 = 52/52（每篇正文中段 200 字、limit=8）同样不得当作本报告的质量数字：那是自检索，不是工程师问句。

## 10. 计划 / 设计 / spec 与真代码、真语料的分歧

下列不一致按「报告分歧、不静默改口径」处理。未改 `RULE.md` 与 `openspec/`（调用方持有）。

1. **「54 篇」不是本题集。** `tasks.md` D1 与 `design.md`「评测设计」写当前 54 篇真实文档。库里三个已投影 KB 合计 54；评测语料是外卖库 **45** 篇。另外 9 篇是短 QA fixture。
2. **「含中文飞书文档」对不上这份 KB。** `design.md:119` 写「含中文飞书文档与代码文件」。`7475766492030701568` 是外卖 TS/React 演示加支付手册，没有飞书文档。若切流假设依赖飞书语料，本评测不能为那条假设背书。
3. **报告路径。** `tasks.md` D4 写 `docs/superpowers/qa/`；本文件按 Stage D 任务说明写在 `docs/superpowers/specs/`。
4. **D4「本报告不构成切流决定」与「必须给出切流建议」同时成立。** 设计禁止把一份 45 篇快照写成授权；任务要求人能据此决定。§12 给建议，并把它的效力限制在本快照。
5. **「引用 URI/version 有效率」未定义分子分母。** 若指 allowlist 通过率，OPENVIKING 返回集是 1.0（构造）。若指相对金标题期望集的精确率，是 0.1313。本报告两个都写。评测按文档 id 对齐，没有再报 URI 字符串与 version marker 的逐字段表；版本对齐已由 allowlist 闸门保证。
6. **LOCAL 可以在 Spring 外立起来。** 计划担心「在 Spring 外立本地检索不切实际」。两参 `ProjectScopedRequirementKnowledgeSearchAdapter` + 生产 `PostgresVectorStore` 即可。没有用替代实现冒充 LOCAL。
7. **测 Router 的 OPENVIKING 模式会在降级时混进 LOCAL。** 见 §3。本评测测的是 Stage C 引擎本身。
8. **`tasks.md` D5（改 `RULE.md`）与 D6（`openspec validate`）本 Stage 未做。** 明确禁止改这两处。

## 11. Stage A/B/C 在真机上暴露的问题

评测先按原样跑了一轮，暴露下面两条后修了生产代码并重跑；表中数字是重跑后的。

1. **`defaultGate` 原为整句 query 子串匹配，已改为原子术语覆盖率**（`ThreeTierNavigationEngine#defaultGate`）。
   修完仍然 `EVIDENCE_SUFFICIENT=0`——词面门在真问句上不可达，见第 8 节第 1 条。
   这条是设计层面的缺口：`NavigatorEvidenceGate` 必须接判定模型才有意义，
   当前默认实现只是「不至于永假」的兜底。
2. **`defaultRefine` 原为追加 `补充N`，已改为转向未覆盖术语**（同类）。
   真机 `DUPLICATE_CANDIDATES` 10 → 0 证明改写生效。
   顺带把 `NavigatorQueryRefiner` 的签名加上了已收集证据：
   只给计数版的 `NavigatorRoundRecord` 根本改不出方向。
3. **三层循环没有「库里没有」出口。** `NOTHING_ADMITTED` 在本跑次为 0。allowlist 全过、L0 非空时，不可答题与可答题走同一套「交 8 篇相关文档」的路径。
4. **Allowlist 在本快照上是空操作（拒绝全 0）。** 它没有坏；45 篇全 `IN_SYNC`、单 KB scope 时测不出 `VERSION_NOT_VERIFIED` / `OUT_OF_SCOPE` 的生产代价。那些分支仍只被单元测试钉住。
5. **Router 降级条件与本次停止原因正交。** 实际停止全是 `REMOTE_CALL_BUDGET_EXHAUSTED`，`shouldDegrade` 不把它当降级。若有人把默认改成 OPENVIKING，Agent 拿到的就是这批「相关但未判定足够、且从不弃权」的证据，不会静默掉回 LOCAL。

未在 A/B/C 里发现「allowlist 放进外库文档」或「越出 owned root 发请求」的缺陷。跨库准入 0。

## 12. 切流建议与限制

**建议：不要把默认 `rd.rag.knowledge-provider-mode` 从 `LOCAL` 改成 `OPENVIKING`。**

可以开 `SHADOW`：OpenViking 结果不进任务上下文，对照成本主要是旁路延迟与 Token，换来的是本语料上真实存在的召回差距。

建议所依据的、以及它所**不能**依据的：

- 支持 SHADOW、反对默认 OPENVIKING 的：Recall@8 0.89 vs 0.24 是改写问句上的差距，不是自检索；误引 100%；默认停止门从未认为证据足够；Token ×4；延迟从亚毫秒到 p95 2.8 s；引用相对期望集的精确率约 0.13。
- 不能支持的普遍结论：换一个知识库、换飞书文档、换多 KB 混合 scope、换更好的改写器或证据门之后，排序可能翻转。45 篇单域演示应用不是「OpenViking 检索质量」的一般证明。
- LOCAL 弱，不等于 OPENVIKING 已可当唯一读路径。切流若发生，应另开变更，并至少先处理：释义下可触发的足够门、不可答题弃权、对照类多文档召回、以及把 p95 从数秒压到角色派发可接受的预算内。

**本报告不构成切流决定。**

## 13. 包隔离政策：既有 vs 新增

HEAD 上已失败、本 Stage **未新增**：

`ModelPackageIsolationPolicyTest.publicDomainRecordsAndEnumsShouldLiveUnderModelPackages`：

- `engine/retrieval/iterative`：`RetrievalStopReason`、`IterativeRetrievalPolicy`、`RetrievalIterationLimits`、`RetrievalRoundAudit`、`RetrievalIterationState`
- `engine/evaluation`：`CodingBenchmarkCaseRuntime`

`ImplementationPackageIsolationPolicyTest.publicInterfaceImplementationsShouldLiveUnderImplPackages`：

- `engine/retrieval/iterative/InMemoryRetrievalRoundAuditStore`

`ImplementationPackageIsolationPolicyTest.anExemptionMustBeBackedByADocumentedAggregateRoot` 仍通过。

本 Stage 新增类型在 `bootstrap/.../openviking` 测试包与 `rag/.../navigator`（A/B/C 已有）之外没有新的生产 `.model` / `.impl` 违规。未修复上述无关模块。
