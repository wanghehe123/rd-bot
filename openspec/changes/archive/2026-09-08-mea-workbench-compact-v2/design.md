# MEA 工作台首屏精简 V2 设计

## 信息层级

任务页头保留唯一的任务级状态和操作区，`TaskHeaderNotice` 承接等待、恢复和阻断提示。`TaskSummaryBand` 与无条件快速操作容器移除，避免同一状态在页头和工作台重复出现。

`workbenchSummaryModel` 是只消费已加载数据的纯函数。它为四角色生成一条可追溯的产物摘要：Coding 连接宿主验证与任务 PR，QA 分别呈现自报检查、任务最新审计和当前阶段证据；占位或截断摘要明确降级，不触发四份完整结果读取。

## 产物与状态

`RoleDeliverablesPanel` 先显示问题、简短产物和可用入口，再显示 MEA、关键证据与默认折叠的检查、长文和原始结果。完整结果仍使用原有两阶段读取与 identity guard。`HostVerificationCard` 只压缩成功态；失败原因保持默认可见。

工作台只渲染一份所选 Attempt 状态 DOM。桌面以 grid 放在右列，窄屏在内容页签前；URL、stage identity 和请求令牌继续作为切换时的身份边界。

## 验证与非目标

验证以真实任务的四个视口、角色/Attempt/tab 切换、证据展开和公网部署后的资源哈希为准。此变更不调整四角色调度、Manager/Host 审计职责、协议字段或数据库结构。
