# Demo 审计范围与读取验证设计

## 审计身份

`RdTaskDetailPage` 读取任务级 `auditedState`，并将其显式传给工作台和产物面板。`roleDeliverableModel` 把它标注为任务最新审计 head：只有 `sourceStageRunId` 等于所选 stageRunId 的阻断记录才进入该 Attempt 的缺口；不同阶段或缺少来源的记录保留任务级来源说明，不借用当前 Attempt 身份。角色卡直接使用阶段状态，`CANCELLED` 保持已取消语义。

## 产物读取

产物页用所选的 `(taskId, stageRunId)` 读取 QA evidence 和完整 stage result。截断预览不参与结构化摘要；有完整内容时走同一身份的 `/result/content`。请求令牌和 stage identity 在切换时丢弃过期响应，加载失败显示为不可用而非零条结果。

## HTTP 验证

`verify_demo.py` 只发 GET。每一正例同时检查 HTTP 状态、DTO 结构和任务/阶段身份；真实跨任务 stageRunId 只接受 404；缺少可借用业务样本时写入 `unverified`，不把跳过记为通过。脚本的本地 HTTP fixture 覆盖错误状态携带成功形状、错误归属状态和任务发现失败等反例。
