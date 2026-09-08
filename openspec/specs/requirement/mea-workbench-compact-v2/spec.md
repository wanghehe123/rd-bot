# MEA 工作台首屏精简 V2 规范

## Purpose

定义 RD-Bot 任务详情页在保留四角色、产物、证据和精确 Attempt 身份的前提下，突出当前状态与关键信息的紧凑工作台展示。

## Requirements

### Requirement: 顶部只保留一套任务级摘要

任务详情页顶部 SHALL 只渲染一套任务级状态/进展摘要，且 MUST NOT 在任何条件下渲染空的快速操作容器。等待材料、恢复中、阻断提示等任务级提示 SHALL 保留在页头层，不得随旧摘要大卡一起移除；任务 ID、时间、Token、审计计数等次级信息 SHALL 移入次级行或任务详情。现有提交、预算审批、补充信息与重试操作的显示条件与 handler MUST NOT 改变。

#### Scenario: 成功任务没有空操作条

- **WHEN** 任务完成且快速操作条件全部为 false
- **THEN** 页头不渲染“快速操作”空壳容器
- **THEN** 页头仍显示任务真实状态与必要次级信息

#### Scenario: 等待材料的任务

- **WHEN** 任务处于等待材料/恢复中/阻断状态
- **THEN** 页头保留对应任务级提示，不因摘要合并而丢失

#### Scenario: 操作合并后不错绑

- **WHEN** 任务满足提交/预算审批/补充信息任一操作条件
- **THEN** 操作出现在页头操作区且绑定原有 handler
- **THEN** 条件为 false 的操作不渲染

### Requirement: 角色卡提供一句真实产物摘要

四角色卡 SHALL 在状态之外显示一句来自真实数据的产物摘要：需求评审取 feasibility/missingInformation/acceptanceCoverage/budgetEstimate 实际值；方案设计取 affectedFiles/implementationSteps/testPlan 计数或一行摘要；Coding 取 changedFiles、宿主验证结果与任务 PR 入口；QA 分别标注自报检查数量、任务最新审计已完成记录数与所选阶段证据数。数据不可用时角色卡 SHALL 显示执行状态与明确的不可用原因，MUST NOT 用“无当前阻断”冒充产物，MUST NOT 把 `ROLE_NAME result json` 占位或截断预览当作有效摘要，MUST NOT 为四张卡并行请求完整结果。

#### Scenario: QA 角色卡的三类计数

- **WHEN** 所选 QA Attempt 有 6 条自报检查、任务最新审计有 7 条已完成记录、该阶段有 23 条证据
- **THEN** 角色卡分别标注三类数量的来源与身份，不把检查数称为验收标准数

#### Scenario: 占位 resultSummary

- **WHEN** 某角色的 resultSummary 是 `ROLE result json` 占位文本
- **THEN** 角色卡显示“结构化摘要暂不可用”类诚实降级，不显示占位文本

#### Scenario: 缺少阶段记录

- **WHEN** 角色尚无任何阶段记录
- **THEN** 角色卡显示状态为未创建，不编造摘要

### Requirement: 产物页内容顺序与默认折叠

产物与证据页 SHALL 按以下顺序呈现：当前阻断/缺口（仅有问题时）→ 最多三项产物摘要（每项默认两行）→ 一行产物/证据快捷入口 → Coding MEA 当前轮摘要（仅 Coding）→ 最多五项关键证据 → 默认折叠的检查/审计明细 → 默认折叠的执行概述全文与原始结果。执行概述与产物长值 MUST 有真实展开入口且带 `aria-expanded`；完整结果 JSON 的自动读取 MUST NOT 因折叠而回退为仅截断预览；任务 head 的当前阻断 MUST 展示在折叠的成功记录区之外并标注“任务当前阻断，不计入所选 Attempt”。

#### Scenario: QA 完成后的默认视图

- **WHEN** 打开已完成的 QA Attempt 产物页，23 条证据与 6 条检查已加载
- **THEN** 默认视图显示计数摘要与至多五项关键证据，不默认铺开全部检查命令、evidence URI 或执行概述全文
- **THEN** 展开“查看全部”可在当前页看到全部 23 条证据，且不触发新的 audit-content 或任务日志下载

#### Scenario: 长执行概述

- **WHEN** executionSummary 超过两行
- **THEN** 默认截断为两行并有明确展开按钮
- **THEN** 展开后完整文本可见；切换 Attempt 后展开状态重置

#### Scenario: Coding MEA 位置

- **WHEN** 查看有 MEA 决策的 Coding Attempt
- **THEN** MEA 当前轮三职责摘要在关键证据与折叠明细之前展示

#### Scenario: 宿主验证成功

- **WHEN** 宿主验证 SUCCEEDED 且工作台选择 roles 视图
- **THEN** 验证块压成一行概要（通过/静态/耗时/查看步骤）
- **WHEN** 宿主验证失败
- **THEN** 失败原因与失败步骤默认展开显示

### Requirement: 响应式状态顺序与单份状态 DOM

窄视口（390px）下所选 Attempt 的简短状态 SHALL 出现在四个内容页签与正文之前；桌面视口下主区在左、状态在右。状态内容 SHALL 只渲染一份，MUST NOT 出现双份 DOM、重复 ID 或重复控件；键盘焦点顺序 SHALL 与视觉顺序一致。

#### Scenario: 390px 首屏

- **WHEN** 在 390×844 视口打开工作台
- **THEN** 首屏可见所选 Attempt 状态摘要与 Prompt 入口，无水平溢出

#### Scenario: Attempt 切换不串状态

- **WHEN** 在历史 Attempt 之间来回切换并切换四个内容 tab
- **THEN** 状态卡身份跟随所选 Attempt，无跨 Attempt 内容污染

### Requirement: 展示改动不回退既有读取与身份守卫

本展示精简 MUST NOT 改变：`codingStageRunId` 参数、`boundedContract` wire 类型、`id/text/evidenceRefs` 映射、role/Attempt URL 语义、`getCompleteStageResult` 两阶段读取及过期响应保护、审计 head 的“任务最新审计”标注与缺来源提示、任务 paused/stage CANCELLED/等待材料/预算审批的原有语义。证据入口 MUST 真实可用；无可用内容时显示明确不可用状态，MUST NOT 编造链接。

#### Scenario: 视觉验收不得用静态测试替代

- **WHEN** 验证首屏密度与响应式顺序
- **THEN** 以真实 RD-Bot 页面在 1280×720、1440×900、900×900、390×844 的初始滚动位置截图为准
- **THEN** Node 正则/类名断言不作为视觉 PASS 的证据
