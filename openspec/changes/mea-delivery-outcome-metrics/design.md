## Context

`upgrade-delivery-observability` 已把聚合收口到 `DeliveryObservabilityQueryService`，但 SUCCESS 集合把发布态与完成态混在一起。冻结 MEA 方案要求观测 SUCCESS 只承认任务完成事实，且完成事实来自 status events，而不是「当前枚举是 MERGED」或「有 PR」。

### Source classification

| Source | Classification | How it is used |
| --- | --- | --- |
| `RULE.md` 3.5.3 | current repository rule | 本 change 在该节追加可验证观测口径。 |
| `docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md` §7 / 决策 5 | PLAN_OR_DECISION | 成功 / 在途 / MERGED 历史规则；阶段 1 明确不改观测代码，由本 delta 落地。 |
| `openspec/changes/upgrade-delivery-observability/` | implemented, not archived | 能力已存在；本 change 只改分类，不归档它。 |
| `DeliveryObservabilityQueryService` + `DeliveryObservabilityQueryServiceTest` | VERIFIED_CURRENT | 改前 SUCCESS 含 COMMITTED/MERGED；`catalog()` 4/5/0.8。 |

### Verified chain

`DeliveryObservabilityController` → `DeliveryObservabilityQueryService.overview/timeseries` → `DeliveryObservabilitySnapshotPort` → `PostgresDeliveryObservabilitySnapshotAdapter`（`terminalAt` 仍按适配器 TERMINAL 集合填充，含 COMMITTED）。

分类必须在 QueryService，不能依赖 `TaskObservation.terminal()`（它只表示 `terminalAt != null`）。

## Goals / Non-Goals

**Goals**

- QueryService 成功分子：`COMPLETED`，或 `MERGED` 且 events 含 `COMPLETED`。
- `COMMITTED` / `WAITING_USER_INPUT` / `WAITING_APPROVAL` 为在途；`runningCount` 在 `terminalAt` 非空时仍计入它们。
- `MERGED` 无 `COMPLETED` 事件：terminal、非 success、非 failure。
- 保持 usage attempt-id 去重与 CNY NaN（`usageAvailable=false` 不得变 0）。
- 既有 `catalog()` 断言保持 4 / 5 / 0.8。

**Non-Goals**

- 不归档 `upgrade-delivery-observability`。
- 不改前端评估 UI、Dashboard 成功集合、Prometheus SQL 回退刮取。
- 不把 `paused` 做成观测状态；分类忽略该标记。
- 不改任务状态机或 finalize 写入者。

## Decisions

### D1. 分类以 status + status events 为准，不以 PR 或 terminalAt

**决定**：`isDeliverySuccess` 只看当前状态与 events 是否出现 `COMPLETED`。`isOutcomeTerminal` 对 `COMMITTED` / `WAITING_*` 恒为 false。`pullRequestUrl` 只用于既有 PR 创建率，不进入成功判定。

**放弃**：把适配器 `terminalAt` 从 COMMITTED 上清掉来「修复」runningCount。生产适配器仍可能给 COMMITTED 填 `terminalAt`；QueryService 必须能在该条件下把任务算成在途。

### D2. MERGED 无 COMPLETED 计入 terminalCount，但不进入 judged 分母

**决定**：成功率分母仍是 success + failure。发布结束但从未完成的 MERGED 增加 `terminalCount`，不增加成功或失败，因此 `successRate` 对纯该样本为 noSample。

**放弃**：把它算失败（合入不是交付失败）；把它算成功（违反决策 5）。

### D3. 本 change 只改 QueryService 口径

**决定**：Dashboard 与 Prometheus SQL fallback 的旧 SUCCESS SQL 列为已知缺口，不在本 delta 扩大范围。管理 API / `rd_bot_delivery_completed_total` 走 QueryService overview，会随本口径更新。

## Risks / Trade-offs

- [Risk] Dashboard 与观测 API 成功数不一致。Mitigation：proposal/tasks 标明缺口；后续独立 change。
- [Risk] Prometheus SQL fallback 在 QueryService 不可用时仍把 COMMITTED/MERGED 当成功。Mitigation：正常路径用 overview scrape；SQL fallback 不在本 change 宣称已对齐。

## Migration Plan

1. 合入 QueryService 分类与测试（本 worktree）。
2. 验证命令见 tasks.md。不发明未跑的真机 smoke。
3. 回滚：恢复 QueryService SUCCESS/TERMINAL 集合即可；无 schema。

## Open Questions

无。
