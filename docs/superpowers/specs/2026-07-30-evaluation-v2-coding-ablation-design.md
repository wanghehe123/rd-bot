# Evaluation V2：多 Agent 编码能力消融评测设计

日期：2026-07-30

状态：设计已确认，等待实施计划

范围：约 20 道真实编码题、LongCat 2.0、多 Agent 编排消融、RAG 文档、依赖离线 Docker 环境、Oracle 判分与管理端评测重构。

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

当前 `EvaluationSource` 只有 `FIXTURE / RAG_HTTP / TASK_RUN`；`TASK_RUN` 收集已存在任务的快照，再交给 Python 评分，不负责创建同题多组 Agent 工作流、隔离代码工作区或运行隐藏测试。现有 `rd_eval_quality_v1.jsonl` 共 48 条，主要是 fixture、角色合同和任务审计样本，不能作为真实编码修复能力的主测试集。

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
4. 在正式运行前完成镜像、依赖、代码镜像、RAG 文档和隐藏测试准备；Trial 运行期间只允许访问固定 LongCat 模型中继，禁止联网下载依赖。
5. 以可复现 Oracle 测试作为主判分依据，模型 Judge 不能覆盖确定性结果。
6. 输出 20×4 结果矩阵、配对统计、失败归因、Token/时间成本和完整审计证据。

### 2.2 非目标

- 首轮不混入 Terminal-Bench 2.0、SWE-Lancer 或通用命令行任务；这些任务形态留到后续独立 Campaign。
- 不把 SWE-bench Verified 作为首轮主测试集，只作为历史结果对照。
- 不做多次采样取最好结果；每个 case/arm 只有一次能力 Trial，指标为 `@1`。
- 不在管理页面开放任意 Docker 镜像、Shell、宿主路径、测试命令或密钥输入。
- 20 题属于工程决策型小样本，不用于宣称具有论文级统计普适性。

## 3. 题集设计

### 3.1 主测试集

首轮只使用 Multi-SWE-bench 的 Java 与 TypeScript/JavaScript 真实仓库修复题：

| 维度 | 配额 |
| --- | ---: |
| Java | 12 |
| TypeScript/JavaScript | 8 |
| 中高难度 | 8 |
| 高难度 | 8 |
| 高难度+ | 4 |
| 正式计分题 | 20 |
| 环境探针题 | 2，不计分 |

题目选择必须同时满足：

- 基础提交、问题描述、Gold Patch 和 fail-to-pass/pass-to-pass 测试均可固定。
- 干净基础提交能够稳定复现失败，Gold Patch 能够稳定通过 Oracle。
- 单次 Oracle 测试目标不超过 8 分钟。
- 不依赖外部服务、真实密钥或运行期互联网。
- 生产代码修改通常不少于 2 个文件。
- 至少 12 题的问题描述不直接给出目标文件名。
- 至少 8 题涉及跨模块、状态传播、协议或回归约束。
- 具有隐藏回归测试，避免只针对公开失败做表面修补。

排除以下题型：

- 纯文档、格式化、拼写修复。
- 明显的一行修改或机械重命名。
- Gold Patch 新增第三方依赖、修改锁文件或要求联网安装依赖。
- 依赖不稳定外部服务、专用硬件、交互式 GUI 或超过 Oracle 时间预算。
- 基础提交在离线环境中不能稳定构建，或 Gold Patch 不能重复通过。

精确 case ID 不写死在本设计中。实施准备阶段必须根据上述确定性规则生成 `dataset-manifest.json`，并在任何正式 Trial 启动前冻结 case ID、仓库、base commit、测试命令、Gold Patch 哈希和数据集总哈希。冻结后不得依据某一消融组的表现替换题目。

### 3.2 环境探针

两个探针分别覆盖 Java 和 TypeScript/JavaScript。探针走与正式题完全相同的工作区、模型、RAG、Oracle 和报告链路，但不进入最终 20 题分母。它们用于发现镜像、依赖、速率限制、重启恢复和产物采集问题，不能用于针对正式题调 Prompt。

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

A/B 不进行任何角色级知识库检索，也不注入为评测生成的四份 RAG 文档；这些文档不写进被测代码仓库。四组的初始需求上下文都只包含 issue、冻结仓库元数据和相同的非 RAG 系统合同，`REQUIREMENT_BASE` 不读取该 case 的评测知识库。只有 C/D 的 `AGENT_ROLE` consumer 能访问同一个冻结 `knowledgeSnapshotId`。不同 arm 不共享可写工作区、对话、检索结果或依赖缓存副本。

每个 case 的 arm 顺序使用分块随机化，避免 LongCat 服务在某一时间段变慢时系统性偏向某一组。禁止先跑完全部 A 再运行 B/C/D。

### 4.3 Trial 预算

每个 Trial 的硬限制为：

- 墙钟时间：45 分钟。
- 输入加输出总 Token：10,000,000。
- 单次模型调用最大输出：65,536 Token。
- Agent 与工具交互合计：最多 60 次。

10M 是防止异常循环的安全上限，不是要求模型主动消耗的目标。报告必须记录实际输入、输出、缓存命中、模型调用数、工具调用数和耗时。预算以 provider 返回的 usage 为优先真值；provider 缺失 usage 时使用版本冻结的保守估算器，并写入 `usageEstimated=true`，预算执行取报告值与估算值的较大者。预算按整个 Trial 统一记账，角色间分配如下：

| Arm | 角色预算 |
| --- | --- |
| A | Coding 100%，即 10M |
| B/C | Reviewer 10%，Architect 20%，Coding 70% |
| D | Reviewer 8%，Architect 16%，首次 Coding 56%，QA 8%，修复预留 12% |

D 的修复预留仅在 QA 明确请求编码修复时可用；未使用的预留不回流到首次 Coding，避免 D 通过额外初始预算获得不公平优势。达到时间、Token、输出或轮次上限都立即停止，并按真实阶段归类为 `TIMEOUT` 或 `PROTOCOL_ERROR`，不得自动增加预算重跑。

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
       -> clean workspace + hidden tests
       -> deterministic verdict
  -> immutable trials.jsonl
  -> Python scorer/report generator
  -> matrix + ablation report + failure analysis
```

现有 `EvaluationRun` 和 `rd_evaluation_runs` 升级为 Campaign 主记录，不平行创建第二套 Run 聚合。新增 `EvaluationMode`：

- `LEGACY_QUALITY`：保留当前 fixture/rag-http 录制、评分和 diff。
- `TASK_AUDIT`：保留当前已完成 RD 任务快照审计。
- `CODING_BENCHMARK`：创建 20×4 Trial 并执行真实代码修复。

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

- `rd_evaluation_trials`：一个 `(campaign, case, arm)` 的逻辑 Trial。
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

- `campaign_id / case_id / arm / status / verdict / attempt_no / version`。
- `rd_task_id / stage_run_ids_json`。
- 冻结的 Trial、模型、工作流、知识库和环境配置快照。
- 输入/输出 Token、模型调用、工具调用和墙钟时间。
- 初始 patch、pre-QA patch、最终 patch 及其哈希。
- 公开测试、隐藏测试、构建和 Oracle 证据引用。
- 错误分类、错误摘要、租约 owner、租约过期时间、心跳与时间戳。

数据库必须有唯一约束 `(campaign_id, case_id, arm)`。80 个逻辑 Trial 在 Campaign 开始运行前一次性创建，调度器通过数据库条件更新领取租约。服务重启后恢复 `QUEUED` 和租约过期的非终态 Trial，不重复创建任务或覆盖已完成结果。

只有纯基础设施错误允许进入一次 `RETRY_PENDING`；调度器在 `RETRY_PENDING -> QUEUED` 时把同一逻辑 Trial 的 `attempt_no` 增加 1，事件和第一次错误证据必须保留。第二次基础设施错误进入终态 `FAILED/INFRA_ERROR`。模型结果错误、无 patch、测试失败、超时、评审阻断或协议失败不得重跑。

## 6. Trial 数据流

1. Campaign `PREPARING` 校验冻结数据集、知识库、环境包、镜像摘要、LongCat 配置和可用磁盘。
2. 一次性创建 80 个 Trial，按 case 分块随机化 arm 顺序。
3. Worker 领取 Trial，基于只读 Git mirror 的 base commit 创建独立可写 worktree。
4. 将内容寻址依赖包复制或 reflink 到 Trial 自有 `/work/cache`，挂载 Agent 可见的公开仓库和工具。
5. 创建标记为评测用途的 patch-only RD 任务，按固定 `AgentWorkflowPlan` 运行角色链。
6. 同一个 Trial 内 Reviewer、Architect、Coding、QA 和一次修复共用 repo 与 cache，以便传递真实代码状态；不同 Trial 完全隔离。
7. 保存 Agent 最终 patch。D 组在首次 Coding 完成、QA 开始前额外保存 pre-QA patch。
8. Agent 容器退出后，Oracle 在全新工作区应用 patch，再只读注入隐藏测试并执行固定测试命令。
9. D 发生修复时，Oracle 分别测试 pre-QA patch 和最终 patch，用于判断 QA 真正修复、误拒绝或未改善。
10. Trial 只写不可变证据引用；Campaign 结束后导出冻结 `trials.jsonl`。
11. Python scorer只读取 `trials.jsonl` 和 manifest，不再次调用模型或修改 Trial 事实，生成统计和报告。

## 7. Docker 与离线依赖设计

### 7.1 存储优化结构

不构建 20 个 Agent 完整镜像和 20 个 Oracle 完整镜像。采用四层共享结构：

1. **共享基础镜像**：按工具链准备 2–3 个镜像，至少覆盖 Java 21 + Maven/Gradle、Node.js + npm/pnpm/Yarn；确有浏览器题时增加浏览器 QA 镜像。
2. **只读仓库镜像**：按上游仓库保存本地 bare Git mirror，多个 case 共享对象，不为每题复制完整 Git 历史。
3. **内容寻址依赖包**：按 wrapper、构建文件、锁文件和工具链摘要生成 Maven/Gradle/npm/pnpm/Yarn 离线缓存包。
4. **20 个轻量 Case Manifest**：只记录提交、摘要、挂载和测试合同，不包含完整 rootfs。

Oracle 复用相同语言基础镜像和仓库 mirror；隐藏测试由宿主以只读方式在 Oracle 阶段挂载，因此不再创建独立 Oracle 镜像。

规划容量预期为：基础镜像约 4–8 GB、仓库 mirror 数 GB、依赖包约 10–25 GB；实际容量以选定 case 后的构建报告为准。并发上限为 6，因此只保留最多 6 组活跃 Trial 的可写 workspace/cache 副本。正式运行前必须测量真实占用和峰值需求，磁盘不足时 fail-fast，不在运行中临时删共享资产。

### 7.2 Case Manifest

每个 case 的 manifest 至少包含：

```text
caseId
repositoryMirrorId
baseCommit
baseImageDigest
dependencyBundleSha256
oracleTestBundleSha256
toolchainSha256
agentTestCommands
oracleTestCommands
agentNetworkPolicy=MODEL_RELAY_ONLY
oracleNetworkMode=none
browserQaRequired
```

所有摘要进入 `environment-manifest.json`。正式 Campaign 引用摘要而不是浮动 tag；镜像、依赖包或测试包变化都必须产生新的 environment snapshot。

### 7.3 构建责任与网络边界

环境准备由本项目实施方负责，包括：选择 case、编写基础镜像、构建 Git mirror、预取依赖、制作 Oracle 测试包、生成 manifest、运行离线验证和记录 digest。不能把依赖准备转交给正式 Trial 中的 LongCat Agent。

可信构建阶段允许联网下载已锁定依赖。Oracle 一律 `network=none`。Agent 容器由于 `rd-pi-bridge.mjs` 在容器内发起 provider 请求，使用专用内部 Docker 网络，只能访问宿主控制的 LongCat 模型中继；中继只转发冻结 profile 中的模型协议和上游地址，容器不能直连公网、DNS、软件仓库或其他服务。Maven、Gradle、npm、pnpm 和 Yarn 同时使用离线参数，使误安装立即失败，而不是等待网络超时。构建或 Agent 日志出现依赖下载、软件仓库访问或非模型中继网络请求，即判环境包或 Trial 不合格。

Trial 可写缓存必须位于任务局部 `/work/cache`：

- 同一 Trial 的编码、QA 和修复尝试复用 repo/cache，避免重复下载和丢失代码状态。
- 不同 case、不同 arm 使用独立可写缓存副本，不允许并发写共享缓存。
- 基础镜像、Git mirror、依赖包和 Oracle 测试包均只读挂载。
- Agent 看不到 Gold Patch、隐藏测试目录、Oracle 启动脚本或其他 arm 的产物。

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
- 隐藏测试、隐藏测试名或隐藏断言。
- 其他 Agent/arm 的输出。
- 根据正式评测失败追加的人工提示。

知识构建进程与 Gold/Oracle 资产分权：文档生成阶段不能读取 Gold Patch 和隐藏测试。冻结语料后，可由只读评测检查器使用 Gold changed-file 路径作为离线标签计算检索覆盖，但不得把这些标签写回文档、Prompt 或索引元数据。

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
- 使用 Gold changed-file 路径仅作为离线标签计算，20 题聚合 `GoldFileHit@5 >= 75%`。
- 若未达 75%，只能修改对所有 case 一致的通用文档生成/切块规则并重建全部知识库，不能按单题 Gold 信息手工调文档。
- 跨知识库命中数为 0。
- Gold Patch/隐藏测试内容泄漏扫描结果为 0。

冻结 `knowledgeSnapshotId`、语料 manifest、文档摘要、chunker 版本、embedding 配置、检索配置和 readiness 报告。C/D 的任意一项摘要不同都使 Campaign 无效。

## 9. Oracle 与 Trial 结论

### 9.1 Case 预验证

正式纳入前，每题必须通过两次确定性验证：

1. base commit：pass-to-pass 测试通过，至少一个 fail-to-pass 测试失败。
2. Gold Patch：patch 可应用，全部 fail-to-pass 与 pass-to-pass 测试通过。

两次验证都在 `network=none` 的 Oracle 环境中进行。重复运行结果不一致的 case 直接排除。

### 9.2 主指标

主指标为 `Resolved@1`。仅当以下条件全部满足时 Trial 记为 PASS：

- Agent 产生非空 patch，且能干净应用到冻结 base commit。
- 全部 fail-to-pass 测试通过。
- 全部 pass-to-pass 回归测试通过。
- 没有违反依赖、网络、路径或协议策略。

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

## 10. 指标与统计

### 10.1 核心对比

- A/B/C/D 各自 `Resolved@1` 和 20 题通过数。
- `B-A`：评审与架构分解增益。
- `C-B`：角色级 RAG 增益。
- `D-C`：QA 与一次修复回路增益。
- `D-A`：完整编排净增益。

“净增题数”定义为配对 wins 减 losses。例如 D 相对 A：`D PASS/A FAIL` 数量减去 `A PASS/D FAIL` 数量，而不是只比较两个总通过数。成本倍率固定为两个比值：D 全部有效 Trial 的总 Token / A 全部有效 Trial 的总 Token，以及 D/A 的 Trial 墙钟时间中位数；“成本不超过 2.5 倍”要求两个比值都不超过 2.5。

### 10.2 诊断指标

- Reviewer：阻断率、错误阻断率、被阻断 case 的 Gold 可解性。
- Architect：建议文件与 Gold changed-file 的 recall、方案到最终修改的一致性。
- RAG：`GoldFileRecall@K`、注入 Token、无关 chunk 比例、跨库命中。
- QA：false pass、false reject、触发修复率、修复成功率。
- 效率：总/角色 Token、模型/工具调用、墙钟时间、Oracle 时间、每个 PASS 的 Token。
- 稳定性：超时、协议错误、基础设施错误和依赖策略违规分布。

D 必须保存 pre-QA patch。若 QA 触发修复，Oracle 同时测试 pre-QA 与最终 patch：pre 失败/final 通过计为有效修复；pre 通过但 QA 要求修复计入 false reject；QA 放行但 final 失败计入 false pass。

### 10.3 统计方法

- 每组通过率报告 Wilson 95% 区间。
- 配对差异使用 10,000 次 case-level bootstrap。
- A 与 D 额外运行 exact McNemar 检验。
- 报告完整 20×4 矩阵，不能只展示汇总百分比。

由于样本量只有 20，统计检验用于辅助工程决策，失败类型和配对 case 证据与 p 值同等重要。

### 10.4 决策门槛

| 结论 | 条件 |
| --- | --- |
| 值得扩大评测 | D-A 净增至少 4 题，且 D 的实际 Token/时间成本不超过 A 的 2.5 倍 |
| 强证据 | D-A 净增至少 6 题，且配对 bootstrap 95% 下界大于 0 |
| 弱正向 | D-A 净增 2–3 题 |
| 无明显证据 | D-A 净增少于 2 题 |
| 负向 | D 总体低于 A，或 Reviewer/QA 引入的损失抵消增益 |

同时必须报告 B-A、C-B、D-C，避免完整链路变好时无法判断收益来自哪个组件。

## 11. 调度、限流与恢复

正式 Campaign 参数固定为：

- 80 个 Trial。
- 最大活跃 Trial：6。
- 同一 case 最大活跃 Trial：1。
- 最大并发 Oracle：3。
- 单 Trial 45 分钟。
- 目标总时长 11–12 小时，硬上限 14 小时。

调度器必须使用数据库租约和心跳，不能只依赖 JVM 队列。服务重启后：

- 已终态 Trial 不重跑。
- 租约未过期的 Trial 等待原 owner；过期后按事件证据恢复。
- 只对可证明的基础设施错误进行一次新 attempt。
- 同一 `(campaign, case, arm)` 始终只有一个逻辑结果。

LongCat 2.0 使用 Campaign 级并发信号量。HTTP 429 按 `Retry-After` 退避；建立连接失败、限流和 provider 5xx 只有在未形成有效模型结果时才可归为 `INFRA_ERROR`。模型返回错误答案、空 patch 或协议不合格是能力结果，不是基础设施重试理由。

Campaign 有效性规则：

- 超过 2 个 case 出现不可修复环境错误，本轮标记 `INVALID`。
- 任意 arm 比另一 arm 多至少 2 个未恢复基础设施失败，本轮标记 `INVALID`。
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

现有 Run 历史、日志、产物、取消和重试入口继续工作。`CODING_BENCHMARK` 追加 Trial 列表/矩阵和 readiness 资源，不用前端轮询 80 个独立 RD 任务拼装状态。

## 13. 产物合同

每个正式 Campaign 至少生成：

- `dataset-manifest.json`
- `environment-manifest.json`
- `knowledge-manifest.json`
- `readiness-report.json`
- `trials.jsonl`
- `matrix.csv`
- `scores.json`
- `ablation-report.md`
- `failure-analysis.md`
- `run-log.txt`

产物记录 SHA-256、大小、相对 URI 和生成时间。HTTP 只提供脱敏、大小受限的 preview 或受控下载；不暴露完整 Prompt、原始 Pi 私有事件、密钥、宿主绝对路径、Gold Patch 或隐藏测试内容。

## 14. 测试与验收

### 14.1 自动测试

实施必须覆盖：

- Campaign/Trial mode-specific 状态转移、CAS、唯一约束和幂等恢复。
- Trial 租约、心跳、并发 6、同 case 并发 1、Oracle 并发 3。
- 暂停只停止新派发，恢复继续，取消终止活跃容器并保留证据。
- 仅 `INFRA_ERROR` 可重试一次；模型失败不重跑。
- A/B/C/D 的角色、RAG、QA 和修复开关在运行时真实生效。
- 45 分钟、10M 总 Token、64K 单次输出、60 轮以及角色预算。
- pre-QA/final patch、Oracle 分类和确定性评分。
- RAG snapshot 隔离、跨库命中、泄漏扫描和 readiness gate。
- Python 统计的 golden test，包括矩阵、净增题数、bootstrap 输入和无效 Campaign。
- 管理端历史兼容、矩阵、状态、固定选择器和无任意命令输入。

涉及 Pi bridge 或 QA 结果协议时，仍必须同步提示词、`result-tool.mjs` 和宿主校验；若 case 要求浏览器验证，console/network/trace/desktop+mobile 截图必须被 acceptance result 引用，Next.js 使用生产模式启动。

### 14.2 离线环境验收

每个 case 在正式运行前完成：

1. 干净 base commit 离线构建。
2. Gold Patch 离线 Oracle 通过。
3. 删除 Trial 可写目录后重新创建，仍能离线构建。
4. Oracle 日志无任何网络访问；Agent 日志无下载、软件仓库、外部 DNS 或模型中继之外的网络请求。
5. Agent 挂载视图不存在隐藏测试、Gold Patch 和其他 arm 产物。
6. 两个并发 Trial 的 `/work/cache` inode/写路径隔离。

### 14.3 真实预演

先运行 2 个环境探针 × 4 个 arm，共 8 个真实 LongCat 2.0 Trial：

- 验证模型、Docker、RAG、patch、Oracle、统计和报告全链路。
- 运行中重启一次评测服务，验证租约恢复。
- 取消一个运行中 Trial，验证容器结束且已有证据保留。
- 验证暂停期间不领取新 Trial，恢复后继续。

任一关键链路未通过都禁止启动正式 20×4 Campaign。

### 14.4 正式验收

- 80 个逻辑 Trial 均有唯一终态或明确未恢复基础设施结论。
- 正式 Agent Trial 只能访问固定 LongCat 模型中继，Oracle 全程 `network=none`，两者都没有现场依赖下载。
- A/B/C/D 使用相同冻结模型、题目、环境和资源合同；C/D 知识摘要一致。
- 输出 20×4 矩阵、全部核心/诊断指标、配对统计和失败分析。
- 报告能追溯到 Trial、RD task、stage run、patch、Oracle 测试和 manifest digest。
- 总时长目标 11–12 小时且不超过 14 小时；超限必须保留原因和未完成矩阵，不能伪造完整结果。

## 15. 实施边界与顺序

后续实施计划应拆为以下可独立验收的阶段，但共用本设计中的数据合同：

1. 冻结题目选择器、Case Manifest、共享镜像、Git mirror、依赖包和 Oracle 包。
2. 增加 EvaluationMode、Campaign/Trial 持久化、状态机、租约和调度。
3. 抽取 `RequirementAgentStageOrchestrator`，以生产 D 回归测试保护现有行为，再接入 A/B/C/D。
4. 构建每题四份非泄漏文档、知识快照和 readiness gate。
5. 接入离线 workspace、Oracle、Trial 证据和确定性 verdict。
6. 扩展 Python scorer、报告和 20×4 管理端。
7. 运行 8 个探针 Trial，通过后再运行正式 80 Trial。

不得为了先看到分数而跳过离线 Gold 验证、RAG 泄漏扫描或 orchestrator 生产回归。数据模型、环境包和知识快照任一未冻结时，正式 Campaign 均不可启动。

## 16. 主要风险与处置

| 风险 | 处置 |
| --- | --- |
| 20 题样本小 | 使用配对设计、完整矩阵和失败归因，只做工程决策 |
| LongCat 输出慢 | Trial 放宽到 45 分钟，Campaign 并发 6，记录真实等待与模型耗时 |
| 10M Token 上限过高导致成本失控 | 作为硬上限而非目标，角色预算、60 轮和 45 分钟共同约束，报告实际成本 |
| Docker/依赖重复占盘 | 共享基础镜像、Git mirror、内容寻址依赖包，仅复制活跃 Trial 可写层 |
| 正式运行现场下载失败 | 预构建依赖并离线复验；Gold 修改依赖的题目直接排除 |
| RAG 泄漏答案 | 文档构建与 Gold/Oracle 分权，语料冻结、哈希和泄漏扫描 |
| 四组资源不公平 | 同模型/提交/环境/预算，分块随机 arm 顺序，按 case 配对 |
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
- 80 Trial 在一天内完成，或按规则输出真实、可追溯的无效/超时结论。
- 主结论基于 Oracle `Resolved@1`，并附配对增益、成本和失败归因。

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

涉及浏览器题时，还要按 `docs/superpowers/specs/2026-07-28-qa-evidence-reference-and-production-mode-spec.md` 运行 Pi、执行器和证据引用验证。正式 80 Trial 的真实性不能由单元测试替代，最终仍以第 14.4 节的 Campaign 产物为验收证据。
