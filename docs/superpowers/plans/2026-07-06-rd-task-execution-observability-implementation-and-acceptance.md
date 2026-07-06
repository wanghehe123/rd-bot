# RD 任务执行透明度 P0/P1/P2 与验收记录

日期：2026-07-06

## 背景

系统执行链路当前存在两个用户可见问题：

- 缺少总耗时、阶段耗时、上下文预算和模型预算统计。
- 多角色执行过程不透明，任务详情页只能看到主状态时间线，缺少当前角色、attempt、provider 和运行中反馈。

同类产品参考：

- GitHub Actions：workflow graph、job execution time、run logs。
- Temporal Web UI：workflow/run 状态、事件历史、可回放调试。
- Argo Workflows：progress、estimated duration、resource duration。
- LangSmith：trace/span、token/cost tracking。
- OpenTelemetry：traces + metrics 分离，分别服务单次路径和聚合指标。

## P0：本轮落地

目标：先让任务执行“看得见、算得出、能轮询”。

交付项：

- 阶段运行进入 `RUNNING` 时写 `startedAtEpochMillis`，进入 `SUCCEEDED` / `FAILED_NEEDS_HUMAN` / `SKIPPED` / `CANCELLED` 时写 `finishedAtEpochMillis`。
- 新增 `GET /admin/rd-tasks/{taskId}/execution-overview`，聚合任务总耗时、角色阶段、上下文预算、模型预算和 Docker 运行态。
- 管理端任务详情页新增“执行概览”，展示当前阶段、总耗时、上下文预算、模型预算、阶段 attempt、provider、阶段耗时和运行中容器。
- 对未终态任务每 3 秒静默刷新详情、时间线和执行概览。

验收标准：

- 单测证明阶段生命周期时间戳写入且不会被 provider metadata 更新覆盖。
- Controller 单测证明执行概览 API 返回 `stageRuns`、`budget`、`runningExecutions`、`progressTotal`，并对不存在任务返回 404。
- 前端 `typecheck` 和 `build` 通过。

## P1：下一步增强

目标：把 provider 尝试、告警和指标结构化。

交付项：

- 从 provider metadata 解析 `durationMillis`、`inputTokens`、`outputTokens`、`estimatedSpendUsd`。
- UI 将 provider attempts 拆成结构化列表，而不是依赖 JSON 字符串。
- 增加卡住/超预算提示：长时间无阶段变化、运行中无心跳、预算超过阈值。
- Prometheus 增加阶段耗时、运行中任务数、上下文预算使用率、模型预算使用率。

验收标准：

- provider metadata 聚合单测覆盖缺字段、字符串金额、数字金额。
- metrics endpoint 能查询到新增指标。
- UI 能区分“运行中有反馈”和“疑似卡住”。

## P2：实时化与预测

目标：从轮询升级为实时事件流，并补上 ETA。

交付项：

- 增加 SSE/WebSocket，推送主状态事件、阶段事件、provider log 摘要和预算更新。
- 基于同类任务历史耗时估算 ETA，展示 P50/P90 区间和数据来源。
- 将执行概览写入交付报告和经验沉淀，便于复盘。

验收标准：

- 浏览器无需轮询即可接收阶段变化。
- ETA 标明置信区间，数据不足时不伪造预计时间。
- 交付报告包含阶段耗时、预算、异常原因和最终状态。

## 本轮代码落点

- `engine/src/main/java/com/wish/rd/engine/agent/model/AgentStageRun.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskExecutionOverviewController.java`
- `frontend/src/services/rdTaskService.ts`
- `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- `engine/src/test/java/com/wish/rd/engine/agent/InMemoryAgentStageRunStoreTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskExecutionOverviewControllerTest.java`

## TDD 失败证据

- `./mvnw -q -pl engine -Dtest=InMemoryAgentStageRunStoreTest#shouldStampLifecycleTimestampsWhenStageStartsAndStops test`
  - 初始失败：`expected: <1783000000050> but was: <0>`。
- `./mvnw -q -pl bootstrap -am -Dtest=RdTaskExecutionOverviewControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 初始失败：`找不到符号 类 RdTaskExecutionOverviewController`。

## 最终验收

| 命令 | 结果 | 说明 |
| --- | --- | --- |
| `./mvnw -q -pl engine -Dtest=InMemoryAgentStageRunStoreTest#shouldStampLifecycleTimestampsWhenStageStartsAndStops test` | PASS | 阶段生命周期时间戳单测通过 |
| `./mvnw -q -pl bootstrap -am -Dtest=RdTaskExecutionOverviewControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS | 执行概览 API、预算、运行态、404 单测通过 |
| `npm run typecheck` | PASS | `tsc --noEmit` 无错误 |
| `npm run build` | PASS | `tsc --noEmit && vite build` 成功，Vite 仅提示 chunk size warning |

本地补充验收记录位于：

- `qa-runs/rd-task-execution-observability/acceptance-2026-07-06.md`
