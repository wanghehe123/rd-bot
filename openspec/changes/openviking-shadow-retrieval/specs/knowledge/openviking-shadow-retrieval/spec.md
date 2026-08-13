# openviking-shadow-retrieval

## ADDED Requirements

### Requirement: 读路径模式路由

系统 SHALL 提供 `LOCAL`、`SHADOW`、`OPENVIKING` 三种知识读取模式，默认 `LOCAL`，
由 `rd.rag.knowledge-provider-mode`（环境变量 `RD_RAG_KNOWLEDGE_PROVIDER_MODE`）决定。
模式只影响读路径，不得影响投影写路径。非法取值必须在启动期失败，不得静默退回默认值。

#### Scenario: 默认模式不触碰远端

- **WHEN** 未配置模式或配置为 `LOCAL`，且需求交付链路发起一次角色检索
- **THEN** 检索结果必须与未引入本能力时逐字节相同
- **AND** 只读导航端口必须零调用

#### Scenario: 非法模式值启动即失败

- **WHEN** 模式配置为 `LOCAL`/`SHADOW`/`OPENVIKING` 之外的任意值
- **THEN** 应用启动必须失败并列出合法取值
- **AND** 不得静默退回默认值

#### Scenario: 未绑定知识库时仍然跳过检索

- **WHEN** 项目没有绑定知识库（scope 为空）
- **THEN** 无论处于哪种模式，都必须沿用既有的 `MISSING_SCOPE` 审计分支
- **AND** 不得发起任何远端检索请求
- **AND** 不得退化成跨知识库全局检索

### Requirement: Shadow 模式不参与任务

系统在 `SHADOW` 模式下 SHALL 让 OpenViking 检索与本地检索并发执行，
且 OpenViking 结果只用于度量与审计，永不进入任务上下文，也不得延长角色派发的关键路径。

#### Scenario: Shadow 结果不改变任务输入

- **WHEN** 模式为 `SHADOW` 且 OpenViking 返回了任意结果
- **THEN** 返回给调用方的检索结果必须与纯 `LOCAL` 结果逐字节相同
- **AND** OpenViking 侧结果必须落入 retrieval artifacts

#### Scenario: Shadow 失败不影响任务

- **WHEN** 模式为 `SHADOW` 且 OpenViking 调用抛异常、超时或返回空
- **THEN** 返回给调用方的结果仍必须与纯 `LOCAL` 结果逐字节相同
- **AND** 角色派发不得因此失败
- **AND** 失败原因必须记录为 artifact 或 metric

#### Scenario: Shadow 不延长关键路径

- **WHEN** 模式为 `SHADOW` 且 OpenViking 侧超出其独立时间预算
- **THEN** 系统必须丢弃 OpenViking 本轮结果并继续
- **AND** 不得等待其完成

### Requirement: OpenViking 主检索的降级必须显式记录

系统在 `OPENVIKING` 模式下 SHALL 以 OpenViking 为主检索，
远端不可用或超出预算时回落本地检索，并在 Run 中留下明确的降级记录，不得静默兜底。

#### Scenario: 降级写入 DEGRADED

- **WHEN** 模式为 `OPENVIKING` 且远端不可用或超出预算
- **THEN** 系统必须回落到本地检索并在 Run 中记录 `DEGRADED` 与降级原因
- **AND** 不得静默兜底

### Requirement: 证据 allowlist 以本地库为唯一权威

系统 SHALL 让每条进入 Agent 上下文的 OpenViking 证据先通过本地 PostgreSQL 的准入判定：
绑定存在、绑定 `IN_SYNC` 且 `observed_version` 等于 `desired_version`、文档处于活跃状态、
文档知识库落在本次 scope 内。远端返回的内容本身不构成准入依据。

#### Scenario: 无本地绑定的远端资源被拒

- **WHEN** OpenViking 返回的 URI 在 `knowledge_external_index_bindings` 中查不到对应绑定
- **THEN** 该候选必须被丢弃并记录拒绝原因 `NO_BINDING`

#### Scenario: 版本未核验的绑定被拒

- **WHEN** 候选对应的绑定不是 `IN_SYNC`，或其 `observed_version` 与 `desired_version` 不相等
- **THEN** 该候选必须被丢弃并记录拒绝原因 `VERSION_NOT_VERIFIED`
- **AND** 远端仍存有的旧版本正文不得进入上下文

#### Scenario: 非活跃文档被拒

- **WHEN** 候选对应的文档 `enabled=false`，或已软删除，或已被 supersede
- **THEN** 该候选必须被丢弃并记录拒绝原因 `DOCUMENT_NOT_ACTIVE`

#### Scenario: 跨知识库证据被拒

- **WHEN** 候选对应文档的知识库不在本次检索 scope 内
- **THEN** 该候选必须被丢弃并记录拒绝原因 `OUT_OF_SCOPE`
- **AND** 单次检索产出的跨知识库证据数必须为 0

#### Scenario: 拒绝必须可观测

- **WHEN** 任意候选被 allowlist 拒绝
- **THEN** 拒绝数量与原因分布必须写入 retrieval artifacts
- **AND** 评测必须能区分「远端未召回」与「召回后被拒」

### Requirement: 三层导航受预算约束并显式停止

系统 SHALL 按 L0 摘要检索、L1 overview、L2 正文逐层展开三层导航，
受轮数、每层展开数、远端请求总数、Token 总量与总耗时约束，并以明确的停止原因收敛。
预算耗尽时必须返回已获得的部分证据，不得抛出异常，也不得无限追加远端请求。

#### Scenario: 预算耗尽返回部分结果

- **WHEN** 任一预算（轮数、远端请求数、Token、时间）耗尽而证据尚不充分
- **THEN** 系统必须返回已获得的证据与对应 `stop_reason`
- **AND** 不得抛出异常
- **AND** 不得继续追加远端请求

#### Scenario: 远端请求总数不可越界

- **WHEN** 远端每轮都返回新的候选
- **THEN** 单次检索的远端请求总数不得超过配置上限

#### Scenario: 每轮留证

- **WHEN** 三层导航执行任意一轮
- **THEN** 该轮必须记录查询与 scope、所用远端层级、候选 URI 与选择理由、
  展开的 URI 与版本 marker、缺失证据类型、Token 与耗时、以及停止原因

### Requirement: 外部正文按不可信数据处理

系统 SHALL 把来自 OpenViking 的 L0/L1/L2 文本作为不可信数据参与提示词构造：
置于标记的数据区，其中的指令性文本不得成为系统指令，导航也不得借此扩展工具权限或访问范围。

#### Scenario: 外部正文被标记隔离

- **WHEN** 外部检索内容进入提示词
- **THEN** 必须置于标记为 `<untrusted_knowledge>` 的数据区
- **AND** 其中的指令性文本不得成为 system、developer 或 tool 指令

#### Scenario: 导航不得扩权

- **WHEN** 外部正文中包含要求调用任意 URL 或修改工具权限的文本
- **THEN** 导航只能选择查询、URI 与层级
- **AND** 任何 URI 必须先通过 owned root 与 scope 校验才允许读取

### Requirement: 检索质量报告必须声明嵌入档与语料集

系统的检索质量报告 SHALL 声明所用嵌入档，并分别报告真实语料与合成语料的结果。
含唯一 ASCII token 的合成语料会系统性高估召回，不得单独作为切流依据。

#### Scenario: 合成语料不得单独作为切流依据

- **WHEN** 产出检索质量报告
- **THEN** 必须分别报告真实语料与合成语料的结果
- **AND** 必须标注合成语料（含唯一 ASCII token）会系统性高估召回
- **AND** 必须声明所用嵌入档
