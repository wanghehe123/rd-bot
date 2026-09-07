# MEA Demo 快速交付范围

## Why

用户 2026-09-08 明确要求：降低苛刻验证与完整性功能的要求，快速交付一个能稳定演示普通需求流程的研发管理后台（B端）。完整 P2 故障注入矩阵、B12–B24 治理功能、12×3/P95 消融、移动端专项不再作为 Demo 发布前置。本 change 是 Demo 范围合同：记录支持项与移出项，避免后续把「未执行」误标为「已完成」，也不回滚任何历史实验结论。

方案全文：`docs/superpowers/plans/2026-09-08-mea-demo-delivery-plan.md`。

## What Changes

- 建立 `codex/mea-demo` 组合候选分支：后端基点 `codex/mea-fresh-executor-episode` 的 `9da44a5cacd4cf3eb38a67b390f65c94f064ef43`（已含 Coding MEA / Manager 全文 / stage result 读取 API 与 B09–B11 代码）；前端工作台实现从 `codex/mea-task-workbench` 工作区（HEAD `3fcd7db6`，未提交 dirty 实现）逐文件迁入。
- 前端修复四个文件的接口/关联/证据链接问题：`codingStageRunId` 参数名、Manager 全文 `boundedContract` wire type、审计记录 `id/text/evidenceRefs` 适配、QA 证据 URL 带 taskId、移除无证据关联回退。
- 后端只补普通路径失败底线：预算失败（BUDGET_EXCEEDED）归类透传与 Host 窄断言；`RequirementReviewProtocol` 的 false-ASK（APPROVED + advisory missingInformation 误判 ASK）方法级修复。
- P2 完整 kill 矩阵（W1/W2/W3）、B12–B24、12×3/P95、移动端专项、故障注入明确移出本次 Demo 范围，保持「未完成」结论不变。

## Capabilities

### New Capabilities

- `requirement/mea-demo-scope`：Demo 验收范围合同——支持项、移出项与五项发布判定。

### Modified Capabilities

- 无。`requirement/fresh-executor-episode`、`requirement/manager-decision-command`、`requirement/audit-only-writeback` 的既有行为合同不变；本 change 只收窄验收范围，不改状态机。

## Impact

- **frontend**：迁入工作台源码/测试（7 个 src 文件 + 8 个测试文件 + 9 个已有文件改动）；修复接口差异；静态 bundle 由组合候选统一 `npm run build` 重新生成。
- **exec**：`DockerPiAgentExecutor.validateRoleProtocolResult` 透传 bridge 的 `failureCategory`（仅 Java 归类，不改容器资源，不重建 Pi 镜像）。
- **engine**：`RequirementReviewProtocol.disposition` 的 false-ASK 修复（仅该方法 + 对应测试）。
- **不带入**：`mea-fresh-executor-episode` 工作区中 command reopen / deadline 刷新 / 立即重调度 / Pi lifecycle 自动开新 Attempt 等 P2 恢复实验补丁（8 个 tracked dirty 文件整体保留在原工作区）。
