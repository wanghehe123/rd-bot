# RD-Bot 简历写法指南

日期：2026-07-07

本文参考“Ragent 项目简历写法”的结构，但按 RD-Bot 当前代码与文档重新组织。核心原则是：不要把 RD-Bot 写成普通聊天机器人，也不要把还没有真实评测报告支撑的提升数字写死。RD-Bot 更适合定位为“研发交付编排系统 / 多 Agent 研发自动化平台 / RAG 证据驱动的需求交付平台”。

## 1. 项目名称

可根据投递岗位选择下面任一名字：

- RD-Bot - 企业级研发交付编排智能体平台
- DevFlow Agent - RAG 驱动的研发交付自动化平台
- AutoRD - 多 Agent 需求开发与验证平台
- RAGOps Copilot - 研发知识检索与交付闭环系统
- CodeFlow Agent - 从需求到 PR 的智能研发助手
- Feishu DevAgent - 飞书工单到研发交付智能体

如果简历里已经有一个通用 RAG 项目，RD-Bot 建议突出“研发交付”和“多 Agent 编排”，降低与普通知识库问答项目的重复感。

## 2. 项目简介

### 简介 1：强调技术深度

RD-Bot 是基于 Java 21 + Spring Boot 3.5 构建的研发交付编排平台，围绕“需求/工单 -> RAG 证据收集 -> 多角色 Agent 执行 -> QA 验证 -> PR -> 报告审计”的交付链路，设计了任务状态机、角色上下文包、阶段运行记录、Docker 执行器、provider fallback、PolicyGate 门控和经验沉淀机制。系统采用端口适配器架构隔离 Feishu、RocketMQ、GitHub、Docker、PostgreSQL 等外部依赖，并通过阶段产物、告警、Prometheus 指标和评测脚本保障流程可追踪、可恢复、可验收。

### 简介 2：强调业务价值

RD-Bot 面向研发团队的需求交付和缺陷修复场景，解决传统 AI 编码助手“只会生成代码、缺少证据、缺少验收、不可审计”的问题。平台接入飞书工单/管理台任务，自动收集需求材料和知识库证据，按需求评审、方案设计、编码、QA 四个角色分阶段执行，并在交付复核通过后创建 PR、沉淀经验和输出报告。系统把每次执行拆成可查询的任务状态、阶段状态、prompt/result/log 产物和告警记录，适合生产环境中的自动化研发交付闭环。

### 简介 3：场景化版本

以内部研发平台为场景，建设从飞书需求到 GitHub PR 的 AI 研发助手。针对普通 RAG 问答无法完成复杂交付、单 Agent 上下文过长、执行结果不可复核等痛点，引入角色化上下文裁剪、多 Agent 阶段状态机、Docker 沙箱执行、QA 真实命令验收、交付复核和经验复用机制；同时搭建自建指标 + RAGAS/LLM-as-judge 的评测框架，围绕 evidence_hit@5、faithfulness、需求阻断准确率、QA skipped rate 等指标持续回归。

## 3. 技术架构写法

推荐写法：

Java 21、Spring Boot 3.5、Maven 多模块、PostgreSQL、MyBatis-Plus、Redis/Redisson、RocketMQ、Docker Claude Code、GitHub API、Feishu OpenAPI、React + Vite + TypeScript、Prometheus、Python 评测脚本、RAGAS/LLM-as-judge。

如果篇幅很紧，可简化为：

Java 21 + Spring Boot 3.5 + PostgreSQL + Redis/Redisson + RocketMQ + Docker + GitHub + Feishu + React/Vite。

注意：当前代码里 RAG 核心使用 `VectorStore` 抽象和内存向量实现，pgvector 更适合作为生产适配方向来讲，不要在没有落地证据时直接写成“已基于 pgvector 上线”。

## 4. 个人职责写法

### 核心亮点：简历空间有限时优先保留

1. 设计研发交付主状态机，覆盖 `CREATED -> MATERIAL_READY -> CONTEXT_READY -> PLAN_GENERATED -> WAITING_POLICY -> EXECUTING -> VALIDATING -> PR_CREATING -> COMPLETED` 等节点，并将失败、人工介入、取消、死信和恢复状态显式建模，避免执行流程依赖内存隐式推进。

2. 基于多 Agent 分阶段编排实现需求交付链路，固定拆分 `REQUIREMENT_REVIEWER -> SOLUTION_ARCHITECT -> CODING_AGENT -> QA_AGENT` 四个角色；每个阶段独立记录 attempt、provider、prompt/result artifact、错误分类和阶段事件，支持失败阻断与人工恢复。

3. 设计角色化 RAG 上下文包 `RoleContextPackage`，按角色裁剪证据、验收标准、风险提示和上下文预算，并保留 omitted evidence，解决单次大上下文投喂导致的证据不可追踪和角色职责混乱问题。

4. 在需求评审阶段实现业务门控：当模型输出 `NEED_INFO / NEEDS_HUMAN / UNSAFE / REJECTED / FAILED / BLOCKED` 等结果时，控制面将阶段标记为 `FAILED_NEEDS_HUMAN` 并阻断方案、编码和 QA，保证“模型响应成功”不等于“需求可交付”。

5. 将 Docker Claude Code、GitHub PR、Feishu、RocketMQ 等外部能力收敛到端口/适配器，核心 `engine` 只负责编排和状态推进；编码阶段不得直接创建 PR，必须经过 QA 和交付复核后由控制面发布，降低越权交付风险。

6. 搭建执行侧 provider fallback 与健康治理链路，记录 provider attempts、active provider、失败分类和降级告警；配合 Docker 执行产物采集，将 patch、result.json、test.log、docker metadata 等归档为可审计交付证据。

7. 基于 PostgreSQL 持久化任务、阶段运行、角色上下文、阶段产物、审计事件和经验条目；经验沉淀绑定 sourceArtifactId、contentHash、redacted 等字段，支持后续按任务和语义检索复用。

8. 搭建端到端评测框架，按 `EvalSample -> EvalRecord -> MetricResult -> report` 组织样本、录制、评分和报告，覆盖 RAG 检索、需求评审、方案、编码、QA 和交付闭环指标，并支持 A/B diff、人工校正列和失败样本落盘。

### 补充亮点：视篇幅增减

- 基于 Redis/Redisson 实现公平分布式限流，使用 expirable semaphore 控制并发、ZSET 维护 FIFO 队列、Lua 脚本原子抢占队首窗口、Pub/Sub 唤醒本地 poller，支撑 SSE/chat 类接口的排队和超时拒绝。
- 按 workload 隔离线程池，将需求交付、Docker/model I/O、修复队列、知识摄取和维护任务拆成独立 bounded executor，避免长耗时模型调用拖垮队列消费或摄取任务。
- 设计 Skill 注册、策略门禁和安装端口，角色必须命中 allowedRoles，高风险 Skill 可进入人工审批，安装器校验 sourceUri、checksum、version 和风险等级后才执行。
- 接入 Feishu 工单/IM 与 RocketMQ 队列，消息保持瘦身，只传 ticketId、priority、traceId、attempt、source 等元数据，需求正文、附件和密钥不进 MQ。
- 建立轻量 trace 和 retrieval log，暴露 `/rag/traces`、`/rag/v3/tasks/{taskId}`、`/actuator/prometheus` 等查询面，方便定位 RAG 命中、阶段耗时、告警和交付复核问题。
- 将知识库、文档、分块、意图树、摄取管道、项目和任务等管理台数据收敛到 Store 端口与 PostgreSQL 适配，避免生产数据依赖 JVM 内存。

## 5. 指标写法

不要照搬“提升 28.5%”这类数字。RD-Bot 的指标要来自真实评测报告、HTTP QA、生产验收台账或 `qa-runs/evaluation` 产物。

可写成三类：

1. 已有真实数据时：
   - “在 xx 条评测样本上，evidence_hit@5 达到 xx%，mrr@10 达到 xx，faithfulness 达到 xx。”
   - “多 Agent 生产验收覆盖 xx 个验收点，包含 provider preflight、Docker coding、QA blocker、Feishu alert、GitHub PR 远端反查和 observability metrics。”
   - “真实 HTTP QA 覆盖 xx 个接口，记录 status code、关键断言和失败证据。”

2. 只有框架和口径、暂未跑稳定数据时：
   - “设计并实现 RAG 与多 Agent 评测框架，覆盖 intent_top1、evidence_hit@5、mrr@10、faithfulness、QA skipped rate 等指标，支持失败样本回流和 A/B diff。”
   - “定义需求评审阻断准确率、方案验收映射覆盖率、编码 forbidden file touch rate、QA false pass rate 等生产门禁指标。”

3. 不建议写：
   - “准确率 99%”、“响应 1s 内”、“节省 80% 人力”这类没有证据路径的泛化数字。
   - “完全自动上线”、“全自动合并 PR”。RD-Bot 的正确表达是“交付复核后创建 PR、保留人工门控和审计链路”。

## 6. 简历模板

### 校招/实习版

项目：RD-Bot - RAG 驱动的研发交付智能体平台

- 基于 Java 21 + Spring Boot 3.5 搭建研发交付编排平台，支持从需求/工单创建、RAG 证据收集、方案生成、Docker 编码执行、QA 验证到 PR 创建的闭环流程。
- 参与设计多 Agent 阶段状态机，将需求交付拆分为需求评审、方案设计、编码、QA 四个角色，并为每个阶段记录 prompt/result/log 产物、attempt、provider 和失败分类。
- 实现角色化上下文包和经验沉淀机制，按角色裁剪证据、验收标准和风险提示，交付后沉淀需求评审、技术方案、代码变更、QA 报告和交付报告等可复用经验。
- 建设 RAG/Agent 评测脚本，支持 Hit@K、MRR、faithfulness、QA skipped rate 等指标计算，以及 Markdown 报告、失败样本和 A/B diff 输出。

### 社招版

项目：RD-Bot - 企业级研发交付编排智能体平台

- 负责 RD-Bot 研发交付控制面的核心设计与落地，基于 Java 21 + Spring Boot 3.5 + PostgreSQL 构建任务状态机、阶段运行表、角色上下文包、阶段产物和经验沉淀链路，支撑需求/工单到 PR 的可审计交付闭环。
- 主导多 Agent 编排改造，将单 Agent 执行拆分为需求评审、方案设计、编码、QA 四阶段；通过 `AgentStageRun` 持久化 attempt、provider attempts、错误分类和 prompt/result artifact，并在需求评审、QA、交付复核失败时显式阻断后续阶段。
- 抽象 Docker Claude Code、GitHub、Feishu、RocketMQ、Skill 安装等外部能力为端口适配器，配合 PolicyGate、provider fallback、PR 发布复核和 Feishu 告警，降低模型执行和外部副作用的生产风险。
- 建设自建指标 + RAGAS/LLM-as-judge 评测框架，围绕 RAG 检索、需求评审、方案、编码、QA 和交付闭环输出评分报告、失败样本、人工校正列和 A/B diff，为后续 prompt、检索策略和 provider 调优提供量化依据。

### 极简版

RD-Bot 是一个面向研发交付的 RAG + 多 Agent 平台，基于 Java 21 / Spring Boot 3.5 构建从飞书需求、证据检索、四角色 Agent 执行、Docker 编码、QA 验收到 GitHub PR 的闭环。本人负责任务状态机、多 Agent 阶段编排、角色上下文包、执行产物审计、经验沉淀和评测指标体系，重点解决 AI 编码交付中的证据可追溯、失败可恢复、验收可量化和外部副作用可控问题。

## 7. 面试讲法

### 一句话讲清楚

RD-Bot 不是普通 RAG 问答系统，而是把 RAG 证据检索、多 Agent 角色分工、Docker 编码执行、QA 验证、PR 发布和审计沉淀串起来的研发交付控制面。

### 三分钟讲法

先讲业务链路：需求或工单进入系统后，先收集材料和 RAG 证据，再生成计划和策略决策，之后按需求评审、方案设计、编码、QA 四个角色执行。需求评审不过关就阻断，QA 不通过就不创建 PR，只有交付复核通过后控制面才调用 GitHub 发布 PR。

再讲架构：`rag` 负责知识、检索、上下文和任务运行时；`engine` 负责编排；`exec` 负责 Docker/model 执行抽象；`skill` 负责能力治理；`bootstrap` 负责 HTTP、PostgreSQL、Feishu、RocketMQ、GitHub、Docker 等适配。核心层不直接依赖外部 SDK。

最后讲可观测和质量：每个阶段都有独立 attempt、provider、上下文包、prompt/result/log 产物、错误分类和时间线；交付成功后沉淀经验；评测框架用确定性指标和 LLM judge 衡量检索、答案忠诚度、需求阻断、QA 真实命令覆盖和交付闭环质量。

### 常见深挖点

- 为什么不用一个 Agent 全链路做完？
  - 因为需求评审、方案、编码、QA 的风险和上下文不同，单 Agent 容易把上游不确定性传到下游；分阶段后可以在每个角色保存证据、做 schema 校验、失败阻断和人工恢复。
- 如何保证 PR 不是模型越权创建？
  - 编码阶段请求里 `pullRequestRequired=false`，如果阶段返回 PR URL 会被判定为策略违例；物理 PR 只在 QA 和交付复核通过后由控制面调用发布端口。
- RAG 证据怎么避免过度召回？
  - 用角色上下文包承载 evidence、acceptanceCriteria、riskHints、budget 和 omittedEvidenceIds；后续通过 evidence_hit@5、context_precision、context_recall 等指标回归召回质量。
- 失败后怎么恢复？
  - 主任务状态和阶段状态分离；终态失败不直接改旧 stage，而是通过新的 attempt 继续，阶段事件和错误分类保留在数据库里。
- 如何防止评测“看起来通过”？
  - `SKIPPED` 不算通过；QA 必须有真实命令、状态和 log artifact；评测报告要记录样本、taskId、stageRunId、artifactUri、失败原因和 nextAction。

## 8. 投递场景改写

### 投 AI 平台 / Agent 岗

突出：多 Agent 编排、角色上下文、工具/Skill 策略、provider fallback、评测闭环。

标题可写：企业级多 Agent 研发交付平台。

### 投后端 / Java 岗

突出：状态机、PostgreSQL 持久化、RocketMQ、Redisson 限流、线程池隔离、端口适配器、真实 HTTP QA。

标题可写：研发交付自动化后端系统。

### 投工程效率 / DevOps 岗

突出：Docker 执行、GitHub PR、QA 验证、审计报告、Prometheus、Feishu 告警、生产验收台账。

标题可写：AI 辅助研发交付与验证平台。

### 投 RAG / 搜索岗

突出：多路召回、意图树、问题重写、上下文打包、trace、RAGAS/LLM judge 评测。

标题可写：RAG 证据驱动的研发知识检索与交付系统。

## 9. 证据路径

写简历前建议从这些路径取真实说法和数字：

- 项目结构与 RAG 流程：`README.md`
- 依赖和模块：`pom.xml`、各子模块 `pom.xml`
- 研发交付入口：`engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- 主状态枚举：`rag/src/main/java/com/wish/rd/rag/runtime/model/RdTaskStatus.java`
- Agent 角色与阶段状态：`engine/src/main/java/com/wish/rd/engine/agent/model/AgentRole.java`、`AgentStageStatus.java`
- 角色上下文：`rag/src/main/java/com/wish/rd/rag/context/model/RoleContextPackage.java`
- 经验沉淀：`engine/src/main/java/com/wish/rd/engine/agent/WorkflowExperienceStore.java`、`bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresWorkflowExperienceStore.java`
- 评测框架：`scripts/evaluation/*`、`docs/superpowers/plans/2026-07-06-rd-bot-rag-agent-evaluation-design.md`
- 生产验收：`docs/superpowers/plans/2026-07-03-multi-agent-rag-orchestration-production-runbook.md`
- 真实 QA 证据：`docs/qa/*`、`qa-runs/*`，注意 `qa-runs` 是本地产物，不要把密钥或敏感日志写入简历。

## 10. 最终建议

RD-Bot 的简历表达重点不是“我做了一个 RAG 问答”，而是“我把 AI 能力放进研发交付流程，并且让它可控、可验收、可审计”。如果只保留三句话，建议保留：

1. RAG 证据驱动的需求/工单到 PR 闭环。
2. 四角色多 Agent 阶段状态机和角色上下文包。
3. 真实验收、告警、经验沉淀和评测指标闭环。
