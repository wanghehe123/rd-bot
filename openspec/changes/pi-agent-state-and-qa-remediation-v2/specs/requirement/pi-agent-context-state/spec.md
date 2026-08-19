## Purpose

定义由 Java 控制面建立、由 Pi 运行时受控推进并可跨实例审计的 Agent 状态合同，使每次模型调用都能获得准确目标、阶段、预算和 TODO，同时保持历史执行路径兼容。

## ADDED Requirements

### Requirement: PI 状态能力必须显式冻结
系统 SHALL 只为 execution profile snapshot 同时声明 `runtime=PI` 与 `PI_AGENT_STATE_V2` capability、且 Host kill switch 开启的 Attempt 启用 v2 状态能力。没有该冻结 capability 的新旧 PI Attempt 和所有非 PI runtime MUST 保持既有请求、工具和 artifact 行为。

#### Scenario: 显式启用状态能力
- **WHEN** 一个新 Attempt 的冻结 execution profile 为 PI、包含 `PI_AGENT_STATE_V2` 且 Host kill switch 开启
- **THEN** 系统为该 Attempt 建立 `rd-agent-state/v2` 初始状态并启用受控状态工具和上下文注入

#### Scenario: 历史或未启用 PI 保持兼容
- **WHEN** 一个 Attempt 为 PI 但冻结 snapshot 不含 `PI_AGENT_STATE_V2`，或 Host kill switch 关闭
- **THEN** 系统不得生成 v2 初始状态、注册 v2 状态工具或产出 v2 有效上下文，并保持既有 PI 请求语义

### Requirement: 初始状态必须由 Host 建立
系统 SHALL 在 Attempt 进入运行前由 Java 控制面计算非空初始状态，至少包含任务与阶段身份、角色、Attempt、当前目标、任务和阶段开始时间、阶段、预算可用性及 Host TODO。Pi bridge MUST 校验该状态的协议、身份、canonical bytes 与 hash，且不得以空状态替代缺失或非法的 Host 状态。

#### Scenario: 首次模型调用获得完整状态
- **WHEN** 一个已启用 v2 的 PI Attempt 发起第一次模型调用
- **THEN** 模型有效上下文的结尾包含与该 Attempt 身份匹配的非空 Host 初始状态，其目标、时间、阶段、预算和 TODO 均可验证

#### Scenario: 初始状态缺失时失败关闭
- **WHEN** 已启用 v2 的 PI 请求缺少 Host 初始状态，或状态 hash、stageRunId、role、attemptNo 任一不匹配
- **THEN** bridge 在启动 Agent 前拒绝请求，不得自行创建空 TODO 状态继续执行

### Requirement: Host TODO 必须覆盖验收责任
系统 SHALL 为每条验收标准创建独立、不可被 Agent 删除的 Host TODO，并保留验收标准完整内容或 hash-bound 受控 attachment 引用。Agent MAY 增加子项或更新合法状态，但完成 Host TODO MUST 关联该验收项自己的有效证据。

#### Scenario: 每条验收标准独立映射
- **WHEN** 一个任务包含多条验收标准且其总量位于协议上限内
- **THEN** 初始状态为每条验收标准生成稳定 ID 的独立 Host TODO，且任何一条的内容和证据不会被聚合到另一条

#### Scenario: Agent 不能删除 Host TODO
- **WHEN** Agent 的状态动作尝试删除、覆盖或绕过一个 Host TODO
- **THEN** 系统拒绝该动作且状态 sequence 不增长

#### Scenario: 验收责任超限时不截断
- **WHEN** Host 必做验收项、单项内容或总 attachment 超过协议上限
- **THEN** 系统在 Attempt 进入 RUNNING 前失败关闭并给出可审计原因，不得截断、聚合或丢弃验收责任

### Requirement: 状态动作必须原子、单调且受限
系统 SHALL 使用独立单调 sequence 和 action 幂等身份提交状态更新；非法状态迁移、重复但冲突的 action、越界字段、疑似密钥或无法安全注入的 candidate MUST 被拒绝且不得改变已提交状态。

#### Scenario: 合法 TODO 更新提交
- **WHEN** Agent 对允许修改的 TODO 提交合法、bounded 且不含敏感信息的状态动作
- **THEN** 系统原子提交新快照、sequence 恰好增加一次并记录可验证 hash

#### Scenario: 重放相同动作
- **WHEN** 相同 action identity 和相同 payload 被重复提交
- **THEN** 系统返回原提交结果且不重复增加 sequence

#### Scenario: 冲突或敏感动作被拒绝
- **WHEN** action identity 重复但 payload 不同，或 candidate 包含越界内容、疑似凭据、非法迁移或无法注入的文本
- **THEN** 系统拒绝整个动作，保留原状态 sequence/hash 且留下安全诊断

### Requirement: 最新状态必须位于有效上下文末尾
系统 SHALL 在每次 PI 模型上下文构建时移除旧的自定义状态块，并把当时最新的已提交状态作为唯一状态块追加到有效上下文结尾。状态 sequence 和注入 sequence MUST 独立单调，且每次实际注入 MUST 记录准确的 state sequence/hash、Prompt hash、injected block/hash 与时间。

#### Scenario: 状态更新在下一次调用生效
- **WHEN** 状态 sequence 在一次模型调用后增长
- **THEN** 下一次模型调用的有效上下文只含一份新状态块且该块位于上下文结尾

#### Scenario: 状态未变但发生新注入
- **WHEN** state sequence/hash 未变化但系统构建并发送了新的模型上下文
- **THEN** injection sequence 增长并记录新的注入事实，状态 sequence 保持不变

### Requirement: 运行中状态必须跨实例可观测
系统 SHALL 将经过 Host 独立校验和再次脱敏的最新状态与最近注入事实投影到 PostgreSQL，并以 stageRunId 和单调 sequence 做 CAS。Attempt 结束后系统 MUST 归档最终状态和有效上下文 artifact，并将投影标记为 finalized；运行中投影写入失败不得篡改 Agent 内部状态。

#### Scenario: 更大 sequence 推进投影
- **WHEN** Host 收到身份、canonical hash 和 sanitizer 均通过且 sequence 更大的状态或注入事件
- **THEN** 系统以 CAS 更新该 stageRunId 的对应投影并保留另一套 sequence

#### Scenario: 冲突重放被拒绝
- **WHEN** Host 收到相同 sequence 但 hash 或身份不同的事件，或收到倒退 sequence
- **THEN** 系统拒绝投影更新并告警，不得覆盖较新的已持久化状态

#### Scenario: 投影故障不改变 Agent 状态
- **WHEN** PostgreSQL live projection 写入暂时失败
- **THEN** Agent 内部已提交状态保持不变，系统记录观测故障并在管理 API 中暴露 stale provenance

### Requirement: 状态协议必须有确定性与安全边界
系统 SHALL 对 v2 状态使用同一套 Java/Node canonical JSON 与 SHA-256 合同，整数 MUST 位于 JSON safe-integer 范围；所有快照、动作、注入块和 artifact MUST 受字段、单项、总量限制和脱敏规则保护，且不得包含 Provider 对话历史、隐藏 reasoning、凭据或未验证的外部路径。

#### Scenario: Java 与 Node 得到相同 hash
- **WHEN** Java 和 Node 校验同一份包含 Unicode、转义、未知预算和边界整数的合法 fixture
- **THEN** 二者产生完全相同的 canonical bytes 和 SHA-256

#### Scenario: 非 canonical 或超限状态被拒绝
- **WHEN** 状态含非法 surrogate、超 safe-integer、hash mismatch、超限内容或敏感数据
- **THEN** Host 与 bridge 均失败关闭且不注入、不持久化该 candidate
