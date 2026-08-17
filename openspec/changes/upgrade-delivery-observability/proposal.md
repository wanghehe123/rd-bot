## Why

RD-Bot 已经持久化任务状态、角色 attempt、检索 run、执行事件和 Token/成本，但这些信号尚未形成可信且可运营的交付观测面：现有 Prometheus 端点仍包含错误语义的占位指标，调度器指标只停留在进程内快照，管理台也缺少按时间窗口查看延迟分位、容量、成本和失败趋势的能力。参考秒哒对 QPS、P50/P95、首 Token、生成阶段与容量的分层表达，本 change 将在不改变任务状态机、重试语义和 Provider 路由的前提下，把 RD-Bot 的现有证据整理成可追溯、低基数、可告警的可观测性能力。

## What Changes

- 建立统一的交付指标词典，明确每个指标的信号来源、时间窗口、单位、标签基数、缺失语义和兼容策略。
- 移除或更名语义错误的占位指标；禁止把数据库查询失败静默伪装成健康的零值。
- 暴露任务吞吐、端到端耗时、阶段耗时、排队等待、RAG、Provider 首响应/首 Token、Token/成本、重试、失败和资源容量指标，并提供 P50/P95/P99 等分位视图。
- 将进程内调度观测改为有界、低基数的运行指标，并补充从 PostgreSQL 账本读取的跨重启积压与最老等待时间。
- 新增项目范围的交付可观测性查询 API 与管理台页面，支持时间窗口、角色、runtime/provider 和失败类别下钻，同时保持任务详情与 execution-overview 为单任务权威入口。
- 增加数据新鲜度、采集错误和不可用状态，保证“无样本”“采集失败”和“真实为零”可区分。
- 定义分阶段启用、双写/对照、回滚、SLO 与告警验收计划；本 change 不自动改变调度容量、模型选择、Provider fallback、预算门禁或任务状态。

## Capabilities

### New Capabilities

- `delivery/observability`: RD 需求交付链路的指标合同、聚合查询、Prometheus 暴露、容量/SLO 观测和管理台下钻行为。

### Modified Capabilities

无。当前主 spec 只有与本 change 无关的 `knowledge/openviking-projection-admin`；本 change 不修改其行为合同。

## Impact

- 后端：`RequirementDeliveryEngine`、`RequirementAgentStageOrchestrator`、`RequirementDeliveryDispatchService`、执行 runtime 事件与 Token 解析、RAG retrieval stores、任务/阶段/命令 PostgreSQL 账本。
- 管理接口：保留 `/actuator/prometheus`；新增向后兼容的 `/admin/observability/delivery/**` 只读查询，不改变现有任务写 API。
- 前端：新增交付可观测性页面，并从 Dashboard/执行追踪进入；现有任务详情继续承载单任务证据。
- 数据：前几个阶段优先复用既有账本与事件；只有在基准证明窗口聚合无法满足性能目标时，才由后续独立决策引入聚合表或索引迁移。
- 运行依赖：本 change 不要求立即引入外部 APM、OpenTelemetry Collector 或新的时序数据库；Prometheus/Grafana 接入属于部署侧可选项。
