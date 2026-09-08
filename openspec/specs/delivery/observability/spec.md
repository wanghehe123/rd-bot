# 交付可观测性规范

## Purpose

定义需求交付的指标、结果口径、数据质量、延迟、容量与下钻边界，使发布状态、任务状态和历史完成事实不会被混同，也不会把缺失数据伪装成健康零值。

## Requirements

### Requirement: 指标必须具有明确语义与来源

系统 SHALL 为每个交付指标定义稳定名称、单位、统计对象、时间窗口、样本数、数据来源、可用性和生成时间，并 SHALL NOT 使用无关数据代替尚未采集的指标。

#### Scenario: 指标具有完整元数据

- **WHEN** 操作者查询一个交付观测窗口
- **THEN** 响应说明窗口起止时间、生成时间、样本数和各指标的数据来源与单位

#### Scenario: 不以无关耗时代替缺失耗时

- **WHEN** 某阶段耗时没有可验证的开始与结束信号
- **THEN** 系统将该指标标为不可用且不把任务总耗时或其它阶段耗时填入该指标

### Requirement: 零值、无样本、采集失败和过期数据必须可区分

系统 SHALL 在管理 API 和管理台中区分真实零值、窗口内无样本、数据源采集失败和缓存快照过期；Prometheus 暴露面 SHALL 提供按低基数数据源分类的采集成功与数据新鲜度信号。

#### Scenario: 数据库查询失败

- **WHEN** 交付账本查询失败而 Prometheus 端点仍可响应
- **THEN** 端点暴露对应数据源采集失败且不把受影响的业务指标伪装成零

#### Scenario: 窗口内没有终态任务

- **WHEN** 所选窗口内没有可用于完成耗时统计的终态任务
- **THEN** API 返回无样本和 sampleCount=0，而不是返回耗时为零的健康结论

#### Scenario: 使用最后一次成功快照

- **WHEN** 当前采集失败但仍有允许复用的最近成功快照
- **THEN** 系统明确返回该快照的生成时间、过期状态和采集失败状态

### Requirement: 系统必须提供端到端和阶段延迟分位

系统 SHALL 按选定时间窗口提供完成任务的端到端耗时，以及排队、上下文构建、计划/策略、角色执行、RAG、QA、发布等可验证阶段的 P50、P95、P99、平均值和样本数。

#### Scenario: 完成任务进入延迟分位

- **WHEN** 一个任务在窗口内进入交付终态且具有合法开始和结束时间
- **THEN** 该任务进入相应端到端与阶段耗时样本，并按真实 attempt 分别统计阶段执行

#### Scenario: 运行中任务不污染完成耗时

- **WHEN** 一个任务或阶段仍在运行
- **THEN** 它不进入完成耗时分位，但进入运行数量、当前年龄和疑似卡住观测

#### Scenario: 非法时间观测

- **WHEN** 结束时间早于开始时间或所需时间戳缺失
- **THEN** 系统排除该样本并增加命名的无效观测计数

### Requirement: 系统必须提供吞吐与结果指标

系统 SHALL 按窗口提供任务进入量、终态完成量、成功率、QA 通过率、PR 创建率、人工介入率、重试率和失败类别分布，并 SHALL 公开每个比率的分子、分母与样本数。

交付成功率分子 MUST 只包含当前状态为 `COMPLETED` 的任务，以及当前状态为 `MERGED` 且 `statusEvents` 中存在 `COMPLETED` 的任务。系统 MUST NOT 因存在 pull request URL、当前状态为 `COMMITTED`、或当前状态为 `MERGED` 但历史只有 `COMMITTED` 而计入成功。成功/失败分类 MUST 使用 status events 中的历史完成事实，MUST NOT 仅凭当前枚举猜测 `MERGED`，也 MUST NOT 把 `paused` 当作成功、失败或终态。

`COMMITTED`、`WAITING_USER_INPUT` 与 `WAITING_APPROVAL` MUST 视为在途：不是成功、不是失败、不是观测终态。`runningCount` MUST 计入这些状态，即使观测行的 `terminalAt` 非空。`WAITING_USER_INPUT` MUST NOT 进入成功、失败或终态计数。

当前 `MERGED` 且 status events 不含 `COMPLETED` 的任务 MUST 计入终态（发布已结束），MUST NOT 计入成功或失败，因此 MUST NOT 进入成功率 judged 分母。

#### Scenario: 只用可判定终态计算交付成功率

- **WHEN** 窗口中同时存在运行中、成功和失败任务
- **THEN** 交付成功率只以可判定的成功与失败终态为分母，运行中任务单独展示

#### Scenario: COMMITTED 有 PR 但从未 COMPLETED

- **WHEN** 任务当前状态为 `COMMITTED`、带有 pull request URL、status events 不含 `COMPLETED`，且 `terminalAt` 非空
- **THEN** 该任务不计入成功分子，不计入终态，计入 `runningCount`

#### Scenario: MERGED 且历史含 COMPLETED

- **WHEN** 任务当前状态为 `MERGED` 且 status events 包含 `COMPLETED`
- **THEN** 该任务计入成功分子与终态

#### Scenario: MERGED 仅有 COMMITTED 历史

- **WHEN** 任务当前状态为 `MERGED` 且 status events 仅有 `COMMITTED` 而无 `COMPLETED`
- **THEN** 该任务计入终态，但不计入成功或失败

#### Scenario: WAITING_USER_INPUT 带 terminalAt

- **WHEN** 任务当前状态为 `WAITING_USER_INPUT` 且 `terminalAt` 非空
- **THEN** 该任务不计入成功、失败或终态，计入 `runningCount`

### Requirement: 系统必须提供队列和容量指标

系统 SHALL 提供持久化待处理命令数、最老待处理年龄、运行中数量、lease 丢失、线程池拒绝、资源容量和利用率；跨重启积压 SHALL 来源于持久化账本，进程内运行指标 SHALL 使用有界内存。

#### Scenario: 进程重启后仍能看见积压

- **WHEN** 后端重启且数据库仍有 PENDING、可重试或 lease 过期命令
- **THEN** 管理 API 和 Prometheus 仍显示这些命令及最老等待时间

#### Scenario: 运行采样长期持续

- **WHEN** 调度器连续运行并产生大量命令和项目
- **THEN** 可观测性采样不会按任务数或项目数无界保留内存

#### Scenario: 资源容量不足

- **WHEN** Docker、浏览器 QA、Provider 或通用执行资源达到配置容量
- **THEN** 系统显示当前使用量、容量和饱和状态，但不自动改变既有准入或调度策略

### Requirement: Provider 与模型使用量必须可解释

系统 SHALL 在信号可用时按 runtime、角色和受控 Provider 维度聚合请求耗时、首响应/首 Token、输入/输出/缓存 Token、重试与估算成本；缺失信号 SHALL 显式标记为不可用。

#### Scenario: 计算首 Token 耗时

- **WHEN** 同一 runtime attempt 具有可信请求开始事件和首次助手文本事件
- **THEN** 系统以两者时间差记录首 Token 耗时并关联该 attempt

#### Scenario: runtime 不提供首 Token 事件

- **WHEN** runtime 只有最终响应或用量而没有首 Token 事件
- **THEN** 首 Token 指标不可用，但总耗时和 Token 指标可独立可用

#### Scenario: Provider fallback 产生多个计费 attempt

- **WHEN** 同一次角色执行产生多个具有不同 attemptId 的 Provider 尝试
- **THEN** 系统对实际观测到的各 attempt 用量去重后求和，并保留成功/失败结果分解

#### Scenario: 展示成本

- **WHEN** 系统展示 Provider 成本
- **THEN** 成本明确标记为估算值、币种和汇率时间，且不得宣称为权威账单

### Requirement: Prometheus 指标必须保持兼容且控制标签基数

系统 SHALL 保留 `/actuator/prometheus` 路径，并 SHALL NOT 在 Prometheus 标签中写入 taskId、stageRunId、projectId、仓库地址、自由文本错误或其它无界值。现有指标只有在使用真实同义数据时才能保留；不能保持语义的指标 SHALL 经弃用期移除。

#### Scenario: 暴露角色阶段指标

- **WHEN** Prometheus 抓取交付指标
- **THEN** 标签只使用受控枚举或配置 allowlist，例如角色、状态、runtime、Provider、失败类别和资源类型

#### Scenario: 兼容旧指标名

- **WHEN** 一个旧指标能够从真实同义信号重新计算
- **THEN** 系统在弃用期保留旧名称并使其值与新指标口径一致，同时记录替代名称

#### Scenario: 旧指标语义错误

- **WHEN** 一个旧指标只能通过无关数据维持
- **THEN** 系统不继续发布误导值，并在发布说明和管理端健康信息中说明迁移方式

### Requirement: 管理 API 必须支持范围、时间窗口和下钻

系统 SHALL 提供只读的交付可观测性 API，支持全部项目或具体 projectId、受控时间窗口、角色、runtime/Provider 和失败类别过滤，并返回总览、时间序列、失败分布、容量和数据质量信息。

#### Scenario: 查询具体项目

- **WHEN** 操作者以合法 projectId 和时间窗口查询交付观测
- **THEN** 响应只包含该项目范围内的数据并回显归一化范围与窗口

#### Scenario: 查询全部项目

- **WHEN** 操作者不传 projectId
- **THEN** 系统查询全部项目且不使用展示用哨兵值作为真实项目 ID

#### Scenario: 非法窗口或分页参数

- **WHEN** 请求包含不支持的窗口、未来时间、超大范围或非法分页参数
- **THEN** 系统返回稳定的 400 错误且不执行无界聚合查询

#### Scenario: 从聚合结果下钻

- **WHEN** 操作者选择某个失败类别、角色或慢阶段
- **THEN** 系统提供受分页约束的任务列表或已有任务/执行追踪入口，不返回原始私有事件

### Requirement: 管理台必须展示健康、延迟、容量、成本和数据质量

管理台 SHALL 提供项目范围一致的交付可观测性页面，展示吞吐与成功、延迟分位、阶段瓶颈、Provider/Token/成本、队列容量、失败趋势和数据质量，并 SHALL 对不可用、无样本和过期数据使用不同视觉状态。

#### Scenario: 切换项目和窗口

- **WHEN** 操作者切换项目或时间窗口
- **THEN** 页面重新查询对应范围、不会展示上一范围的过期结果，并保持筛选条件可复现

#### Scenario: 查看慢阶段

- **WHEN** 操作者选择延迟最高的阶段
- **THEN** 页面展示该阶段的样本数、P50/P95/P99、趋势和任务下钻入口

#### Scenario: 响应式与无障碍展示

- **WHEN** 页面在 390px、900px 和桌面视口打开
- **THEN** 核心健康状态和异常入口保持可见、无横向页面溢出，并可通过键盘访问

### Requirement: SLO 与告警必须先观察后启用

系统 SHALL 支持为排队年龄、端到端 P95、人工介入率、采集失败、资源饱和、lease 丢失和成本异常定义告警，但初始 SHALL 以观察模式运行，直到窗口满足配置的最小天数和最小样本数。

#### Scenario: 基线样本不足

- **WHEN** 基线窗口未达到配置的最小天数或终态样本数
- **THEN** 页面显示基线不足且不会把建议阈值标记为正式 SLO

#### Scenario: 告警命中

- **WHEN** 已启用的阈值连续满足配置的持续时间
- **THEN** 系统产生可追溯告警并包含指标、范围、窗口和下钻入口，但不自动重试、扩容或改变任务状态

### Requirement: 可观测数据必须脱敏并保持任务治理边界

系统 SHALL 只聚合脱敏后的结构化字段， SHALL NOT 在指标、趋势 API 或页面中暴露密钥、完整 Prompt、原始模型输出、原始事件、文件内容或自由文本错误；可观测性读取 SHALL NOT 推进任务状态或触发执行。

#### Scenario: Provider 错误包含敏感值

- **WHEN** Provider 错误或事件中包含凭据、URL 参数或自由文本
- **THEN** 聚合面只记录受控失败类别和脱敏摘要，Prometheus 不包含该文本

#### Scenario: 抓取与查询可观测数据

- **WHEN** Prometheus 或管理台读取观测数据
- **THEN** 读取不会创建新 attempt、消费命令、修改 lease 或改变任务状态
