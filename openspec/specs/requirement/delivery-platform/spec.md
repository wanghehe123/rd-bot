# Requirement Delivery Platform Specification

## Purpose

把 RD-Bot 收敛后的需求交付平台写成可验证契约：只有需求任务可以创建和推进，只有 Pi Agent 可以执行角色阶段，已下线的工单、聊天、评测和仅模型 HTTP 路径不得再被当成活功能。

## Requirements

### Requirement: 任务写入面只接受需求交付

系统 SHALL 只通过需求交付入口创建可执行任务。管理台新建任务 MUST 调用 `POST /admin/rd-tasks/requirements`。系统 MUST NOT 再提供 BugFix 创建写入面（包括 `POST /admin/rd-tasks` 的工单创建）。提交推进 MUST 拒绝非 `REQUIREMENT` 任务。

#### Scenario: 管理台创建需求任务

- **WHEN** 操作者在任务管理中保存一个带项目、基准分支、需求材料和预期结果的新任务
- **THEN** 系统创建 `REQUIREMENT` 任务，初始状态为 `CREATED`，并可进入需求交付启动链路

#### Scenario: 提交非需求任务

- **WHEN** 客户端对 `taskType` 不是 `REQUIREMENT` 的已有任务调用 `POST /admin/rd-tasks/{taskId}/submit`
- **THEN** 系统拒绝提交，并说明只有需求任务可以提交

#### Scenario: 历史 BugFix 草稿不得伪装成需求

- **WHEN** 浏览器本地草稿的 `taskKind` 为 `BUG_FIX`
- **THEN** 系统丢弃该草稿，而不是把它恢复成需求创建表单

### Requirement: 历史 BugFix 任务只读可查

系统 SHALL 保留对已有 `BUG_FIX` 行的读取、筛选和展示，以便操作者查看历史记录。`BUG_FIX` 与 `QNA` MUST NOT 再获得新的执行链路。`RdTaskType` 非法值 MUST 失败关闭；空值 MUST 落为 `REQUIREMENT`。

#### Scenario: 按历史类型筛选任务列表

- **WHEN** 操作者在任务列表将类型筛选项设为 `BUG_FIX`
- **THEN** 系统返回匹配的历史任务，且不提供从该筛选结果新建 BugFix 任务的入口

#### Scenario: 非法任务类型失败关闭

- **WHEN** 外部输入的任务类型字符串不是 `BUG_FIX`、`REQUIREMENT` 或 `QNA`
- **THEN** 系统抛出未知类型错误，而不是静默改成 `BUG_FIX`

#### Scenario: 空任务类型落为需求

- **WHEN** 任务类型输入为空且调用方未指定其它默认值
- **THEN** 系统将其视为 `REQUIREMENT`

### Requirement: 飞书 IM 只摄入需求，其余忽略

系统 SHALL 在飞书 IM 入口只创建需求任务。非文本、未 @ 机器人、空文本、以及解析后不是需求的消息 MUST 以 `ignored=true` 接受并丢弃，MUST NOT 再解析成工单或 BugFix 任务。飞书 IM 开关 MUST 独立于已删除的工单 provider 配置。

#### Scenario: 需求消息进入交付链路

- **WHEN** 飞书 IM 收到可解析的需求文本且需求交付可用
- **THEN** 系统创建 `REQUIREMENT` 任务并返回 `taskType=REQUIREMENT`

#### Scenario: 非需求消息被忽略

- **WHEN** 飞书 IM 收到文本，但解析结果不是需求
- **THEN** 系统返回 `accepted=true` 且 `ignored=true`，原因是 `not a requirement message`，并且不创建任务

### Requirement: 角色执行只走 Pi Agent 容器

系统 SHALL 让 `REQUIREMENT_REVIEWER`、`SOLUTION_ARCHITECT`、`CODING_AGENT` 和 `QA_AGENT` 走 Agent 容器。项目未保存执行策略时，兼容默认 MUST 为 `PI`。模型访问 MUST 只经 Pi credential-relay，MUST NOT 再提供宿主直连的 OpenAI Chat Completions 执行器，也 MUST NOT 再构建或启动 Claude Code 执行镜像。

#### Scenario: 未配置策略的项目落到 Pi

- **WHEN** 需求任务需要执行某个角色，且项目没有已保存的 Agent 执行策略
- **THEN** 系统选择 `PI` 运行时，而不是 `CLAUDE_CODE` 或仅模型 HTTP

#### Scenario: 历史 Claude 或仅模型快照失败关闭

- **WHEN** 冻结的执行快照声明 `CLAUDE_CODE` 或 `MODEL_ONLY`，且路由器没有对应执行器
- **THEN** 系统拒绝执行并报告该运行时没有注册执行器，而不是回退到另一种运行时

### Requirement: 已下线控制面不得重新作为活功能出现

系统 SHALL 不再把 RAG 会话聊天、样例问题、消息反馈、Coding Benchmark / 评测控制台、工单修复队列和 `repair_records` 写入当作可操作功能。管理台当前导航 MUST 指向需求交付工作台，而不是评测页或修 Bug 创建页。项目任务模板编辑 MUST 只维护需求模板。

#### Scenario: 管理台创建入口是需求

- **WHEN** 操作者打开新建任务对话框
- **THEN** 标题和表单按需求交付收集材料，而不是按 Bug 复现步骤收集工单字段

#### Scenario: 项目模板只编辑需求

- **WHEN** 操作者打开某项目的任务模板
- **THEN** 系统加载并保存 `REQUIREMENT` 模板，而不是默认打开修 Bug 模板

#### Scenario: 评测控制台不再作为产品入口

- **WHEN** 操作者使用管理台主导航
- **THEN** 系统不展示评测或 Coding Benchmark 作为可进入的功能页
