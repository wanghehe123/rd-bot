# Evaluation V2：多 Agent 编码能力消融评测设计

日期：2026-07-30

状态：社区方案交叉审查后修订，等待实施计划

范围：20 道真实编码题、LongCat 2.0、多 Agent 编排消融、RAG 文档、依赖离线 Docker 环境、隔离 Oracle 判分与管理端评测重构。

本修订参考 Multi-SWE-bench 的三状态验证、SWE-bench 官方 Docker 分层、Terminal-Bench 2.0 的多次运行实践、Claw-SWE-Bench 的统一 harness/预算/patch 提取和未来提交清理、Harbor 的 Agent/Verifier 隔离，以及随机区组实验设计。社区方案只能提供约束，最终实现仍以本仓库真实执行链、LongCat 2.0 的速度和一天内完成为边界。

## 1. 背景与问题

RD-Bot 已有 Web 评测链路，但当前能力更适合 RAG 指标和已完成任务快照审计，不适合回答“多 Agent 编排是否真正提升编码问题解决率”。已验证的当前链路是：

```text
EvaluationController
  -> EvaluationRunEngine
  -> LocalEvaluationTaskScheduler
  -> LocalPythonEvaluationExecutor
  -> rd_eval_run.py / rd_eval_score.py / rd_eval_report.py / rd_eval_diff.py
  -> rd_evaluation_runs / rd_evaluation_events / rd_evaluation_artifacts
```

当前 `EvaluationSource` 只有 `FIXTURE / RAG_HTTP / TASK_RUN`；`TASK_RUN` 收集已存在任务的快照，再交给 Python 评分，不负责创建同题多组 Agent 工作流、隔离代码工作区或运行期扣留测试。现有 `rd_eval_quality_v1.jsonl` 共 48 条，主要是 fixture、角色合同和任务审计样本，不能作为真实编码修复能力的主测试集。

真实需求交付链路目前在 `RequirementDeliveryEngine#executeAgentStages(...)` 中按固定顺序执行：

```text
REQUIREMENT_REVIEWER
  -> SOLUTION_ARCHITECT
  -> CODING_AGENT
  -> QA_AGENT
  -> 最多一次 QA -> CODING_AGENT -> QA_AGENT 修复回路
```

角色最终通过 `RequirementExecutionProfileResolverPort -> RequirementExecutorPort -> EngineRequirementExecutorAdapter -> DockerPiAgentExecutor -> rd-pi-bridge.mjs` 进入 Pi 容器。Evaluation V2 必须复用这条生产执行链，不能另写一套“看起来像多 Agent”的评测专用模拟器。

## 2. 目标与非目标

### 2.1 目标

1. 使用约 20 道难度偏高的真实仓库修复题，在一天内完成一轮正式评测。
2. 通过 A/B/C/D 四组配对消融，分别测量任务分解、角色级 RAG 和 QA 修复回路的增益与代价。
3. 把现有 EvaluationRun 重构为可承载 Campaign + Trial 的评测控制面，同时保持旧评测可用。
4. 在正式运行前完成镜像、依赖、裁剪代码仓库、RAG 文档和运行期扣留测试准备；Trial 运行期间只允许访问固定 LongCat 模型中继，禁止联网下载依赖。
5. 以可复现 Oracle 测试作为主判分依据，模型 Judge 不能覆盖确定性结果。
6. 输出 20×4 结果矩阵、配对统计、失败归因、Token/时间成本和完整审计证据。

### 2.2 非目标

- 首轮不混入 Terminal-Bench 2.0、SWE-Lancer 或通用命令行任务；这些任务形态留到后续独立 Campaign。
- 不把 SWE-bench Verified 作为首轮主测试集，只作为历史结果对照。
- 不做多次采样取最好结果；20×4 首次尝试均按 `@1` 计分，另对预注册的 4 个哨兵 case 复跑 A/D 一次，只报告翻转率和方向，不取两次最好值。
- 不在管理页面开放任意 Docker 镜像、Shell、宿主路径、测试命令或密钥输入。
- 20 题属于工程决策型小样本，不用于宣称具有论文级统计普适性。

## 3. 题集设计

### 3.1 双切片主测试集

公开 SWE 题及 Gold Patch 可能进入模型训练语料，因此首轮 20 题拆为两个独立切片。只有 `FRESH_PRIMARY` 用于污染风险较低的主结论；`PUBLIC_ANCHOR` 用于和公开生态对齐，不能声称模型未见过：

| 切片 | 数量 | 来源 | 结论用途 |
| --- | ---: | --- | --- |
| `FRESH_PRIMARY` | 10 | issue/test/Gold 在 Campaign 冻结前始终为私有且模型不可访问，或在可证明的模型训练截止/不可变 revision 发布后才公开的真实任务 | D-A 主结论 |
| `PUBLIC_ANCHOR` | 10 | Multi-SWE-bench Java 与 TypeScript/JavaScript | harness 对照与可复现锚点 |

若 LongCat 2.0 的不可变模型 revision、训练截止日期或 fresh 题历史无法证明，`FRESH_PRIMARY` 只能标记为“较低暴露风险”，不能标记为“确认未污染”。若准备阶段无法得到 10 道合格 fresh 题，可运行 `PUBLIC_ANCHOR_ONLY` 诊断 Campaign，但不得输出“多 Agent 提升了未见编码题能力”的正式结论。

20 题总体配额为：

| 维度 | 配额 |
| --- | ---: |
| Java | 12 |
| TypeScript/JavaScript | 8 |
| Easy | 2 |
| Medium | 10 |
| Hard | 8 |
| 正式计分题 | 20 |
| 环境探针题 | 2，不计分 |

不自创 `hard+` 标签。公开锚点沿用上游难度；fresh 题按同一冻结 rubric 双人复核映射到 Easy/Medium/Hard。每个切片都必须同时包含 Java 和 TypeScript/JavaScript；总体至少覆盖 10 个仓库，每仓库最多 2 题，避免结果由单一仓库聚类主导。

#### 3.1.1 首轮 fresh 切片的单仓库例外【已批准，必须报告】

首轮唯一可证明的私有来源是 `wanghehe123/rd-bot` 本身（2026-07-22 创建、始终私有、Java 主语言且含 TypeScript 前端），因此
**首轮 10 道 `FRESH_PRIMARY` 全部来自这一个仓库**，明确豁免上面的"每仓库最多 2 题"。这是经批准的取舍，不是疏漏，代价必须如实承担：

- fresh 切片的 case 之间**不是相互独立的**。同一仓库共享架构、命名习惯、测试框架和领域词汇，因此
  case-level bootstrap 会低估真实方差，fresh paired CI 必须按单仓库聚类解读，不得当作跨仓库置信区间。
- fresh D-A 结论的适用范围只能表述为"在一个 Java/TypeScript 单体仓库内的离线修复题上，编排带来的净增益"，
  **不得**外推为"提升了模型的通用编码能力"或"在任意仓库上成立"。
- `PUBLIC_ANCHOR` 切片仍须覆盖多个上游仓库，作为跨仓库对照；两个切片方向不一致时，
  报告必须同时给出，并且不得只引用对结论有利的那一侧。
- 报告的限制章节必须显式写明本例外、其对独立性假设的影响，以及后续获得更多私有仓库后应重跑的计划。

单仓库带来一个公开题不存在的新泄漏面：同一仓库的已提交文档可能已经描述了尚未实现的修复。
`rd_eval_audit_case_leakage.py` 对每道 fresh 题强制审计，判据是"Gold patch 声明的符号已被 base 文档提及、
却未被任何 base 生产文件定义"。审计为 `LEAK` 的候选必须换题或把该文档移出 case 仓库；
审计为 `UNAUDITABLE`（Gold patch 没有可识别声明）不得当作通过。实测该审计在 40 个真实候选中判出 10 个泄漏，
其中 5 个的新增类名被逐字写在已提交的实现计划文档里。

题目选择必须同时满足：

- 基础提交、问题描述、Gold Patch 和 fail-to-pass/pass-to-pass 测试均可固定。
- 干净基础提交能够稳定复现失败，Gold Patch 能够稳定通过 Oracle。
- 单次 Oracle 测试目标不超过 8 分钟。
- 不依赖外部服务、真实密钥或运行期互联网。
- 生产代码修改通常不少于 2 个文件。
- 至少 12 题的问题描述不直接给出目标文件名。
- 至少 8 题涉及跨模块、状态传播、协议或回归约束。
- 具有运行期扣留的官方测试；fresh 题尽可能再增加私有补充回归。公开 benchmark 的 `test.patch` 只能称为“运行期扣留测试”，不能称为模型未见的隐藏测试。

排除以下题型：

- 纯文档、格式化、拼写修复。
- 明显的一行修改或机械重命名。
- Gold Patch 新增第三方依赖、修改锁文件或要求联网安装依赖。
- 依赖不稳定外部服务、专用硬件、交互式 GUI 或超过 Oracle 时间预算。
- 基础提交在离线环境中不能稳定构建，或 Gold Patch 不能重复通过。

精确 case ID 不写死在本设计中。实施准备阶段先导出完整候选池、排除理由和固定随机种子，再按切片、语言、仓库、issue 类型、官方难度和 Gold 修改文件数分层抽样，生成 `dataset-manifest.json`。在任何正式 Trial 启动前冻结 case ID、仓库、base commit、测试命令、Gold Patch 哈希和数据集总哈希。冻结后不得依据某一消融组的表现替换题目；探针只能用于发现明显地板/天花板或环境故障，不能查看正式 arm 结果后换题。

### 3.2 环境探针

两个探针分别覆盖 Java 和 TypeScript/JavaScript。探针走与正式题完全相同的工作区、模型、RAG、Oracle 和报告链路，但不进入最终 20 题分母。它们是唯一允许调试统一 Prompt、文档生成器、chunker 和检索参数的开发集；一旦探针通过，必须冻结这些配置。正式 20 题上的 GoldFileHit、失败轨迹或 arm 结果只能做事后分析，不能触发调参、补文档或换题。

## 4. 消融实验

### 4.1 四个实验组

| Arm | 工作流 | RAG | QA | 目的 |
| --- | --- | --- | --- | --- |
| A | `CODING_AGENT` | 关闭 | 关闭 | LongCat 2.0 单编码 Agent 基线 |
| B | `REQUIREMENT_REVIEWER -> SOLUTION_ARCHITECT -> CODING_AGENT` | 关闭角色检索 | 关闭 | 测量任务评审与方案分解增益 |
| C | 与 B 相同 | 开启冻结的角色级 RAG | 关闭 | 测量 RAG 的边际增益 |
| D | `REQUIREMENT_REVIEWER -> SOLUTION_ARCHITECT -> CODING_AGENT -> QA_AGENT` | 开启同一冻结 RAG | 开启，最多一次修复回路 | 完整生产编排 |

生产需求交付始终使用 D；A/B/C 只能由 `CODING_BENCHMARK` 评测模式创建，不能成为普通 RD 任务的可选生产配置。

### 4.2 公平性约束

四组必须共享：

- 同一个 LongCat 2.0 模型标识、provider、推理参数和工具策略。
- 同一 case 的 base commit、只读代码镜像、依赖包和 Oracle 测试包。
- 相同 Trial 时限、总 Token 上限、单次输出上限和工具轮次上限。
- 相同网络策略和宿主资源等级。
- 相同问题描述与公开仓库内容。
- Reviewer、Architect、Coding 等同名角色相同的 Token 配额；只有该 arm 不存在的角色额度保持不可用，不能转给其他角色。

A/B 不进行任何角色级知识库检索，也不注入为评测生成的四份 RAG 文档；这些文档不写进被测代码仓库。四组的初始需求上下文都只包含 issue、冻结仓库元数据和相同的非 RAG 系统合同，`REQUIREMENT_BASE` 不读取该 case 的评测知识库。只有 C/D 的 `AGENT_ROLE` consumer 能访问同一个冻结 `knowledgeSnapshotId`。不同 arm 不共享可写工作区、对话、检索结果或依赖缓存副本。

每个 case 预注册到四个平衡序列之一：`ABCD / BCDA / CDAB / DABC`，每个序列 5 个 case。分配时按 fresh/public、语言、难度和仓库分层；调度器按波次滚动执行，并使每个波次的 arm 尽量平衡。禁止先跑完全部 A 再运行 B/C/D。每个 Trial 记录 `sequencePosition`、wave、开始时间、不可变模型 revision、provider 指纹和宿主资源快照。

### 4.3 Trial 预算

每个 Trial 的硬限制为：

- Agent 阶段墙钟时间：45 分钟；环境准备不占用该额度，Oracle 另有 8 分钟硬上限并与后续 Trial 流水执行。
- 输入加输出总 Token：10,000,000。
- 单次模型调用最大输出：65,536 Token。
- Agent 与工具交互合计：最多 60 次。

10M 是防止异常循环的安全上限，不是要求模型主动消耗的目标。报告必须记录实际输入、输出、缓存命中、模型调用数、工具调用数和耗时。预算以 provider 返回的 usage 为优先真值；provider 缺失 usage 时使用版本冻结的保守估算器，并写入 `usageEstimated=true`，预算执行取报告值与估算值的较大者。预算按整个 Trial 统一记账。为避免把角色预算差异误当成编排增益，所有 arm 的同名角色固定为同一份配额：

| Arm | 角色预算 |
| --- | --- |
| A | Coding 56%；其余 44% 不可用 |
| B/C | Reviewer 8%，Architect 16%，Coding 56%；其余 20% 不可用 |
| D | Reviewer 8%，Architect 16%，首次 Coding 56%，QA 8%，修复预留 12% |

D 的修复预留仅在 QA 明确请求编码修复时可用；任何 arm 的未使用额度均不回流、不跨角色重分配。达到时间、Token、输出或轮次上限都立即停止，并按真实阶段归类为 `TIMEOUT` 或 `PROTOCOL_ERROR`，不得自动增加预算重跑。10M 与 64K 是统一安全上限，不代表 A 可以把缺失角色的额度全部用于 Coding。

因此本实验估计的是“增加受限角色后，完整系统在固定同名角色上限下的增益”，而不是“所有 arm 拥有相同可消费 Token 时的纯 Prompt 效应”。新增角色的实际成本通过 Token/时间倍率显式计入决策门槛。

## 5. Evaluation V2 架构

### 5.1 总体结构

```text
管理端 / Admin API
  -> EvaluationCampaignEngine
  -> EvaluationTrialScheduler
  -> RequirementAgentStageOrchestrator(AgentWorkflowPlan)
       -> RequirementExecutorPort
       -> execution-profile resolver
       -> DockerPiAgentExecutor
       -> rd-pi-bridge.mjs
       -> patch + stage evidence
  -> OfflineOracleRunner
       -> clean verifier workspace + runtime-withheld tests
       -> deterministic verdict
  -> immutable trials.jsonl
  -> Python scorer/report generator
  -> matrix + ablation report + failure analysis
```

现有 `EvaluationRun` 和 `rd_evaluation_runs` 升级为 Campaign 主记录，不平行创建第二套 Run 聚合。新增 `EvaluationMode`：

- `LEGACY_QUALITY`：保留当前 fixture/rag-http 录制、评分和 diff。
- `TASK_AUDIT`：保留当前已完成 RD 任务快照审计。
- `CODING_BENCHMARK`：创建 20×4 首次 Trial，并为 4 个预注册哨兵 case 创建 A/D 各 1 个稳定性复跑，共 88 个 Trial。

历史记录没有 mode 时，由持久化配置中的 source 兼容推断；旧 API 和历史报告继续可读。

### 5.2 生产编排复用

从 `RequirementDeliveryEngine` 抽取内部 `RequirementAgentStageOrchestrator`，输入不可变 `AgentWorkflowPlan`。Plan 至少包含：

- 允许的角色和固定顺序。
- 每个角色是否允许检索。
- 是否启用 QA 与一次性修复回路。
- Trial 预算账本和超时。
- 评测专用 patch-only 交付策略。

`RequirementDeliveryEngine` 仍负责需求任务主状态、策略、交付和生产默认值，并以 D 计划调用 orchestrator。Evaluation V2 只负责创建评测任务和传入 A/B/C/D 的受限计划。角色执行仍经过现有执行端口和 Pi 容器协议，阶段结果继续落 `AgentStageRun`，不能绕过现有审计、CAS 和结果校验。

### 5.3 Campaign 状态

`CODING_BENCHMARK` 使用以下主流程：

```text
CREATED -> QUEUED -> PREPARING -> RUNNING_TRIALS -> SCORING -> REPORTING -> SUCCEEDED
                |          |             |             |           |
                +----------+-------------+-------------+-----------+-> FAILED

QUEUED/PREPARING/RUNNING_TRIALS/SCORING/REPORTING
  -> CANCEL_REQUESTED -> CANCELLED
```

旧模式继续使用既有 `RECORDING -> SCORING -> REPORTING -> DIFFING` 合法边。`EvaluationTransitionPolicy` 必须按 mode 分图校验，不能把两种状态机拼成任意可跳转的统一图。

暂停不创建新的 Campaign 状态：在 `rd_evaluation_runs` 持久化 `dispatch_paused`。暂停只停止领取新 Trial，已经运行的 Trial 继续到终态；恢复清除标记并继续派发。取消则停止派发、终止活跃容器，并保留已完成 Trial 和证据。

单个 Trial 的模型失败不会使 Campaign 进入 `FAILED`。Campaign 只有在数据集准备失败、调度器无法继续、数据库/产物系统性损坏或评分报告无法生成时才失败。

### 5.4 Trial 状态与持久化

新增：

- `rd_evaluation_trials`：一个 `(campaign, case, arm, replicate_no)` 的逻辑 Trial；首次尝试 `replicate_no=0`，哨兵稳定性复跑为 `1`。
- `rd_evaluation_trial_events`：append-only Trial 状态、租约和重试时间线。

Trial 生命周期为：

```text
QUEUED -> PREPARING -> RUNNING_AGENTS -> RUNNING_ORACLE -> SUCCEEDED
   |          |              |                |
   +----------+--------------+----------------+-> FAILED

PREPARING/RUNNING_AGENTS/RUNNING_ORACLE
  -> RETRY_PENDING -> QUEUED    （仅首次 INFRA_ERROR）

非终态 -> CANCELLED
```

`SUCCEEDED` 表示 Trial 执行链完整结束，不代表代码修复通过；能力结论存放在独立 `verdict` 字段。Trial 至少持久化：

- `campaign_id / case_id / arm / replicate_no / status / verdict / attempt_no / version`。
- `rd_task_id / stage_run_ids_json`。
- 冻结的 Trial、模型、工作流、知识库和环境配置快照。
- 输入/输出 Token、模型调用、工具调用和墙钟时间。
- 初始 patch、pre-QA patch、最终 patch 及其哈希。
- 公开测试、运行期扣留测试、构建和 Oracle 证据引用。
- 错误分类、错误摘要、租约 owner、租约过期时间、心跳与时间戳。

数据库必须有唯一约束 `(campaign_id, case_id, arm, replicate_no)`。88 个逻辑 Trial 在 Campaign 开始运行前一次性创建，调度器通过数据库条件更新领取租约。服务重启后恢复 `QUEUED` 和租约过期的非终态 Trial，不重复创建任务或覆盖已完成结果。

只有纯基础设施错误允许进入一次 `RETRY_PENDING`；调度器在 `RETRY_PENDING -> QUEUED` 时把同一逻辑 Trial 的 `attempt_no` 增加 1，事件和第一次错误证据必须保留。第二次基础设施错误进入终态 `FAILED/INFRA_ERROR`。模型结果错误、无 patch、测试失败、超时、评审阻断或协议失败不得重跑。

## 6. Trial 数据流

1. Campaign `PREPARING` 校验冻结数据集、知识库、环境包、镜像摘要、LongCat 不可变 revision、分析计划和可用磁盘。
2. 一次性创建 80 个首次 Trial 和 8 个哨兵稳定性 Trial，按预注册平衡序列排队。
3. Worker 领取 Trial。宿主 mirror 只作为可信构建输入；prepare 阶段为 Trial 生成不含 base commit 之后 ref/object 的私有、裁剪 Git 仓库，禁止使用会写共享 `$GIT_DIR/worktrees` 的 linked worktree。
4. 将内容寻址依赖包以只读 lower layer + Trial 私有 writable upper layer 挂载到 `/work/cache`，挂载 Agent 可见的裁剪仓库和工具。
5. 创建标记为评测用途的 patch-only RD 任务，按固定 `AgentWorkflowPlan` 运行角色链。
6. 同一个 Trial 内 Reviewer、Architect、Coding、QA 和一次修复共用 repo 与 cache，以便传递真实代码状态；不同 Trial 完全隔离。
7. 保存 Agent 最终 patch。D 组在首次 Coding 完成、QA 开始前额外保存 pre-QA patch。
8. Agent 容器退出后，只把标准化 patch、patch 哈希和最小元数据传给独立 Verifier。Verifier 在全新仓库与独立 cache 中应用 patch，再注入只读的运行期扣留测试并执行固定命令；Agent 的 repo、cache、`node_modules`、构建输出和测试配置均不得进入 Verifier。
9. D 发生修复时，Oracle 分别测试 pre-QA patch 和最终 patch，用于判断 QA 真正修复、误拒绝或未改善。
10. Trial 只写不可变证据引用；Campaign 结束后导出冻结 `trials.jsonl`。
11. Python scorer只读取 `trials.jsonl`、冻结 manifest 和 `analysis-plan.json`，不再次调用模型或修改 Trial 事实，生成统计和报告。

## 7. Docker 与离线依赖设计

### 7.1 存储优化结构

不构建 20 个 Agent 完整镜像和 20 个 Oracle 完整镜像，也不能假设 2–3 个通用镜像足以覆盖所有真实仓库。采用“共享厚层 + case 薄层”的 OCI 分层结构：

1. **共享基础镜像**：按工具链准备 2–3 个镜像，至少覆盖 Java 21 + Maven/Gradle、Node.js + npm/pnpm/Yarn；确有浏览器题时增加浏览器 QA 镜像。
2. **内容寻址 case/env 薄层**：每个仓库或 case 只增加系统包、原生库、工具链差异和锁定依赖；由 Docker layer 去重复用，不复制完整基础 rootfs。
3. **宿主可信仓库源 + Trial 私有裁剪仓库**：bare mirror 仅在准备阶段由宿主读取；Agent 得到只包含 base commit 及其祖先、无未来 refs/objects 的私有仓库。
4. **内容寻址依赖 lower layer**：摘要绑定 wrapper、构建文件、锁文件、settings、插件和工具链；只读 lower layer 叠加 Trial 私有 writable upper layer。
5. **20 个轻量 Case Manifest**：记录提交、平台、摘要、挂载、资源和测试合同，不包含完整 rootfs。

Agent 与 Verifier 可以复用相同的只读镜像层，但必须使用不同容器、不同私有仓库、不同 writable cache 和不同网络；运行期扣留测试由受信宿主只读注入 Verifier，不创建 20 个完整 Oracle 镜像。

不再写死过于乐观的 4–25 GB 容量估算。SWE-bench 官方把 120 GB 空闲磁盘、16 GB RAM 作为 Docker 评测的一般规划起点；本项目以 20 个实际 case 的构建结果为准。`readiness-report.json` 必须测量 2–3 个基础层、全部 case/env 薄层、依赖 lower layer、宿主 mirrors、6 个 Agent writable upper、3 个 Verifier writable upper、Docker build cache、日志和隔离区的实际 allocated bytes，并预留至少 20% 余量。磁盘不足时 fail-fast，不在运行中临时删共享资产。

### 7.2 Case Manifest

每个 case 的 manifest 至少包含：

```text
caseId
repositoryMirrorId
baseCommit
platform=linux/amd64
agentImageDigest
verifierImageDigest
dependencyBundleSha256
oracleTestBundleSha256
toolchainSha256
privateRepoTreeSha256
agentTestCommands
oracleTestCommands
agentNetworkPolicy=MODEL_RELAY_ONLY
oracleNetworkMode=none
browserQaRequired
cpuLimit
memoryLimit
pidsLimit
storageLimit
shmSize
```

所有摘要进入 `environment-manifest.json`。正式 Campaign 引用平台相关 image digest/ID 而不是浮动 tag，并用 `--platform` 强制运行；镜像、依赖包、测试包、Docker/runtime 版本或资源合同变化都必须产生新的 environment snapshot。若实际机器不是 `linux/amd64`，必须在探针中确认模拟性能仍满足一天时限，或把整轮统一切换到另一个冻结平台，不能不同 Trial 混用架构。

### 7.3 构建责任与网络边界

环境准备由本项目实施方负责，包括：选择 case、编写基础镜像、构建 Git mirror、预取依赖、制作 Oracle 测试包、生成 manifest、运行离线验证和记录 digest。不能把依赖准备转交给正式 Trial 中的 LongCat Agent。

可信构建阶段允许联网下载已锁定依赖，但禁止 SNAPSHOT、动态版本和浮动插件。Oracle 一律 `network=none`。`MODEL_RELAY_ONLY` 必须是可执行网络拓扑而不是日志约定：

- 每个 Trial 使用独立 internal Docker network；Agent 只有这一个网络。
- Relay 双网卡连接该 internal network 与受信上游；Agent 只持有一次性 relay token，不持有 LongCat 上游密钥。
- Relay 从冻结 profile 读取固定模型和上游，拒绝任意 URL、redirect、CONNECT、非模型 path 和非预期 method。
- readiness 必须从 Agent 内做负向探测，证明公网 IP、外部 DNS、UDP、软件仓库、其他 Trial 和 Docker host 均不可达；不能只靠“日志里没看到请求”判定隔离成功。

Maven、Gradle、npm、pnpm 和 Yarn同时使用真实 offline/frozen 参数，使误安装立即失败。依赖 lower layer 不允许 hardlink 到可写 canonical cache；使用只读 mount、reflink/COW 或 overlay，并用 canary 验证一个 arm 的写入不会改变 canonical bundle 或其他 arm。

Trial 可写缓存必须位于任务局部 `/work/cache`：

- 同一 Trial 的编码、QA 和修复尝试复用 repo/cache，避免重复下载和丢失代码状态。
- 不同 case、不同 arm 使用独立可写缓存副本，不允许并发写共享缓存。
- 基础镜像、依赖 lower layer 和 Agent 工具均只读挂载；宿主 mirror 不挂进 Agent。
- Agent 看不到 Gold Patch、运行期扣留测试目录、Oracle 启动脚本或其他 arm 的产物。
- Verifier 只接收 patch + hash + 元数据，不接收 Agent repo/cache/build output。

所有容器默认 non-root、read-only rootfs、`cap-drop=ALL`、`no-new-privileges`、固定 seccomp、无 Docker socket、无 host pid/ipc/device，并设置 CPU、memory、swap、pids、storage、shm 和 ulimit。容器带 `campaign/trial/attempt` labels 与 `--init`；停止后先给固定 grace period，再 kill，并通过 inspect 确认容器、network、volume 和子进程均已消失。清理失败归为 `INFRA_ERROR` 并把资源放入 quarantine，启动时必须做遗留资源 reconciliation。

每个 attempt 生成 `runtime-attestation.json`，记录实际 image ID/digest/platform、容器配置、只读/可写 mounts、网络、资源限制、Git tree、cache seed、Docker 版本、cgroup peak/OOM/exit 和隔离检查结果。

Trial 已终态且确认不再进行基础设施重试后，释放可写 workspace/cache；长期保留 patch、测试日志、事件、摘要和 manifest。清理必须按明确 Trial 路径执行，不能对共享根目录做模糊递归删除。

## 8. RAG 文档与知识快照

### 8.1 测试前生成文档

每个正式 case 在任何能力 Trial 启动前拥有独立知识库，至少包含：

- `repository-overview.md`：模块、入口、关键目录和边界。
- `build-and-test-guide.md`：离线构建、测试范围和常见失败定位。
- `architecture-and-conventions.md`：状态、协议、代码约定和扩展模式。
- `symbol-and-test-index.md`：重要符号、生产代码与公开测试之间的索引。

每个 case 的文档总量目标为 15K–30K Token，切块为 600–1000 Token。文档只进入该 case 的 RAG 知识库，不写进代码 worktree，从而保证 A/B 仍然是无评测文档的有效对照组。

### 8.2 来源与泄漏隔离

文档只能基于 base commit 中的代码、已有文档、构建文件、公开测试和注释生成。禁止使用：

- Gold Patch 内容、修复 PR 或未来提交。
- 运行期扣留测试、测试名或断言。
- 其他 Agent/arm 的输出。
- 根据正式评测失败追加的人工提示。

知识构建进程与 Gold/Oracle 资产分权：文档生成阶段不能读取 Gold Patch 和运行期扣留测试。统一文档生成规则只能在 2 个开发探针上调试并冻结。正式 20 题冻结语料后，可由只读评测检查器使用 Gold changed-file 路径作为事后标签计算检索覆盖，但不得把这些标签写回文档、Prompt 或索引元数据，也不得据此重建文档或索引。

### 8.3 检索合同

C/D 使用相同配置：

- 按角色限定知识库和 consumer。
- `TopK=8`。
- 单角色最多注入 12K Token。
- 同一文档最多 4 个 chunk。
- 查询、命中 chunk、得分、来源 URI 和注入 Token 全部记录。

每个 case 独立 `knowledgeBaseId`，跨知识库命中必须为 0。A/B 在代码和运行时两层关闭角色检索，不能只在 UI 隐藏开关。

### 8.4 Readiness Gate

正式运行前必须同时满足：

- 20 个知识库全部摄取成功，文档和 chunk 哈希与 manifest 一致。
- 以 issue 文本构造的非泄漏检索查询都有非空且有界结果。
- 两个开发探针的 `GoldFileHit@5 >= 75%`；该阈值只用于在正式冻结前验证通用管线。
- 正式 20 题的 GoldFileHit 只在 Trial 全部结束后计算和报告，不是 readiness gate；低于 75% 也不得调参或替换 case。
- 跨知识库命中数为 0。
- Gold Patch/运行期扣留测试内容泄漏扫描结果为 0。

冻结 `knowledgeSnapshotId`、语料 manifest、文档摘要、chunker 版本、embedding 配置、检索配置和 readiness 报告。C/D 的任意一项摘要不同都使 Campaign 无效。

## 9. Oracle 与 Trial 结论

### 9.0 Harness 与 patch 合同

`PUBLIC_ANCHOR` 使用固定 commit 的上游 Multi-SWE/SWE-bench evaluator adapter；`FRESH_PRIMARY` 使用兼容的同一三状态协议。RD-Bot wrapper 只能转换 manifest、容器和产物格式，不能重新解释 `PASSED/FAILED/NONE/SKIPPED`。

候选 patch 从最终工作区集中提取，不依赖模型最终文本。提取器必须：

- 支持二进制文件、mode、删除和未被 ignore 的新增文件。
- 排除 build output、cache、日志和 harness 产物。
- 拒绝路径逃逸、symlink 逃逸、submodule 变化以及运行期扣留测试、测试 runner/config 和 verifier 脚本的改动。
- 在干净 base 上 round-trip 应用，并证明结果与允许范围内的 Agent 最终工作区 byte-equivalent。

版本冻结的 patch extractor 和 verifier adapter 都进入 `benchmark-provenance.json`。

### 9.1 Case 预验证

正式纳入前，每题必须验证三个状态，每个状态从全新 Verifier 工作区独立运行 3 次：

1. `BASE`：base commit 上 pass-to-pass 通过，至少一个 fail-to-pass 失败。
2. `TEST`：base + test patch 必须保持相同的预期失败/通过矩阵，且所有预期 test ID 都实际执行。
3. `FIX`：base + test patch + Gold Patch 可应用，全部 fail-to-pass 与 pass-to-pass 通过。

三种状态均在 `network=none` 的 Verifier 环境中运行并冻结 test transition matrix。任一预期测试出现 `NONE`、`SKIPPED`、缺失、重复、未收集，或三次结果不一致，都在正式抽样前排除 case。

测试注入失败必须使 Trial 终止，绝不能降级为“没有测试可跑”。Verifier 在应用候选 patch 前后校验 test bundle、测试文件、runner、config 和命令哈希；拒绝候选仓库中与注入路径冲突的文件、指向受保护路径的 symlink 或会覆盖测试包的目录。测试进程必须输出精确执行过的 test ID 清单，缺少任一 manifest 期望 ID 即 FAIL。这一规则用于防止候选 patch 预创建/覆盖测试路径而制造假 PASS。

### 9.2 主指标

主指标为 `Resolved@1`。仅当以下条件全部满足时 Trial 记为 PASS：

- Agent 产生非空 patch，且能干净应用到冻结 base commit。
- 全部 fail-to-pass 测试通过。
- 全部 pass-to-pass 回归测试通过。
- 没有违反依赖、网络、路径或协议策略。
- test bundle/runner/config 哈希未变化，且 manifest 中所有预期 test ID 均且仅执行一次。

模型 Judge、角色自评和公开测试日志都不能把 Oracle FAIL 改成 PASS。

### 9.3 Verdict 分类

每个 Trial 必须落入且只落入以下分类之一：

- `PASS`
- `TEST_FAIL`
- `BUILD_FAIL`
- `NO_PATCH`
- `TIMEOUT`
- `REVIEW_BLOCKED`
- `PROTOCOL_ERROR`
- `DEPENDENCY_POLICY_VIOLATION`
- `INFRA_ERROR`

只有 `INFRA_ERROR` 不进入模型能力分母，并允许一次基础设施重试。基础镜像缺包、预构建依赖不全属于环境准备缺陷；正式运行中不能让 Agent 现场联网修复。

`INFRA_ERROR` 不能由异常字符串自由映射。分类器必须按冻结决策表读取 Docker inspect、OOM/exit、provider control replay、依赖策略、失败阶段、是否已形成有效模型结果和重试次数，并为每次分类记录 reason code 与证据。若有效模型结果已经形成，后续 patch/test 失败通常是能力结果；只有可证明与候选内容无关的宿主、容器或 provider 故障才是 INFRA。

## 10. 指标与统计

### 10.1 核心对比

- A/B/C/D 各自 `Resolved@1` 和通过数，分别报告 `FRESH_PRIMARY`、`PUBLIC_ANCHOR` 和全 20 题。
- `B-A`：评审与架构分解增益。
- `C-B`：角色级 RAG 增益。
- `D-C`：QA 与一次修复回路增益。
- `D-A`：完整编排净增益；唯一 confirmatory contrast 是 `FRESH_PRIMARY` 上的 D-A。

“净增题数”定义为配对 wins 减 losses。例如 D 相对 A：`D PASS/A FAIL` 数量减去 `A PASS/D FAIL` 数量，而不是只比较两个总通过数。B-A、C-B、D-C 和 public/full 切片是探索性结果，不能取其中最好的一个冒充主结论。

成本只在 A/D 均非 INFRA 的同一 paired case 集合计算，并包含失败与超时：Token 倍率为 `sum(D tokens) / sum(A tokens)`，时间倍率为 `sum(D Agent execution seconds) / sum(A Agent execution seconds)`；pair ratio 中位数仅作补充，Campaign makespan 单独报告。2.5× 是预注册工程阈值，不是统计推导值，报告同时展示 1.5/2.0/2.5/3.0× 敏感性。

### 10.2 诊断指标

- Reviewer：阻断率、错误阻断率、被阻断 case 的 Gold 可解性。
- Architect：建议文件与 Gold changed-file 的 recall、方案到最终修改的一致性。
- RAG：`GoldFileRecall@K`、注入 Token、无关 chunk 比例、跨库命中。
- QA：false pass、false reject、触发修复率、修复成功率。
- 效率：总/角色 Token、模型/工具调用、墙钟时间、Oracle 时间、每个 PASS 的 Token。
- 稳定性：超时、协议错误、基础设施错误和依赖策略违规分布。

D 必须保存 pre-QA patch。若 QA 触发修复，Oracle 同时测试 pre-QA 与最终 patch：pre 失败/final 通过计为有效修复；pre 通过但 QA 要求修复计入 false reject；QA 放行但 final 失败计入 false pass。

### 10.3 统计方法

- 第一个正式 Trial 前冻结并哈希 `analysis-plan.json`：数据切片、唯一 confirmatory contrast、哨兵 case、随机种子、缺失值规则、区间/检验、成本口径和决策门槛均不得事后修改。
- 每组通过率报告 Wilson 95% 区间。
- D-A 配对差异主区间使用 paired-proportion Newcombe score 95% CI，并运行 exact McNemar；固定种子的 case-level bootstrap 仅作敏感性分析。
- B-A、C-B、D-C 若展示显著性，使用 Holm 校正；否则只报告效应与区间。
- 报告完整 20×4 矩阵，不能只展示汇总百分比。
- 预注册 4 个哨兵 case，首次 80 Trial 完成后仅复跑 A/D 各一次，共 8 Trial。复跑使用全新 repo/cache/conversation/request ID，不读取首次轨迹；provider 支持 seed 时使用预注册的不同 seed。复跑不替换首次结果、不计算 best-of-2，只报告 A、D 各自 flip rate、`FAIL->PASS`/`PASS->FAIL` 方向和 D-A 方向是否反转。
- 每个 contrast 只使用两个 arm 都非 `INFRA_ERROR` 的 case，并明确报告 `pairedN`。同时计算缺失 pair 全部有利于前者与全部有利于后者的上下界；若决策类别随上下界变化，该 contrast 标记 `INCONCLUSIVE`。
- 除总体有效性外，每个 contrast 单独给出 validity；模型 revision/provider 指纹在整轮中变化，或同一对比的资源合同不一致，直接使该 contrast 无效。

由于主切片只有 10 题，区间会很宽。统计检验用于辅助工程决策，失败类型、仓库聚类敏感性、paired case 和稳定性证据与 p 值同等重要；不得把不显著解释为“确认无差异”。

### 10.4 决策门槛

| 结论 | 条件 |
| --- | --- |
| 值得扩大评测 | fresh D-A 净增至少 2/10、全 20 题净增至少 4/20、两个切片均不为负，且 D 的 Token/时间成本都不超过 A 的 2.5 倍 |
| 强证据 | 满足扩大门槛，fresh paired CI 下界大于 0、exact McNemar `p<0.05`、缺失值敏感性不改变类别、哨兵复跑不反转 D-A 方向 |
| 弱正向 | fresh D-A 净增 1/10，或效果为正但区间跨 0 |
| 无明显证据 | fresh D-A 净增为 0，或 pairedN/缺失值不足以判断 |
| 负向 | fresh D-A 小于 0，或 Reviewer/QA 引入的损失抵消增益 |

同时必须报告 B-A、C-B、D-C，避免完整链路变好时无法判断收益来自哪个组件。

## 11. 调度、限流与恢复

正式 Campaign 参数固定为：

- 80 个首次 `@1` Trial + 8 个哨兵稳定性 Trial，共 88 个。
- 最大活跃 Trial：6。
- 同一 case 最大活跃 Trial：1。
- 最大并发 Oracle：3。
- Agent 阶段每 Trial 45 分钟，Oracle 另有 8 分钟并发流水线。
- 目标总时长 12–13 小时，硬上限 14 小时；若探针实测 P90 推算无法在 14 小时内完成，正式运行前 fail-fast，而不是中途减少题目或跳过 arm。

调度器必须使用数据库租约和心跳，不能只依赖 JVM 队列。服务重启后：

- 已终态 Trial 不重跑。
- 租约未过期的 Trial 等待原 owner；过期后按事件证据恢复。
- 只对可证明的基础设施错误进行一次新 attempt。
- 同一 `(campaign, case, arm, replicate_no)` 始终只有一个逻辑结果。

LongCat 2.0 使用 Campaign 级并发信号量。HTTP 429 按 `Retry-After` 退避；建立连接失败、限流和 provider 5xx 只有在未形成有效模型结果时才可归为 `INFRA_ERROR`。模型返回错误答案、空 patch 或协议不合格是能力结果，不是基础设施重试理由。

Campaign 与 contrast 有效性规则：

- 超过 2 个 case 出现不可修复环境错误，本轮标记 `INVALID`。
- 任意 arm 比另一 arm 多至少 2 个未恢复基础设施失败，本轮标记 `INVALID`；低于此阈值仍必须按 pairedN 和缺失界限判断各 contrast。
- 20 题不足 10 个仓库或每仓库超过 2 题时，本轮标记 `LIMITED_GENERALIZATION`，并报告按仓库 cluster 的 leave-one-repository-out 敏感性。
- `INVALID` 仍生成完整报告，但不能用于通过/否决多 Agent 编排。

`VALID/INVALID` 是 Campaign 结果中的独立 `validity`，不是运行状态；报告成功生成时 Campaign 仍可为 `SUCCEEDED`，避免把“评测执行失败”和“评测结果因样本不平衡不可用于决策”混为一类。

## 12. 管理端与 API

评测页面默认进入“编码消融评测”，旧能力放在“历史质量评测”页签。创建 Campaign 只允许选择后端发现并通过 readiness 的固定包：

- 数据集 snapshot。
- 环境 snapshot。
- 知识 snapshot。
- LongCat 2.0 执行 profile。
- 固定 A/B/C/D 方案。

页面不接受任意镜像、命令、路径、仓库 URL、测试命令、模型地址或密钥。后端从受信 manifest 和环境配置解析这些值。

管理端至少展示：

- readiness 状态、20×4 矩阵、各 arm 通过数和配对增益。
- 已完成/活跃/等待 Trial、LongCat 活跃调用、Token 和 ETA。
- Campaign 暂停、恢复和取消。
- Trial 详情：case、arm、commit/digest、RD task、stage run、RAG 命中、patch、测试、Token、错误和事件。
- 环境或知识摘要不一致时的明确阻断原因。

现有 Run 历史、日志、产物、取消和重试入口继续工作。`CODING_BENCHMARK` 追加 Trial 列表/矩阵和 readiness 资源，不用前端轮询 88 个独立 RD 任务拼装状态。

## 13. 产物合同

每个正式 Campaign 至少生成：

- `dataset-manifest.json`
- `benchmark-provenance.json`
- `environment-manifest.json`
- `knowledge-manifest.json`
- `analysis-plan.json`
- `readiness-report.json`
- `trials.jsonl`
- `runtime-attestations/<trial-attempt>.json`
- `matrix.csv`
- `scores.json`
- `ablation-report.md`
- `failure-analysis.md`
- `run-log.txt`

`benchmark-provenance.json` 至少记录数据集/harness commit、RD-Bot commit、Pi bridge/Prompt/tool contract hash、patch extractor 版本、镜像/平台、Docker/runtime 版本和 LongCat 不可变 revision。产物记录 SHA-256、大小、相对 URI 和生成时间。HTTP 只提供脱敏、大小受限的 preview 或受控下载；不暴露完整 Prompt、原始 Pi 私有事件、密钥、宿主绝对路径、Gold Patch 或运行期扣留测试内容。

## 14. 测试与验收

### 14.1 自动测试

实施必须覆盖：

- Campaign/Trial mode-specific 状态转移、CAS、`replicate_no` 唯一约束和幂等恢复。
- Trial 租约、心跳、并发 6、同 case 并发 1、Oracle 并发 3。
- 暂停只停止新派发，恢复继续，取消终止活跃容器并保留证据。
- 仅 `INFRA_ERROR` 可重试一次；模型失败不重跑。
- A/B/C/D 的角色、RAG、QA 和修复开关在运行时真实生效。
- Agent 45 分钟、Oracle 8 分钟、10M 总 Token、64K 单次输出、60 轮、同名角色等额预算和未使用额度不回流。
- 二进制安全 patch round-trip、未来 commit/ref/object 清理、pre-QA/final patch、Oracle 分类和确定性评分。
- 候选 patch 预创建/覆盖 test path、symlink 逃逸、修改 runner/config、遗漏/跳过 test ID 时必须失败。
- Agent/Relay/Verifier 网络负向探测、一次性 relay token、Verifier 不接收 Agent repo/cache、runtime attestation 和遗留容器 reconciliation。
- RAG snapshot 隔离、跨库命中、泄漏扫描和 readiness gate。
- Python 统计的 golden test，包括 fresh/public 矩阵、净增题数、Newcombe/McNemar、Holm、pairedN、缺失界限、哨兵翻转、成本口径和无效 Campaign。
- 管理端历史兼容、矩阵、状态、固定选择器和无任意命令输入。

涉及 Pi bridge 或 QA 结果协议时，仍必须同步提示词、`result-tool.mjs` 和宿主校验；若 case 要求浏览器验证，console/network/trace/desktop+mobile 截图必须被 acceptance result 引用，Next.js 使用生产模式启动。

### 14.2 离线环境验收

每个 case 在正式运行前完成：

1. 干净 base commit 离线构建。
2. `BASE / TEST / FIX` 三状态各从干净 Verifier 工作区运行 3 次并符合冻结 transition matrix。
3. 删除 Trial 可写目录后重新创建，仍能离线构建。
4. Oracle `network=none`；从 Agent 内实际证明公网 IP、外部 DNS、UDP、软件仓库、其他 Trial 和 Docker host 不可达，只有固定 Relay 可达。
5. Agent 挂载视图不存在未来提交、Gold Patch、运行期扣留测试、Verifier 脚本和其他 arm 产物。
6. 两个并发 Trial 的 repo、cache、network 和凭证隔离；修改一个 writable upper 不影响 canonical bundle 或另一个 Trial。
7. Agent 退出后，Verifier 只收到 patch + hash + 元数据；test bundle 与 runner/config 哈希前后一致，期望 test ID 全部执行。
8. 实际 image/platform、资源限制、mount、网络、Git tree 与 `runtime-attestation.json` 一致。

### 14.3 真实预演

先运行 2 个环境探针 × 4 个 arm，共 8 个真实 LongCat 2.0 Trial。统一 Prompt、文档生成器与 RAG 参数在探针阶段完成调试并冻结：

- 验证模型、Docker、RAG、patch、Oracle、统计和报告全链路。
- 运行中重启一次评测服务，验证租约恢复。
- 取消一个运行中 Trial，验证容器结束且已有证据保留。
- 验证暂停期间不领取新 Trial，恢复后继续。

任一关键链路未通过都禁止启动正式 20×4 Campaign。

### 14.4 正式验收

- 88 个逻辑 Trial 均有唯一终态或明确未恢复基础设施结论；主 20×4 仍只使用 `replicate_no=0`。
- 正式 Agent Trial 只能访问固定 LongCat 模型中继，Oracle 全程 `network=none`，两者都没有现场依赖下载。
- A/B/C/D 使用相同冻结模型、题目、环境和资源合同；C/D 知识摘要一致。
- 输出 fresh/public/full 20×4 矩阵、全部核心/诊断指标、配对统计、缺失界限、哨兵翻转和失败分析。
- 报告能追溯到 Trial、RD task、stage run、patch、Oracle 测试和 manifest digest。
- 总时长目标 12–13 小时且不超过 14 小时；超限必须保留原因和未完成矩阵，不能伪造完整结果。

## 15. 实施边界与顺序

后续实施计划应拆为以下可独立验收的阶段，但共用本设计中的数据合同：

1. 冻结 fresh/public 题目选择器、Case Manifest、共享基础镜像、case/env 薄层、宿主 mirror、Trial 私有裁剪仓库、依赖包和 Oracle 包。
2. 增加 EvaluationMode、Campaign/Trial 持久化、状态机、租约和调度。
3. 抽取 `RequirementAgentStageOrchestrator`，以生产 D 回归测试保护现有行为，再接入 A/B/C/D。
4. 构建每题四份非泄漏文档、知识快照和 readiness gate。
5. 接入离线 workspace、Oracle、Trial 证据和确定性 verdict。
6. 扩展 Python scorer、报告和 20×4 管理端。
7. 运行 8 个探针 Trial，冻结 `analysis-plan.json`，通过后再运行正式 80 + 哨兵 8 Trial。

不得为了先看到分数而跳过离线 Gold 验证、RAG 泄漏扫描或 orchestrator 生产回归。数据模型、环境包和知识快照任一未冻结时，正式 Campaign 均不可启动。

## 16. 主要风险与处置

| 风险 | 处置 |
| --- | --- |
| 20 题样本小 | 使用配对设计、完整矩阵和失败归因，只做工程决策 |
| 公开题与 Gold Patch 进入训练语料 | 10 道 fresh-primary 承担主结论，10 道 public-anchor 只作生态对照，并冻结模型 revision/题目 provenance |
| Git future commit 泄漏答案 | mirror 只在宿主准备阶段使用，Agent 只见裁剪私有仓库；readiness 扫描 refs、reflog 和 reachable objects |
| 候选 patch 污染 Oracle 测试 | 独立 Verifier、只传 patch、受保护路径/哈希/test ID 校验，注入失败直接 FAIL |
| 单次 Agent 输出随机 | 预注册 4 个哨兵 A/D 复跑，不取最好值，报告 flip rate 和方向反转 |
| LongCat 输出慢 | Trial 放宽到 45 分钟，Campaign 并发 6，记录真实等待与模型耗时 |
| 10M Token 上限过高导致成本失控 | 作为硬上限而非目标，角色预算、60 轮和 45 分钟共同约束，报告实际成本 |
| Docker/依赖重复占盘 | 共享基础厚层 + case/env 薄层、内容寻址依赖 lower layer，仅创建活跃 Trial writable upper；按实测容量 + 20% 余量 gate |
| 正式运行现场下载失败 | 预构建依赖并离线复验；Gold 修改依赖的题目直接排除 |
| RAG 泄漏或正式集调参 | 文档构建与 Gold/Oracle 分权，只在 2 个探针调管线；正式集 Gold 指标仅事后报告 |
| 四组资源不公平 | 同模型/提交/环境/外层限制、同名角色等额预算，平衡 arm 顺序并按 case 配对；新增角色成本单独入账 |
| QA 表面上提升但只是多花预算 | D 首次 Coding 预算固定且修复预留不回流，单独报告 D-C 和成本倍率 |
| 服务重启造成重复运行 | Trial 唯一约束、数据库租约、心跳、CAS 和幂等恢复 |
| 旧评测页面被重构破坏 | mode 兼容、旧 source/状态机保留、历史 payload 双读测试 |

## 17. 完成定义

Evaluation V2 只有在以下条件全部满足时才算实现完成：

- 旧 fixture/rag-http/task-run 历史评测仍可创建或读取。
- 20 题与 2 个探针拥有可复现的冻结 dataset/environment/knowledge snapshot。
- 不存在 40 个完整 case/oracle 镜像；Oracle 复用共享基础镜像。
- 所有正式 Agent Trial 仅有模型中继网络且不下载依赖，Oracle 完全断网。
- A/B/C/D 的运行配置和实际阶段证据一致。
- 8 个探针 Trial 通过重启、暂停、恢复、取消和报告验收。
- 88 Trial 在一天内完成，或按规则输出真实、可追溯的无效/超时结论。
- 主结论基于 fresh-primary 的 Oracle `Resolved@1` D-A 配对结果，并附 public/full 对照、稳定性、成本和失败归因。

## 18. 目标验证命令

下列命令是实施完成后的仓库级验收入口，实施计划不得把它们替换成只验证 mock 的捷径：

| 检查 | 命令 | 预期 |
| --- | --- | --- |
| Java 状态机、调度、编排、Oracle 与持久化 | `./mvnw -pl engine,exec,bootstrap -am test` | 全部通过 |
| Pi bridge 与预算/结果协议 | `cd bootstrap/src/main/resources/executor/pi && npm test` | 全部通过 |
| Python 数据集、判分与报告 | `python3 -m unittest discover -s scripts/evaluation/tests -p 'test_*.py'` | 全部通过 |
| 前端合同 | `node --experimental-strip-types --test frontend/test/*.test.ts` | 全部通过 |
| 前端类型 | `cd frontend && npm run typecheck` | 0 错误 |
| 前端生产构建 | `cd frontend && npm run build` | 构建成功 |
| 20 题离线 readiness | `python3 scripts/evaluation/rd_eval_prepare_coding_benchmark.py verify --config scripts/evaluation/coding-benchmark-v2.yaml` | 20 题 + 2 探针、Gold/依赖/知识/网络检查全部 PASS |

涉及浏览器题时，还要按 `docs/superpowers/specs/2026-07-28-qa-evidence-reference-and-production-mode-spec.md` 运行 Pi、执行器和证据引用验证。正式 88 Trial 的真实性不能由单元测试替代，最终仍以第 14.4 节的 Campaign 产物为验收证据。

## 19. 社区依据与采用边界

- Multi-SWE-bench：采用 per-PR 环境与 `BASE/TEST/FIX` 三状态和显式 `PASSED/FAILED/NONE/SKIPPED`；不直接照搬其大规模运行配置。<https://arxiv.org/html/2504.02605>
- SWE-bench：采用官方分层镜像与容量规划起点；实际镜像仍由本项目按 20 题构建并验收。<https://www.swebench.com/SWE-bench/guides/docker_setup/>
- Claw-SWE-Bench：采用固定 prompt/预算/workspace/patch/evaluator 的 harness 对比原则、集中 patch 提取和 future-commit cleanup；其单次运行限制由本方案的哨兵复跑补强。<https://arxiv.org/html/2606.12344>
- Harbor：采用 Agent 与 Verifier 分离、显式网络模式和最小产物传递；具体 relay 拓扑按 `rd-pi-bridge.mjs` 真实边界实现。<https://www.harborframework.com/docs/tasks>
- Terminal-Bench 2.0 与 Agent 随机性研究：采用“多次运行用于稳定性判断”的原则，但受一天预算约束，只复跑预注册哨兵，不做全部 20×4 五次。<https://arxiv.org/html/2601.11868>、<https://arxiv.org/html/2602.07150>
- NIST randomized block design：采用“可控因素分块、其余因素随机化”，落实为四个平衡 arm 序列与 wave 记录。<https://www.itl.nist.gov/div898/handbook/pri/section3/pri332.htm>
- OpenAI 对 SWE-bench Verified 污染的复盘：公开题不能承担模型未见能力结论，因此引入 fresh-primary/public-anchor 双切片。<https://openai.com/index/why-we-no-longer-evaluate-swe-bench-verified/>
- SWE-bench issue #538：候选补丁可能通过预创建测试路径污染测试注入，因此 Verifier 必须校验注入、受保护路径、哈希和精确 test ID。<https://github.com/SWE-bench/SWE-bench/issues/538>
