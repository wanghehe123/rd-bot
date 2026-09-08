# MEA Demo 范围规范

## Purpose

定义可快速交付的 MEA Demo 的验收边界与候选可追溯性，使未执行的治理能力和苛刻故障矩阵不被误表述为已完成。

## Requirements

### Requirement: Demo 支持范围

Demo SHALL 只对研发人员使用的管理后台（桌面视口）及其支撑读取接口作验收：一条普通真实需求 MUST 走完现有四角色完成/交付流程；四角色产物与证据 MUST 可看；Coding 内 MEA 摘要 MUST 可追踪且阶段身份一致（task/role/Attempt/Prompt/证据不串）；所选 Attempt 状态 MUST 常显、Prompt MUST 可切换；执行失败（含预算耗尽）MUST 明确结束并显示原因，MUST NOT 误报成功。

#### Scenario: 普通需求演示通过

- **WHEN** 在已有测试项目提交一条材料明确的小范围普通需求并等待流程推进
- **THEN** 四角色（需求评审、方案设计、编码、QA）产物与证据可打开
- **THEN** Coding MEA 当前摘要、角色 Prompt 与所选 Attempt 状态一致，切换角色/Attempt、刷新、换任务后不串数据
- **THEN** 任务到达现有真实完成/交付状态，至少一个实际交付产物（PR）可打开

#### Scenario: 失败与空态如实显示

- **WHEN** 出现非法引用（不属于当前 task 的 stage）、缺完整产物或预算耗尽
- **THEN** 页面/接口给出明确不可用或失败原因，不白屏、不把失败显示为完成、不自动挑其他轮次补空白

### Requirement: Demo 移出范围

以下能力 SHALL 移出本次 Demo 验收并 MUST 保持未完成/未验证结论，MUST NOT 在文档或报告中标为已完成：完整 P2 故障注入矩阵（W1 主动 kill JVM 恢复、W2 强制构建失败、W3 QA 协议隔离）、B12–B24 治理功能（稳定契约表、追加材料重规划、契约反查、预算账本、无进展检测、回执恢复、记忆晋升、模型路由、消融）、12 case×3 轮与 P95 阈值、移动端专项、四边界故障注入与恢复率统计。

#### Scenario: 移出项不阻塞发布

- **WHEN** 评估本次 Demo 是否可发布
- **THEN** 仅以上述「Demo 支持范围」的五项判定为门槛
- **THEN** 移出项在验收记录中列为「本期不演示/未验证」，历史实验（07h/07i/07k 等）保持原结论

### Requirement: 候选可追溯

Demo 前后端 SHALL 统一使用 `codex/mea-demo` 分支组合候选：后端基点为 `codex/mea-fresh-executor-episode` 的 `9da44a5cacd4cf3eb38a67b390f65c94f064ef43`，前端实现从 `codex/mea-task-workbench` 工作区逐文件迁入并修复接口差异。所有验证与演示 MUST 指向同一候选，验收记录 MUST 包含来源 SHA、迁入文件清单与构建 hash。

#### Scenario: 来源可追溯

- **WHEN** 查阅验收记录
- **THEN** 能找到后端基点 SHA、前端来源 SHA 与迁入文件清单、组合构建 hash、实际演示 taskId 与产物链接
