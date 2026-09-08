## 1. WP-0 指标真值与回归基线

- [x] 1.1 在 `docs/qa/delivery-observability/metric-dictionary-v1.md` 建立指标词典，逐项记录名称、单位、分子/分母、窗口、来源表/事件、可用性、标签 allowlist、旧指标兼容状态；用 `rg -n 'rd_bot_|/actuator/prometheus' bootstrap frontend docs` 核对现有消费者。
- [x] 1.2 先在 `PrometheusMetricsControllerTest` 增加失败用例，证明上下文耗时不能再使用 mean repair time，且顶层/子查询失败不能返回看似健康的业务零值。
- [x] 1.3 在 `RequirementDeliveryMetricsTest` 增加长期采样和高项目数失败用例，证明当前 `queueAgeMillis`/`projectClaims` 会违反有界内存合同，并冻结替换后的最大桶/维度要求。
- [x] 1.4 为任务事件、角色阶段、检索步骤和 Provider metadata 准备同一组确定性观测 fixture，覆盖成功、运行中、重试、人工失败、无样本、负耗时和缺失 Token/成本。
- [x] 1.5 记录现有 PostgreSQL 查询的 `EXPLAIN (ANALYZE, BUFFERS)`、fixture 行数和 `/actuator/prometheus` 响应大小，写入 `docs/qa/delivery-observability/baseline.md`；不得把本机结果写成生产容量结论。

## 2. WP-1 领域查询边界与正确统计口径

- [x] 2.1 先新增 `DeliveryObservabilityQueryServiceTest`，覆盖 projectId/窗口归一、终态分母、运行中排除、无样本、数据源失败和过期快照语义。
- [x] 2.2 在 `engine/src/main/java/com/wish/rd/engine/admin/observability/model/` 定义不可变查询、范围、窗口、分位、比率、容量、数据质量和响应模型，并为每个公开 record 补齐 JavaDoc。
- [x] 2.3 在 `engine/src/main/java/com/wish/rd/engine/admin/observability/` 定义 `DeliveryObservabilitySnapshotPort` 与 `DeliveryObservabilityQueryService`，集中实现分母、可用性、freshness 和 P99 最小样本规则。
- [x] 2.4 先新增 mapper/adapter 测试，使用相邻 task status `entered_at` 计算阶段耗时、`stage_run.started_at/finished_at` 计算角色耗时，并断言不会信任 host-finalizer 写入的零 `duration_ms`。
- [x] 2.5 在 `bootstrap` 增加 MyBatis mapper 和 `PostgresDeliveryObservabilitySnapshotAdapter`，从现有 task/stage/retrieval/command 账本执行受窗口和 projectId 约束的聚合；Controller 中不得新增业务 SQL。
- [x] 2.6 增加成功率、QA、PR、人工介入、重试和 UNKNOWN 失败类别的聚合测试，逐项断言分子、分母、sampleCount 与窗口成员规则。
- [x] 2.7 增加采集状态封装：每个 contributor 返回 available、generatedAt、lastSuccess、stale、warning；查询异常保留最后一次允许复用的快照而不伪造零值。
- [x] 2.8 增加查询上限配置与测试：默认窗口 24h，allowlist 为 1h/24h/7d/30d，拒绝未来时间、超大窗口和无界分页；所有配置项在 `application.yaml` 使用环境变量占位和安全默认值。

## 3. WP-2 调度、Runtime 与用量测量

- [x] 3.1 先扩充 `RequirementDeliveryMetricsTest`，覆盖固定 duration buckets、计数器、当前 in-flight/capacity、processStartedAt、样本覆盖和进程重启后的 warm-up 状态。
- [x] 3.2 将 `RequirementDeliveryMetrics` 的全量 queue-age 列表和 per-project 累积 map 替换为固定桶/有界快照；保留公平调度逻辑不变，并运行 `RequirementFairSchedulingSimulationTest` 防止测量代码影响准入结果。
- [x] 3.3 在 `RequirementDeliveryDispatchServiceTest` 先增加 claim、completion、retry、lease lost、queue rejection 和资源饱和观测用例，再把 `metricsSnapshot()` 接入 delivery snapshot adapter；观测失败不得改变 command/task 结果。
- [x] 3.4 先新增 `AgentRuntimeMeasurementParserTest`，覆盖 agent-start 到首文本、Provider response 与首文本区分、无首 Token、乱序/重复事件、负耗时、重试和 finalized usage。
- [x] 3.5 在 `exec` 增加 runtime-neutral 的 `AgentRuntimeMeasurementParser` 与版本化 summary model，只输出受控时间、耗时、runtime/provider/model alias、Token/cache、估算成本和 availability/error category。
- [x] 3.6 为 Pi 和 Claude 兼容事件 fixture 增加用量去重测试：相同 immutable attemptId 只能计一次，不同已执行 fallback attempt 的真实用量必须全部计入。
- [x] 3.7 在 Host 结果回收边界生成并保存有界 `RUNTIME_MEASUREMENT` stage artifact，并以向后兼容字段补充 `providerAttemptsJson`；失败解析只降低 measurement coverage，不得让角色执行失败。
- [x] 3.8 更新 `RdTaskExecutionOverviewControllerTest` 与 `DashboardRuntimeSnapshotAdapter` 测试，使单任务详情、Dashboard 和新聚合查询复用同一 Token/成本/availability 口径，删除重复且相互漂移的解析分支。
- [x] 3.9 若修改任何 Pi bridge/resource，运行 `npm test`（`bootstrap/src/main/resources/executor/pi`）、`DockerPiAgentExecutorTest` 和 Bootstrap 配置测试并重建两张 Pi 镜像；若未修改，验收报告明确记录“不需要重建”的代码证据。

## 4. WP-3 Prometheus v2 与兼容迁移

- [x] 4.1 先扩充 `PrometheusMetricsControllerTest`，覆盖 HELP/TYPE、直方图 bucket 单调性、低基数标签、独立 contributor 局部失败、collector health、last success 和 corrected legacy alias。
- [x] 4.2 增加 Prometheus 标签策略测试，禁止 `taskId`、`stageRunId`、`projectId`、模型自由文本、仓库 URL 和 error message 出现在 label；失败类别和 Provider 只能来自 allowlist。
- [x] 4.3 将 `PrometheusMetricsController` 收敛为 snapshot 调用与文本渲染适配器，接入 delivery query/scheduler contributor，同时保持现有知识投影与 inventory 系列可独立暴露。
- [x] 4.4 发布 `rd_bot_delivery_*` 任务、阶段、队列、容量、runtime、Token/成本直方图/计数/仪表和 `rd_bot_observability_*` 数据质量系列；所有 duration 使用 seconds、Token 使用 integer、成本明确为 estimated CNY。
- [x] 4.5 用实际上下文阶段事件修正 `rd_bot_context_build_latency_seconds`，更新 HELP 文本并把替代指标/弃用期写入 `metric-dictionary-v1.md`；不能修正的旧指标必须停止误导输出并给出迁移说明。
- [x] 4.6 更新 `ObservabilityMetricsEvidenceFileTest`、`ObservabilityMetricsProductionAcceptanceReportTest`、`MultiAgentProductionAcceptanceReportTest` 等 `/actuator/prometheus` 消费者，证明路径兼容且不继续断言错误语义。
- [x] 4.7 增加 scrape 自观测：查询耗时、query timeout、响应大小、invalid/dropped observation 和缓存 fresh/stale；以测试证明采集故障时端点仍暴露自健康而非全零。

## 5. WP-4 管理 API 合同

- [x] 5.1 在开始本工作包前加载仓库要求的 `model-escalation` skill，对新增管理 API、查询性能和兼容策略完成架构复核并把结论记录到本 change；skill 不可用时暂停 API 实现并请求明确评审。
- [x] 5.2 先新增 `DeliveryObservabilityControllerTest`，覆盖 overview、timeseries、failures、tasks 四个路由，全部项目/具体项目、窗口/filter allowlist、分页、400/404 和 section availability。
- [x] 5.3 在 `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/observability/DeliveryObservabilityController.java` 实现只读 HTTP 适配，复用 engine 查询服务并保持稳定错误信封；Controller 不直接访问 DataSource/Mapper。
- [x] 5.4 为 timeseries 实现受控 bucket 粒度和最大点数，为 failures/tasks 实现稳定排序与分页；测试证明非法窗口不会执行底层查询。
- [x] 5.5 在任务下钻响应中只返回任务 ID、标题、项目、状态、角色、耗时和受控类别，提供既有 `/admin/traces/{taskId}` 与 `/admin/rd-tasks/{taskId}` 路径；不得返回 Prompt、原始事件或完整错误文本。
- [x] 5.6 增加 API/Prometheus 一致性测试：同一 fixture 的成功率、terminal count、阶段样本数、queue backlog 和 collector health 在两个视图中口径一致。

## 6. WP-4 前端“交付观测”页面

- [x] 6.1 先新增 `frontend/test/deliveryObservabilityService.test.ts` 和 presentation 测试，覆盖 projectId=all 不下传、窗口/filter 序列化、无样本/不可用/过期格式化、分位样本不足和旧响应隔离。
- [x] 6.2 在 `frontend/src/services/deliveryObservabilityService.ts` 增加严格类型与四个只读请求函数，并在 `viteProxy.test.ts` 先添加 `/admin/observability/delivery/**` 代理合同。
- [x] 6.3 新增 `DeliveryObservabilityPage.tsx`、`/admin/observability` route 和 `AdminLayout` 导航项，页面按健康、吞吐、延迟、阶段、容量、Provider/成本、失败和下钻组织。
- [x] 6.4 复用 `useProjectScope` 并把 window/role/runtime/provider/failureCategory 写入可复现 URL；切换范围时清除上一响应，加载失败保留筛选器可用。
- [x] 6.5 实现 unavailable、no-sample、stale、collector-failed 四种不同视觉状态；所有比率显示分子/分母，所有分位显示 sampleCount 和窗口。
- [x] 6.6 将慢阶段和失败类别链接到分页任务 breakdown，再链接到现有执行追踪/任务详情；聚合页不得复制私有 role evidence 或 raw agent event。
- [x] 6.7 运行全部 Node contract tests、`npm run typecheck` 和 `npm run build`，并更新 `AdminFrontendControllerTest`/SPA route 测试证明页面导航与嵌套 API 不会互相吞路由。
- [x] 6.8 用真实浏览器验证 390px、900px、桌面视口、键盘焦点、无横向页面溢出、console/network 无错误；截图命名含 mobile/tablet/desktop 并记录到 `docs/qa/delivery-observability/frontend-acceptance.md`。

## 7. WP-5 基线、SLO 建议与告警

- [x] 7.1 先新增 `DeliverySloEvaluationServiceTest`，覆盖 disabled/observation-only、最小天数、最小样本、连续持续时间、恢复和 unavailable 数据不触发告警。
- [x] 7.2 在 engine 增加只读 SLO eligibility/recommendation 模型与服务，初始默认 observation-only；阈值来自配置，不得硬编码秒哒或本机样本数字。
- [x] 7.3 为采集失败、最老队列、资源饱和、端到端/角色 P95、lease loss、线程池拒绝、人工介入和成本异常生成结构化 alert candidate，并复用现有告警端口或新增窄 Port。
- [x] 7.4 证明告警评估不会调用 submit/retry/approve、修改 capacity、续租或推进任务状态；增加静态 policy test 和行为测试。
- [x] 7.5 运行配置时长的 observation-only 基线，生成 `docs/qa/delivery-observability/slo-baseline.md`，记录数据量、覆盖、窗口、P50/P95/P99、异常样本、成本估算和建议阈值，不足时明确“不可定 SLO”。
- [x] 7.6 对每条准备启用的告警使用合成信号验证 fire/recover/抑制和 drill-down URL；未获审批前保持通知或阈值 enforcement 关闭。

## 8. WP-6 性能与后续持久化决策

- [x] 8.1 在 realistic fixture 上重复运行 overview/timeseries/failures 查询，记录 p50/p95、rows、buffers、timeout 和 cache 命中；24h overview 的 provisional 本地目标为 p95 < 500 ms，报告实际结果而非修改数据掩盖失败。
- [x] 8.2 仅在 `EXPLAIN (ANALYZE, BUFFERS)` 证明需要时增加精确索引迁移，并补 SQL policy test、真实 PostgreSQL smoke 和回滚说明；不得先建宽索引再找理由。
- [x] 8.3 评估 process-window queue percentile coverage 与 Prometheus retention；若仍无法满足历史分析，单独创建 command-attempt observation OpenSpec change，不得在本 change 临时加通用 metrics JSON 表。
- [x] 8.4 如果 bounded SQL 在有证据的索引后仍不达标，单独提出 rollup/materialized aggregate change，明确重建、保留、幂等和 PostgreSQL truth；本 change 保持 raw-ledger 查询可回滚。

## 9. 全链路验收与归档前检查

- [x] 9.1 运行 `./mvnw -pl engine -am -Dtest='DeliveryObservability*Test,RequirementDeliveryMetricsTest,RequirementFairSchedulingSimulationTest,DeliverySloEvaluationServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test`。
- [x] 9.2 运行 `./mvnw -pl exec -am -Dtest='AgentRuntimeMeasurementParserTest,AgentEventTokenUsageParserTest,DockerPiAgentExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false test`。
- [x] 9.3 运行 `./mvnw -pl bootstrap -am -Dtest='PrometheusMetricsControllerTest,DeliveryObservabilityControllerTest,RdTaskExecutionOverviewControllerTest,RdDashboardControllerTest,ExecutionTraceControllerTest,RequirementDeliveryDispatchServiceTest,ObservabilityMetrics*Test,AdminFrontendControllerTest' -Dsurefire.failIfNoSpecifiedTests=false test`。
- [x] 9.4 增加并运行 opt-in `PostgresDeliveryObservabilityRealSmokeTest`，验证真实 PostgreSQL 的 project/window、阶段分位、backlog、失败分类、采集失败恢复和查询性能；保存数据量与命令，禁止把跳过当通过。
- [x] 9.5 启动本地后端后执行真实 HTTP：抓取 `/actuator/prometheus`，调用四个 `/admin/observability/delivery/**` 路由，验证 200/400、标签基数、无样本、局部数据源失败和任务下钻。
- [x] 9.6 在 `frontend` 运行 `node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build`，然后完成真实浏览器 CURRENT/REGRESSION 验收。
- [x] 9.7 运行 `./mvnw test`，将总测试数、失败/跳过、HTTP/DB/浏览器证据和未覆盖外部系统写入 `docs/qa/delivery-observability/final-acceptance.md`。
- [x] 9.8 运行 `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`，逐条核对 spec scenario 与证据；实施全部完成后再使用 verify change，未完成不得 sync/archive。
