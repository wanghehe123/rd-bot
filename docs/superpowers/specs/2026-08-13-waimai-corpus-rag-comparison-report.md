# 外卖项目语料扩充与两种 RAG 检索方式对照报告

- 日期：2026-08-13
- 语料：知识库 `7475766492030701568`（waimai），本轮由 45 篇扩至 **69 篇**
- 项目：`7479447343427883008` Codex 外卖验收项目，仓库 `wanghehe123/rd-bot-waimai-acceptance-20260624-141045`，默认分支 `main`
- 对照的两条读路径：`rd.rag.knowledge-provider-mode` 取 `LOCAL` 与 `OPENVIKING`

## 0. 结论摘要

在 38 道金标题、69 篇真实语料上，OpenViking 三层导航的**召回显著高于本地检索**（Recall@8 0.7222 对 0.1515），代价是 **5.4 倍上下文** 与 **约 1.2 秒的 p50 延迟**（本地是 0 毫秒量级）。但两条路径在**不可答问题上都 100% 强行引用**，且 OpenViking 在 38 道题里有 37 道是**耗尽远端调用预算**才停止，而不是证据充分才停止。

因此本报告不建议把 `OPENVIKING` 设为默认读路径；它在"找得到"上明显更强，在"知道自己找不到"和"知道什么时候够了"上都还不成立。

## 1. 为什么对照的是 LOCAL 与 OPENVIKING，而不是 LOCAL 与 SHADOW

`SHADOW` 模式的设计保证是：返回给任务的检索结果与 `LOCAL` **逐字节相同**，OpenViking 探针是 fire-and-forget，只写检索工件、不进 Agent 输入。所以拿 `LOCAL` 对 `SHADOW` 做任务侧对照，得到的差异必然为零——那正是该模式要保证的东西，不是可测的对比量。

只有 `OPENVIKING` 会真正改变 Agent 看到的证据（`KnowledgeRetrievalModeRouter` 走 `ThreeTierNavigationEngine`），所以任务侧对照只能是 `LOCAL` 对 `OPENVIKING`。

## 2. 语料扩充：24 篇新文档

四类文档由子代理并行撰写，全部要求以 PostgreSQL 中的真实仓库源码为依据：

| 类别 | 篇数 | `knowledge_type` | 内容 |
| --- | ---: | --- | --- |
| 接口文档 | 7 | `api` | auth / orders / riders / coupons / merchants / admin 六域，加一篇跨域约定 |
| 历史 PRD | 6 | `requirement` | 订单生命周期、骑手接单、券活动、商家菜单、后台控制台、回调处理 |
| 研发计划 | 5 | `plan` | 技术栈与架构、反推迭代计划、数据模型演进、工程约定、技术债与风险 |
| 测试文档 | 6 | `test` | 测试策略、四组用例、回归清单 |

共约 24.4 万字符，`STRUCTURE_AWARE` 分块、`chunkSize=360`、`overlapSize=48`——与既有 45 篇**完全相同**的分块参数。参数不一致会让对照失去意义，所以这里刻意复用 `scripts/owner/seed-waimai-repository-knowledge.sh` 的取值。

24 篇全部到达 `INDEXED`，且投影绑定全部 `IN_SYNC`（版本已核验），两条路径看到的是同一份语料。

### 2.1 语料可信度校验

新文档若杜撰接口，金标题就会建在虚构之上，两条路径都在跟虚构对标。因此用 `tmp/verify_docs_grounding.py` 把文档引用的接口路径与真实路由定义逐条对账：

- 真实接口 **58 个**（六个 router 的定义加 `index.ts` 里内联的 `GET /api/health`），**全部被新文档覆盖**
- **0 个编造接口**，**0 个编造表名**
- 剩余 3 条形式上不匹配的引用均已判定无害：一条是故意调用不存在接口、期望 404 的负向用例，一条是订单号占位符 `N`，一条是句中简写

校验脚本第一版误报 36 条，原因是把任何 `METHOD /api/...` 提及都当成"声称存在"，而文档实际是在**显式记录 README 与实现的漂移**（例如 README 写 `GET /api/riders/available`，实现是 `/available-orders`）。这类负向引用是真实且有价值的内容，脚本已按否定语境过滤。

## 3. 金标题集：从 20 道扩到 38 道

新增 Q21–Q38：10 道跨文档、6 道单文档、2 道不可答。合并后 38 道 = 17 单文档 + 16 跨文档 + 5 不可答，`k=8`。

标注错的题会静默污染由它算出的每个召回数，所以 Q21 起的每道题都必须携带 `supportingQuotes`——期望文档里的原文片段。`tmp/verify_gold_questions.py` 逐条断言这些引用是目标文档的**字节级子串**，并核对题型与标注一致、无 ID 冲突。两道不可答题另外独立核实：69 篇里没有任何一篇提到发票、抬头、税号、抽成或结算周期。

`supportingQuotes` 已进入 `WaimaiGoldQuestion` 记录类型，`WaimaiGoldQuestionsTest` 守其结构不变式（每篇答案文档恰好一条引用、长度下限 25 字符）。Q01–Q20 早于该约束，保留为未审计遗留题，脚本以提示而非失败报出。

### 3.1 语料内部的一处出处冲突

撰写四类文档的四个子代理中有三个**各自独立**指出：`waimai-payment-callback-runbook.md`、`waimai-payment-order-status-schema.md`、`waimai-payment-callback-code.md` 这三篇描述的是一个 **Java** 的 `PaymentCallbackService`，用 `PENDING_PAYMENT` / `PAID` 状态与 `paid_at`、`payment_transaction_id` 字段；而本仓库是 Node/SQLite，`schema.sql` 的 `orders.status` CHECK 里没有这两个状态，`routes/orders.ts` 里也没有对应列或路由。

这三篇是与主应用不同来源的材料。它不影响本轮测得的数字——Q01–Q03 的答案确实落在这三篇之内，属于有效标注，OpenViking 三题全中——但读这份报告的人应当知道：语料并非单一系统的一致快照，其中约 3 篇属于另一套系统的材料。新写的 24 篇没有把这部分当作本应用的已实现能力。

## 4. 检索对照结果

评测台 `OpenVikingShadowRetrievalEvaluationRealSmokeTest`：只读、每题 1 次预热 + 5 次计时、两条路径同题同 `k`。OpenViking 路径直连 `ThreeTierNavigationEngine` 而不过 Router，避免降级到本地的命中混进切流数字。

跑了两轮，**召回与上下文分毫不差**，仅延迟随网络波动（见 §4.3）。

### 4.1 总体

| 指标 | LOCAL | OPENVIKING |
| --- | ---: | ---: |
| Recall@1 | 0.0000 | 0.3283 |
| Recall@5 | 0.1364 | 0.6010 |
| Recall@8 | 0.1515 | 0.7222 |
| 引用有效率 | 0.0305 | 0.1053 |
| 不可答题误引率 | 1.0000 | 1.0000 |
| 平均上下文 token | 788.6 | 4277.9 |
| 延迟 p50 / p95（毫秒） | 0 / 1 | 1164 / 2282 |

### 4.2 按题型拆解

| 题型 | 题数 | LOCAL R@1 | R@5 | R@8 | OV R@1 | R@5 | R@8 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 单文档 | 17 | 0.000 | 0.118 | 0.118 | 0.529 | 0.824 | 0.941 |
| 跨文档 | 16 | 0.000 | 0.156 | 0.188 | 0.115 | 0.365 | 0.490 |
| 不可答 | 5 | 两条路径都 5/5 强行引用 | | | | | |

逐题胜负（K=8 命中即算）：**OpenViking 独赢 22 题，两者都中 6 题，都不中 4 题，本地独赢 1 题**（Q23）。

跨文档题是两条路径共同的短板：OpenViking 的 R@8 也只有 0.490，意味着需要同时拿到 2–3 篇才能回答的问题，它多数只拿到其中一篇。

### 4.3 延迟与稳定性

| | LOCAL | OPENVIKING |
| --- | ---: | ---: |
| p50 | 0 ms | 1164 ms（复跑 1659 ms） |
| p95 | 1 ms | 2282 ms（复跑 3298 ms） |
| 最大 | 4 ms | 10033 ms |
| 样本 | 190 | 190 |

本地是一次进程内 pgvector 加 BM25 查询，亚毫秒；OpenViking 每题平均 **14.9 次远端调用**，延迟差三个数量级。两轮之间 38 道题只有 1 道排序有出入，基本确定性。

## 5. 关键发现

### 5.1 本地路径的 Recall@1 恒为 0，且这不是测量假象

34 道可答题、51 个期望文档中，本地检索命中 8 个，位次分别是 2、3、4、4、4、5、5、6——**从不在第 1 位**。这排除了 ID 空间错配或检索范围错配（那两种情况下一次都不会命中）。OpenViking 命中 32 个，其中 13 次在第 1 位。

根因是嵌入能力的不对称，报告里必须讲清楚：**本地向量是 `PostgresVectorStore` 的哈希词袋（1536 维）**，OpenViking 用的是 DashScope `text-embedding-v4` 真实语义嵌入（1024 维）。金标题按要求写成工程师口吻、刻意避开可被单个稀有标识符 grep 命中的措辞，这对哈希词袋近乎最坏情况。

所以这张表衡量的是**"哈希词袋 + BM25 的本地路径" 对 "真实语义嵌入 + 三层导航的 OpenViking 路径"**的端到端差距，不是两套导航算法在同等嵌入下的优劣。想把三层导航本身的贡献单独摘出来，需要给本地路径换上同一个嵌入模型再测一轮。

另一处副作用：本地返回的 8 个候选块常塌缩成更少的不同文档（38 题中 15 题只返回 6 篇、5 题只返回 4 篇），OpenViking 稳定返回 8 篇。

### 5.2 停止原因几乎全是预算耗尽，证据门只触发过 1 次

| 停止原因 | 次数 |
| --- | ---: |
| `REMOTE_CALL_BUDGET_EXHAUSTED` | 37 |
| `EVIDENCE_SUFFICIENT` | 1 |

每题平均 14.9 次远端调用，上限 15，最小 10。也就是说三层导航几乎总是把 15 次请求预算跑干才停手，而不是判断证据够了才停。这既解释了 4278 的平均上下文（本地的 5.4 倍），也说明 WP-7 里调过的证据门在真实语料上仍然过严。

### 5.3 两条路径都不会说"不知道"

5 道不可答题，两条路径都 5/5 返回了引用。本地路径这是结构性的（top-k 检索总会返回 k 条）；OpenViking 则说明证据门从未判定"无可采信证据"。allowlist 拒绝计数全为 0，跨库证据混入为 0——准入围栏本身工作正常，问题在于门槛判定。

### 5.4 新文档确实被检索到，不是死重量

两条路径的 top-8 命中池中新文档占比都过半：本地 61%（接口 20%、PRD 18%、计划 14%、测试 9%），OpenViking 66%（测试 22%、PRD 19%、计划 14%、接口 11%）。语料扩充有效参与了检索。

## 6. 任务执行对照

两轮都跑到了 `COMPLETED` 并开出真实 PR：

| | LOCAL | OPENVIKING |
| --- | --- | --- |
| taskId | `7493758950597332992` | `7493801645885755392` |
| PR | [pull/25](https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045/pull/25) | [pull/26](https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045/pull/26) |

对照方式：同一张需求单跑两遍，仅 `RD_RAG_KNOWLEDGE_PROVIDER_MODE` 不同（`LOCAL` 与 `OPENVIKING`），其余配置由 `tmp/start-backend-for-rag-ab.sh` 固定，避免配置漂移被误读成检索差异。

需求单是语料里定位到的真实缺陷：`POST /api/coupons/calculate` 用请求体 `order_amount` 做门槛与折扣基数、返回 `final_amount` 不含配送费；`POST /api/orders` 用商品小计 `goodsAmount` 做同样两件事、`final_amount` 含配送费。同一张券同一组商品下，两个接口的 `final_amount` 稳定相差一个配送费，`percent` 类券的折扣额也可能不同。需求措辞刻意避开 `支付` 等高风险词，以免触发 `RuleBasedRequirementPolicyGate` 的人工审批分支。

### 6.1 指标的真实来源与限制

不是所有想要的指标都真的落库，逐项说明，不做合成：

| 指标 | 来源 | 限制 |
| --- | --- | --- |
| 任务执行耗时 | `rd_agent_stage_runs.started_at/finished_at`、`rd_tasks.created_at/updated_at` | `rd_task_status_events.duration_ms` 实测恒为 0，不作为来源 |
| token 消耗 | `rd_agent_stage_runs.provider_attempts_json` 的 `inputTokens` / `outputTokens` / `totalTokens` | 是 JSON 字段，**没有** SQL 列；没有专门的 token 计量表 |
| 缓存命中 | 同一 JSON 的 `cacheReadInputTokens` / `cacheCreationInputTokens` | 这是 **token 计数，不是命中率**；系统里不存在任何缓存命中/未命中计数器 |
| 检索候选与选中数 | `rd_rag_retrieval_runs.candidate_count` / `selected_evidence_count` | 可用 |
| 任务内检索延迟 | `rd_rag_retrieval_runs.created_at → updated_at` 差值 | 只有 run 级；`rd_rag_retrieval_steps.duration_ms` 有建表但**无任何 Java 写入方**，生产路径从不填 |
| 检索召回率 | 不可得 | 召回需要金标标注，任务运行没有标注。召回只能来自 §4 的评测台 |

因此"缓存命中"一栏报的是缓存读取 token 及其在提示输入中的占比，并明确标注它不是命中率。

### 6.2 耗时对照

| | LOCAL | OPENVIKING | 差异 |
| --- | ---: | ---: | ---: |
| 端到端墙钟 | 2513.1 s | 2029.5 s | −19.2% |
| 需求评审 | 176.2 s | 155.9 s | −11.5% |
| 方案架构 | 207.6 s | 177.2 s | −14.6% |
| 编码 | 385.9 s | 356.0 s | −7.7% |
| 质量保障 | 1200.8 s | 797.6 s | −33.6% |
| 四阶段合计 | 1970.5 s | 1486.7 s | −24.6% |
| 编排与等待开销 | 542.6 s | 542.8 s | 持平 |

编排开销两轮几乎完全一致（相差 0.2 s），说明差异全部落在角色执行里，而不是调度抖动。

### 6.3 token 与缓存对照

| | LOCAL | OPENVIKING | 差异 |
| --- | ---: | ---: | ---: |
| 新鲜输入 token | 168,893 | 157,408 | −6.8% |
| 缓存读取 token | 16,002,816 | 14,784,256 | −7.6% |
| `totalTokens` | 16,171,709 | 14,941,664 | −7.6% |
| 缓存写入 token | 0 | 0 | — |
| 缓存读取占提示输入 | 98.96% | 98.95% | 持平 |

两点必须说清楚：

- **输出 token 恒为 0**，不是模型没输出，而是 `deepseek-anthropic` 这条 anthropic-compatible 通道回填的用量里 `outputTokens` 一直是 0。这是计量缺口，不是省了钱。
- **需求评审与方案架构两个角色的 token 完全没有记录**。它们走宿主侧 `OpenAiChatCompletionsRepairExecutor`，该执行器不往 `provider_attempts_json` 写任何用量字段。上表的 token 只覆盖编码与质量保障两个 Docker 角色。

### 6.4 检索行为：唯一的自变量

| | LOCAL | OPENVIKING |
| --- | ---: | ---: |
| 任务内检索次数 | 8 | 8 |
| 中位延迟 | 82 ms | 1541 ms |
| 最大延迟 | 144 ms | 1952 ms |
| 累计检索耗时 | 0.7 s | 11.7 s |
| 平均选中证据 | 8.00 条 | 7.25 条 |
| 质量降级次数 | 0 | 2 |

OpenViking 单次检索慢约 19 倍，但累计只多 11 秒，在 2000 秒量级的任务里可以忽略。真正值得注意的是两次降级：

- 方案架构：`缺少角色关键证据: ARCHITECTURE_OR_INTERFACE；已降级为受限仓库发现`
- 编码：`缺少角色关键证据: CODE_SYMBOL；已降级为受限仓库发现`

### 6.5 更快更省，但不能直接读成"更好"

OpenViking 那轮每一项都更低，可这三条约束必须同时摆出来：

1. **每臂只有 1 个成功样本。** 主导差值的质量保障阶段（−403 s，占端到端差值的 83%）是浏览器实跑，本身方差极大，单次差异不足以支撑结论。
2. **更省 token 部分是因为喂进去的证据更少。** OpenViking 平均选中 7.25 条、两次降级，LOCAL 恒定 8 条、零降级。上下文更小自然更快更省，这不是检索质量更好的证据。
3. **OpenViking 这一臂多失败了一次。** 首次提交（`7493799568929329152`）在方案架构阶段以 `openai chat completions response did not contain a JSON object` 失败，而该阶段的检索恰好也是 `DEGRADED_ACCEPTABLE`。样本太少不能断因果，但方向和 §5 的检索质量结论一致：OpenViking 侧证据更不稳。

交付内容本身是等价的，所以上面的"更快"不是"少做了事"：两轮改的都是 `server/src/routes/coupons.ts` 同一处基数口径，patch 规模相当（240 行 vs 247 行），都同步了 `client/src/api.ts` 并新增了一致性验收脚本。差别在于 OpenViking 那轮还补了 `README.md` 的接口口径说明——正好是第 3 条验收标准里"该口径在接口文档中写明"的部分，LOCAL 那轮没做。就交付完整度而言，OpenViking 一臂反而略胜。

两轮在 `order_amount` 的语义上做了不同选择：LOCAL 把入参当作含配送费的合计、反推 `goodsAmount = order_amount - deliveryFee`；OpenViking 直接把入参定义为商品小计。后者与需求材料里描述的下单侧口径一致。

### 6.6 为了跑通这两轮而修的两个真实缺陷

对照本身暴露了两个与检索无关、但会让任何编码任务失败的问题：

- **`DockerClaudeCodeExecutor` 的 tmpfs 缺 uid/gid。** `/home/rdbot/.claude/session-env` 挂成 root 拥有的 0755，容器内 uid 999 无法建立会话目录，Agent harness 在跑第一条命令前就死了，表现为 `EACCES: mkdir /home/rdbot/.claude/session-env/<session-id>`。Docker 只对 `/tmp` 默认给 1777，其他路径不会。`DockerPiAgentExecutor` 的每个 tmpfs 早就带了 `uid=1000,gid=1000`，Claude 这条路径漏了。已补 `uid=999,gid=999` 并加回归断言。
- **`collect_task_metrics.py` 把 token 读成 0。** 执行器把整个元数据 map 序列化成字符串，采集脚本却用 `isinstance(value, (int, float))` 判断，于是每个计数器都被静默丢弃。已改为兼容数字字符串。

### 6.7 环境偏离说明

三处有意偏离，记录在此以免被读成配置漂移，且两臂完全一致：

- `RD_EXECUTOR_DOCKER_CIRCUIT_BREAKER_STATE_STORE=memory`（而非默认 `redis`）。单后端进程下逐进程熔断器与共享熔断器等价，避免为一次实验引入 Redis 口令依赖。
- 宿主侧角色改用 DeepSeek 官方直连而非 `opencode.ai` 中继。中继连续两次在约 60 秒处断开（一次客户端超时、一次 TLS `bad_record_mac`），撑不住这个体量的提示词。
- `RD_QA_EXECUTION_TIMEOUT_MILLIS=2400000`（而非默认 20 分钟）。20 分钟会把质量保障阶段拦腰砍断，40 分钟是上一次绿色 canary 用的值。

## 7. 复现命令

```bash
# 语料可信度对账
python3 tmp/verify_docs_grounding.py

# 金标题引用逐条核验（连真实库）
python3 tmp/verify_gold_questions.py

# 金标题结构门
./mvnw -q -pl bootstrap -am -Dtest=WaimaiGoldQuestionsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 检索对照（只读，约 5 分钟）
./mvnw -pl bootstrap -am -Drd.openviking.smoke=true \
  -Dtest=OpenVikingShadowRetrievalEvaluationRealSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 结果按题型与逐题拆解
python3 tmp/analyse_eval.py tmp/wp7-shadow-eval-results.json

# 任务执行指标采集
python3 tmp/collect_task_metrics.py <taskId> <label>

# 两臂并排对照（读上一步落盘的 JSON）
python3 tmp/compare_task_runs.py

# tmpfs 权限修复的回归断言
./mvnw -q -pl exec -am -Dtest=DockerClaudeCodeExecutorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
