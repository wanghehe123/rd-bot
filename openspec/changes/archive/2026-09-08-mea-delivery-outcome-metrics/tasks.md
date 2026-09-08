## 1. 失败测试与 fixture

- [x] 1.1 在 `DeliveryObservabilityFixtures` 增加独立 fixture（不改 `catalog()`）：`committedNoCompleteTask`、`mergedWithCompleteTask`、`mergedCommittedOnlyTask`、`waitingUserInputTask`（及 `waitingApprovalTask` 覆盖 runningCount）
- [x] 1.2 在 `DeliveryObservabilityQueryServiceTest` 先加失败用例：COMMITTED+PR 非成功且在途；MERGED+COMPLETED 为成功；MERGED 仅 COMMITTED 为终态非成功非失败；WAITING_USER_INPUT 带 `terminalAt` 为 running。确认 `catalog()` 仍为 4 success / 5 terminal / 0.8，stale-cache 仍 0.8
- [x] 1.3 运行 `./mvnw -pl engine -am -Dtest=DeliveryObservabilityQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认新用例因旧口径失败

## 2. QueryService 口径

- [x] 2.1 实现 `isDeliverySuccess` / `isOutcomeTerminal`：成功 = `COMPLETED` 或 `MERGED`+events 含 `COMPLETED`；`COMMITTED` / `WAITING_USER_INPUT` / `WAITING_APPROVAL` 为在途；不读 PR URL；不把 `paused` 当终态
- [x] 2.2 overview 与 timeseries 共用该分类；`UsageAccumulator` 保持 attempt-id 去重与 `usageAvailable=false` → 成本 NaN
- [x] 2.3 再跑 1.3 命令至全绿

## 3. 规范

- [x] 3.1 本 change 的 proposal / design / spec delta / tasks；`openspec validate mea-delivery-outcome-metrics --strict`
- [x] 3.2 `RULE.md` 3.5.3 追加【强制】观测成功口径与验证命令
- [x] 3.3 不归档 `upgrade-delivery-observability`；不改前端评估 UI

## 4. 可选

- [x] 4.1 若存在且单测安全：`PostgresDeliveryObservabilityRealSmokePreconditionsTest`（不发明未配置的真机 smoke）
