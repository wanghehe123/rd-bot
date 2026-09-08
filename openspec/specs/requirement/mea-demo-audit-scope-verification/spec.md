# MEA Demo 审计范围与读取验证规范

## Purpose

定义工作台对任务最新审计、所选 Attempt、完整角色产物和 Demo HTTP 验收的真实身份边界，防止历史记录、截断结果或错误响应被误用为当前证明。

## Requirements

### Requirement: 任务最新审计与所选 Attempt 区分

工作台 SHALL 明确标注任务当前审计 head 为“任务最新审计”，并保留和显示记录的真实来源阶段。它 MUST NOT 被表示为所选历史 Attempt 的审计快照；非本阶段或无来源的任务记录 MUST NOT 混入该 Attempt 的问题摘要。

#### Scenario: 历史 QA 查看任务最新审计

- **WHEN** 所选阶段是 qa-1，任务 head 包含 sourceStageRunId 为 qa-2 的记录
- **THEN** 任务最新审计区显示真实来源 qa-2，并明确这不是所选 Attempt 的历史审计
- **THEN** qa-2 的阻断不进入 qa-1 的问题摘要

#### Scenario: 记录缺少阶段来源

- **WHEN** head 记录没有 sourceStageRunId
- **THEN** 页面显示来源未记录，不借用当前选中 stageRunId 填充

### Requirement: 角色卡片保留阶段取消状态

角色卡片 SHALL 将已有阶段的 CANCELLED 状态显示为已取消，图标的可访问名称 SHALL 与阶段状态一致，不得回落为待开始。本要求不改变没有阶段记录的角色状态。

#### Scenario: 实际 Coding 或 QA 阶段已取消

- **WHEN** 角色最新阶段记录的 status 为 CANCELLED
- **THEN** 角色卡片图标标注已取消，与 Attempt 选择框和详情状态一致

### Requirement: Demo 读取验收必须检查真实 HTTP 合同

验收脚本 SHALL 对正例同时检查 HTTP 200、返回结构与请求身份；跨任务引用与不可用全文下载 SHALL 检查 HTTP 404。HTTP 错误 MUST NOT 因正文看似正常而判成功；无法验证的场景 SHALL 显式记录原因及未验证数量。

#### Scenario: 错误状态携带成功形状正文

- **WHEN** stage result 请求返回 HTTP 404，正文包含 source 或其他正常字段
- **THEN** 正例验收失败并以非零退出码结束

#### Scenario: 非归属错误不能代替跨任务拒绝

- **WHEN** 跨任务读取返回 400、401 或 403
- **THEN** 不得作为通过的 404 归属检查

#### Scenario: 缺少第二个业务任务

- **WHEN** 列表读取成功，但没有可用于跨任务校验的第二个 REQUIREMENT 任务
- **THEN** 对应项明确 SKIP 并进入未验证清单，不声称已经覆盖

#### Scenario: 列表接口失败

- **WHEN** 用于寻找测试对象的列表接口返回错误
- **THEN** 报告接口失败，不能描述成成功读取到空列表

#### Scenario: 历史目标不在列表当前页

- **WHEN** 列表返回合法 records、page、pageSize、total、pages，目标任务不在当前页
- **THEN** 列表结构校验通过，目标任务身份由任务详情接口独立验证

#### Scenario: 继续 QA 的 Manager 决策没有有界 Coding 合同

- **WHEN** Manager 决策为 EXECUTE 到 QA_AGENT，或其他不执行有界 Coding 的合法路由
- **THEN** 全文仍须 HTTP 200 且 taskId、decisionHash 和 revision 身份匹配，空 boundedContract 不构成失败
- **THEN** 若无适用的有界 Coding 决策，将非空 Coding 合同检查单独记为 SKIP/unverified

#### Scenario: 存在有界 Coding 决策

- **WHEN** 决策列表存在 EXECUTE 到 CODING_AGENT 且 targetRecordIds 非空的记录
- **THEN** 选择其中一条读取全文并验证精确身份、revision 及非空 boundedContract，不以首条 QA 决策代替

### Requirement: 默认产物页读取真实证据与完整结构化结果

所选 Attempt 的默认产物与证据页 SHALL 按需读取该任务的 QA evidence，并继续以真实 stageRunId 过滤。对于有结果的所选阶段，结构化摘要 SHALL 使用可解析的完整结果；截断预览 MUST NOT 冒充完整 JSON。读取失败 SHALL 明确显示，任务或阶段切换后的过期响应 MUST NOT 写入新 Attempt。

#### Scenario: 初次进入已完成 QA 的默认页

- **WHEN** QA evidence 接口有属于当前 stageRunId 的 23 条记录
- **THEN** 默认产物页完成读取后显示这 23 条证据，不要求先进入 Prompt 或任务审计

#### Scenario: 结果预览在 JSON 字符串中间截断

- **WHEN** `/result` 返回 available=true、truncated=true 且存在完整结果内容
- **THEN** 使用同一 taskId/stageRunId 的 `/result/content` 读取完整 JSON，再计算结构化摘要与自报检查

#### Scenario: 全文加载期间切换 Attempt

- **WHEN** 元数据或全文请求尚未完成时选中另一个任务或阶段
- **THEN** 原请求响应不得覆盖新选择的结果、加载状态或错误信息

#### Scenario: 证据或全文请求失败

- **WHEN** 所选 Attempt 的实际读取请求失败
- **THEN** 页面明确显示不可读原因或重试入口，不将未成功读取表述为已确认的零条产物
