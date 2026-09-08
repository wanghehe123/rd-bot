## Why

前端任务工作台需要在 Coding 内展示 MEA 因果邻域与完整角色结果，但当前管理端只有四角色 overview 的 `resultPreview` 与审计页，没有按 task/Coding stage 聚合的只读快照，也没有从已落盘 finalization 读取完整 RESULT_JSON 的入口。本 change 冻结并实现只读合同，不改调度与完成权。

## What Changes

- 新增 `GET /admin/rd-tasks/{taskId}/coding-mea`：有界 Coding 因果快照（commands/decisions/rounds/Host/Audit 引用 + 分页），默认不含 Prompt/结果/事件正文。
- 新增 `GET /admin/rd-tasks/{taskId}/manager-decisions/{decisionHash}`：按需返回完整 bounded contract。
- 新增 `GET /admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/result` 与 `/result/content`：经精确 identity 从 finalization（或降级 artifact preview）读取角色结果。
- 提供跨端 fixture `fixtures/coding-mea-v1.json`；无新业务表、无调度写路径。

## Capabilities

### New Capabilities

- `requirement/coding-mea-read-model`：Coding MEA 只读快照、Manager 合同全文按需读取、stage 完整结果读取与归属/截断语义。

### Modified Capabilities

- （无）不修改 `manager-decision-command` / `audit-only-writeback` 的写路径要求。

## Impact

- engine：`CodingMeaReadPort` / `CodingMeaQueryEngine` / `StageResultReadPort` / `StageResultQueryEngine`
- bootstrap：Postgres 只读适配器、Controller、mapper 精确读取扩展；真实 PG + HTTP 测试
- frontend（独立分支）：消费 fixture 与真实响应；本 change 不改前端布局
- 非目标：新建调度器、回填历史未持久化完整结果、重建 Pi 镜像

## 证据与来源

| 来源 | 分级 |
| --- | --- |
| 实施计划 §4 / B04–B06 | PLAN_OR_DECISION |
| 现有 Store/Controller 锚点（plan §3） | 实现声明，实施时再核验 |
| W1e / 后续 W1g 真机 identity | 联调证据（B03 后）：W1e `7502196308401328128`、W1g `7502261872498970624`、W2B `7502311153071165440`、W3J `7502300444828504064` |
