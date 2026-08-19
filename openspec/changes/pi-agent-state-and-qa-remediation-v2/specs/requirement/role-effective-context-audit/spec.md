## Purpose

定义管理端后端对角色静态 Prompt、最近真实注入状态和最新 Agent 状态的安全审计合同，使操作者能准确判断模型实际接收了什么，而不会把最新但尚未注入的状态误报为有效上下文。

## ADDED Requirements

### Requirement: 审计数据必须精确绑定同一 Stage Attempt
系统 SHALL 只返回与请求 taskId 下精确 stageRunId 绑定的 Prompt artifact、状态投影和有效上下文，不得回退到任务级基线 Prompt、其他 Attempt 或其他角色的数据。

#### Scenario: 返回已执行 Attempt 的审计数据
- **WHEN** 管理员查询一个具有真实 Prompt 和 PI 状态产物的角色 Attempt
- **THEN** 响应中的静态 Prompt、latest state 和 effective context 均携带相同 stageRunId、role 和 attemptNo provenance

#### Scenario: Attempt 尚未生成 Prompt
- **WHEN** 目标 stage 尚未派发或没有实际 Prompt artifact
- **THEN** 响应明确标记不可用和实际原因，不得使用任务基线 Prompt伪装角色 Prompt

### Requirement: 有效上下文必须表示最近一次真实注入
系统 SHALL 将“有效上下文”定义为已持久化静态角色 Prompt与最近一次真实注入的 bounded、sanitized 状态块的有序组合。响应 MUST 包含 composition order、Prompt provenance/hash、独立 injection sequence、injected state sequence/hash、injected block hash、组合 preview/hash 和注入时间；不得声称包含 Provider 对话历史或隐藏 reasoning。

#### Scenario: 状态已真实注入
- **WHEN** Host 已记录一次通过校验的上下文注入事件
- **THEN** API 返回由该 Prompt 与该次准确 injected block 组成的 effective context，而不是用当前 latest state 重新拼装

#### Scenario: 只有最新状态但从未注入
- **WHEN** stage 有 latest state 但没有任何真实注入记录
- **THEN** latest state 可用而 effective context 明确不可用，API 不得标记“动态状态已注入”

### Requirement: 最新状态与已注入状态必须独立展示
系统 SHALL 分别返回 latest state sequence/hash 和 effective context 的 injected state sequence/hash。二者不同是合法状态；当 latest state 更新但尚未产生新注入时，响应 MUST 明确表示“最新状态尚未注入”。

#### Scenario: 最新状态领先于注入状态
- **WHEN** latest state sequence 为 12而最近注入引用 state sequence 10
- **THEN** API 同时返回两个 provenance，effective context 仍展示 sequence 10 的实际 block并标记最新状态尚未注入

#### Scenario: 仅 injection sequence 前进
- **WHEN** state sequence/hash 不变但发生新的实际上下文注入
- **THEN** API 的 injection sequence 和 injected block provenance 前进，使消费端能够刷新有效上下文

### Requirement: Host 必须提供 freshness 和 stale provenance
系统 SHALL 为 active live projection 计算并返回 `stale`、`staleReason`、最后投影时间和冻结 stale threshold；finalized archived artifact 不得仅因墙钟时间被标记 stale。API MUST 标明数据来源为 live projection 或 archived artifact。

#### Scenario: Active 投影超过阈值
- **WHEN** 运行中 stage 的最后投影时间超过 Host 配置阈值
- **THEN** API 返回 stale=true、原因、最后时间和阈值，而不是宣称状态最新

#### Scenario: Finalized artifact 长期存在
- **WHEN** stage 已结束且 API 读取通过校验的 archived artifact
- **THEN** 响应标记 finalized/source，并且不因当前时间距离归档较久而标 stale

### Requirement: 审计响应必须完整且可验证
系统 SHALL 返回消费端判断新旧响应所需的 generation、stage identity、state/injection sequence和显式 hashes。内容 preview MAY 截断，但 MUST 提供原内容长度、preview 长度、truncated 标记和对实际安全 preview 的 hash；`contentHash` 不得代替 `injectedBlockHash`。

#### Scenario: 响应可拒绝过期 payload
- **WHEN** 消费端在请求发出后收到 stage identity 或 state/injection provenance 落后的响应
- **THEN** 响应包含足够字段让消费端拒绝该 payload，且后端不会以组合 contentHash 冒充 block hash

#### Scenario: Preview 被安全截断
- **WHEN**静态 Prompt、状态或组合内容超过管理 API preview 上限
- **THEN** API 在安全边界处截断并准确返回长度/truncated/hash 元数据，不泄露被省略内容

### Requirement: 审计 API 必须保护敏感信息
系统 SHALL 只返回经过 Host schema、identity、canonical hash 和 sanitizer 校验的安全字段或 preview。响应 MUST 排除凭据、环境变量值、认证 Header、隐藏 reasoning、原始 Pi session、未经验证的工具参数/结果和外部对象存储 URL。

#### Scenario: 投影包含疑似敏感值
- **WHEN** live event 或 artifact candidate 含疑似密钥、认证 Header 或未验证外部路径
- **THEN** Host 拒绝或脱敏该 candidate 并记录诊断，管理 API 不返回原值

### Requirement: 非 PI 与 legacy Attempt 必须给出明确边界
系统 SHALL 对未启用 v2 状态 capability 的 PI Attempt和所有非 PI runtime保留静态 Prompt 审计能力，并明确返回 latest state/effective context 不可用的原因，不得合成 v2 状态。

#### Scenario: Legacy Attempt 查询
- **WHEN** 管理员查询没有 `PI_AGENT_STATE_V2` capability 的历史 Attempt
- **THEN** API 可返回其真实静态 Prompt，但 latest state/effective context 标记不可用并说明 legacy/capability 原因
