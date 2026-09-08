## Why

生产交付观测把 `COMMITTED` 和 `MERGED` 都算进 SUCCESS 分子。这把「PR 已存在 / 已合入」当成了任务完成。冻结 MEA 方案决策 5 / 第 7 节协调表要求：成功只承认 `COMPLETED`，或 `MERGED` 且 `rd_task_status_events` 含 `COMPLETED`。`COMMITTED`、`WAITING_USER_INPUT`、`WAITING_APPROVAL` 是在途。`upgrade-delivery-observability` 已实现但未归档；本 change 只改分类口径，不归档那个 change，不加新的评估 UI。

## What Changes

- 交付观测 SUCCESS = 当前 `COMPLETED`，或当前 `MERGED` 且 status events 含 `COMPLETED`。
- `COMMITTED` 为在途：有 PR URL 也不算成功、不算终态。
- `MERGED` 仅有 `COMMITTED` 历史、从未 `COMPLETED`：发布已结束（terminal），但不计入成功或失败。
- `WAITING_USER_INPUT` / `WAITING_APPROVAL` 为在途；`terminalAt` 非空仍计入 `runningCount`。
- `paused` 是独立标记，观测分类不把它当成成功、失败或终态。
- 用量继续按 attempt-id 去重、成本为估算 CNY；`usageAvailable=false` 不得变成 0 成本。
- 不改前端评估 UI；不归档 `upgrade-delivery-observability`。

## Capabilities

### New Capabilities

无。`delivery/observability` 已由未归档的 `upgrade-delivery-observability` 引入。

### Modified Capabilities

- `delivery/observability`：修正吞吐与结果指标的成功 / 在途 / 终态分类，不新增页面或 API 形状。

## Impact

- **engine**：`DeliveryObservabilityQueryService` 的 overview / timeseries 分类；既有 `catalog()` fixture 保持 4 success / 5 terminal / 0.8。
- **bootstrap**：查询面走 QueryService，本 change 不改 SQL 适配器的 `terminalAt` 填充（`COMMITTED` 仍可能带 `terminalAt`）。
- **frontend**：不改评估 UI。
- **非目标**：Dashboard `RdDashboardQueryService` 成功集合、Prometheus SQL 回退刮取、归档 `upgrade-delivery-observability`、任务状态机。

## 证据与来源

- 计划（PLAN_OR_DECISION）：`docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md` 决策 5、第 7 节 `upgrade-delivery-observability` 协调表。
- 当前代码（2026-09-06，改前）：`DeliveryObservabilityQueryService` `SUCCESS = {COMPLETED, COMMITTED, MERGED}`；`TERMINAL` 含上述三者。`catalog()` 为 4 个 `COMPLETED` + 1 个 `FAILED_NEEDS_HUMAN`。
- 未归档 change：`openspec/changes/upgrade-delivery-observability/`（能力已实现）。
- 历史索引：`docs/openspec/historical-spec-provenance-audit.md` 在本 worktree 不存在；本 delta 不以历史验收记录代替当前测试。
- 验证：`./mvnw -pl engine -am -Dtest=DeliveryObservabilityQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`；`openspec validate mea-delivery-outcome-metrics --strict`。
