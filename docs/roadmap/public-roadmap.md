# RD-Bot 未来方向：AI-native Software Delivery Harness

## 一句话定位

RD-Bot 是一个面向研发流程的 AI 自动化控制平台。它将工单或研发任务转化为可治理的修复流水线，通过任务级 RAG 构建上下文，在隔离执行环境中运行修复 agent，自动验证结果并创建可审查 PR，同时记录完整的审计证据。

它不应该被包装成“一个会修 bug 的机器人”，而应该被包装成：

**AI-native Software Delivery Harness / 研发流程自动化控制面。**

## 核心判断

RD-Bot 的核心价值不是调用大模型，而是把研发任务变成可触发、可编排、可执行、可验证、可审计、可治理的自动化工作流。

模型只是可替换的 worker。真正体现工程能力的是模型外层的 harness：

- workflow orchestration：把任务拆成可恢复的阶段。
- task-scoped RAG：为每个任务生成可复现的上下文包。
- connector boundaries：Feishu、RocketMQ、Docker、GitHub、PostgreSQL、模型 SDK 都通过端口接入。
- sandboxed execution：修复执行在受控环境中完成。
- validation evidence：每次输出都有测试、日志、diff、prompt snapshot 等证据。
- PR-based delivery：agent 不直接合并，只提交可审查变更。
- policy gates：高风险动作必须经过策略和人工审批。
- audit and recovery：失败、重试、人工接管和死信都能追踪。

## Harness 概念映射

| RD-Bot 能力 | Harness 视角 | 目标 |
| --- | --- | --- |
| `bootstrap` | Adapter/API plane | 暴露 REST、配置、管理界面和外部适配器。 |
| `engine` | Control plane / workflow orchestrator | 编排从任务到修复交付的完整链路。 |
| `rag` | Knowledge/context plane | 生成任务级 ContextPackage、检索证据和 prompt plan。 |
| `exec` | Delegate/execution plane | 在 Docker 等隔离环境中执行修复、验证和产物记录。 |
| `skill` | Reusable workflow steps | 沉淀分析、验证、风险评分、报告和审查能力。 |
| `frontend` | Developer portal / operations UI | 展示任务状态、证据、策略结果、指标和人工恢复动作。 |

## 工程路线

现有 P0-P3 仍然是工程实现主线：

| 阶段 | 方向 | 说明 |
| --- | --- | --- |
| P0 | Knowledge productionization | PostgreSQL 持久化、知识库、文档、chunk、摄取任务、飞书导入、定时刷新、`repair_records` 和 `repair_record_artifacts`。 |
| P1 | Feishu ticket + RocketMQ scheduling | 工单系统作为端口，先实现 Feishu；队列使用 RocketMQ，同时保留本地/测试 adapter。 |
| P2 | Docker Claude Code + GitHub PR | 通过 Docker 执行修复 agent，验证后创建 GitHub PR，保留 GitHub App/PAT 可替换边界。 |
| P3 | Production governance | 审计、幂等、死信、人工恢复、告警、allowlist、secret 边界、日志脱敏和运营视图。 |

这条路线解决“系统真实可用”的问题，但作品集还需要另一条演示路线，优先证明完整闭环。

## Demo 路线

### Demo v0：单机黄金路径

目标：不依赖真实 Feishu、RocketMQ、GitHub App，也能演示完整研发任务自动化流程。

流程：

`Manual Repair Task -> Local Demo Repo -> ContextPackage -> Repair Plan -> Docker dry-run -> Validation -> Patch Artifact -> Admin UI`

交付物：

- 一个可启动的 Spring Boot 后端。
- 一个最小 admin UI。
- 一个 demo repo 和 sample bug。
- 一条完整 `repair_record`。
- 可下载的 log、diff、prompt snapshot、validation result。

这是最优先的作品集闭环。没有这个闭环，路线图再完整也不够有说服力。

### Demo v1：PR-based delivery

目标：证明 RD-Bot 理解研发流程不是“自动改主干”，而是通过 PR 进入人类 review。

流程：

`Repair Task -> Docker Execution -> Tests Passed -> Branch -> GitHub PR -> PR Evidence -> repair_record PR URL`

关键原则：

- agent 不直接合并。
- agent 只提交可审查证据。
- PR body 必须包含上下文、风险、测试证据和人工检查点。

### Demo v2：Ticket-driven workflow

目标：接入 Feishu ticket 或 mock ticket adapter，让任务来源从手动创建升级为工单驱动。

流程：

`Feishu Ticket or Mock Ticket -> TicketAdapter -> RocketMQ or LocalQueue -> RepairWorkflowEngine -> RAG -> Exec -> PR -> Ticket write-back`

建议先提供 `MockTicketAdapter`，让演示不依赖私有 Feishu 凭证；真实 Feishu 字段、状态枚举和写回格式必须由用户提供，不能猜。

### Demo v3：Policy + approval

目标：让项目从“自动化工具”升级成“企业可用平台”。

最小策略：

- low risk：自动创建 PR。
- medium risk：创建 PR，但标记需要 review。
- high risk：停在 `WAITING_APPROVAL`。
- secret detected：阻断并进入 `FAILED_NEEDS_HUMAN`。
- validation failed：不创建 PR，只保存 artifact。

前端需要展示：

- policy decision。
- risk level。
- reason。
- required manual action。

### Demo v4：Developer Portal / SEI dashboard

目标：把 RD-Bot 包装成全栈研发效率平台，而不是后端脚本。

页面建议：

- Repair Tasks：task list、status、ticket source、risk level、current stage。
- Repair Detail：context package、plan、execution logs、validation result、generated diff、PR link、policy decisions。
- Knowledge Base：documents、chunks、ingestion tasks、refresh history。
- Operations：retry、cancel、approve、mark as needs human、dead-letter recovery。
- Metrics：success rate、validation pass rate、average repair time、human intervention rate、top failure reasons。

## 优先补齐的 5 个能力

1. `RepairWorkflowEngine` 的真实闭环
   先让一个 task 从创建、上下文构建、执行、验证、产物保存到完成状态完整跑通，不要先追求多平台集成。

2. `repair_records` + `repair_record_artifacts` 证据中心
   每次 agent 做了什么、为什么做、输入是什么、输出是什么、验证结果是什么，都应该能查。

3. `ContextPackage` 标准化
   RAG 不只返回文本，而应返回结构化工程上下文：

   ```json
   {
     "taskId": "xxx",
     "knowledgeBaseVersion": "v1",
     "problemSummary": "...",
     "relatedFiles": [],
     "retrievedChunks": [],
     "constraints": [],
     "suspectedRootCauses": [],
     "suggestedValidationCommands": [],
     "traceId": "xxx"
   }
   ```

4. `ExecutionDelegate` 抽象
   不要把 Docker Claude Code 写死在 orchestration 中。控制面编排任务，执行面负责运行 worker：

   ```java
   public interface RepairExecutor {
       RepairExecutionResult execute(RepairExecutionRequest request);
   }
   ```

   初始实现可以包括 `LocalDryRunRepairExecutor`、`DockerClaudeCodeRepairExecutor` 和 `MockRepairExecutor`。

5. `PolicyGate` + `RiskScorer`
   先做简单规则也有价值：

   - 修改文件数量超过 10 个：medium risk。
   - 涉及 auth/security/payment/config：high risk。
   - 测试失败：block。
   - 检测到 secret：block。
   - 只有文档改动：low risk。

## 验收标准

未来方向不是只写文档，最终应能用以下方式证明：

- 本地可运行一条 Demo v0 黄金路径。
- 每条任务都有 `repair_record`。
- 大产物进入 `repair_record_artifacts` 或对象存储引用，不塞进主记录。
- 每个关键阶段都有结构化 trace。
- agent 产物必须有 validation evidence。
- 高风险动作必须经过 `PolicyGate`。
- PR body 能说明上下文、改动、风险和测试证据。
- 没有 Feishu/GitHub/RocketMQ 凭证时，mock/local adapter 仍能跑通同一端口合同。

## 后续迭代总结

RD-Bot 的后续方向是：从“RAG + Agent 修复 demo”迭代成“研发任务自动化控制平台”。先打通可演示黄金路径，再补持久化、执行代理、PR 交付、策略治理、前端运维视图，最后沉淀成 IDP/SEI 风格的研发效率平台。
