# requirement/fresh-executor-episode Delta

## MODIFIED Requirements

### Requirement: 重试 Prompt 只能携带有界已审计缺口
系统 SHALL 在同一角色新 Attempt、`HOST_VERIFY_FIX` Coding Attempt、QA 协议重试 Attempt，以及 **checkpoint 打回上游时的恢复 Prompt（含 `downstreamFailureFeedbackSection`）** 中，用 Host 已审计缺口段替代上一轮 / 下游 `errorMessage` 原文。缺口段 MUST 来自该任务 `AuditedTaskState` head（及最近 `AuditRun` 的 missing/blockers/untrusted/sourceRefs），MUST 包含 `state_version` 与 `state_hash`，缺口 ID 合计 MUST ≤ 16，全文 MUST ≤ 2000 字，证据字段 MUST 只含 URI。系统 MUST NOT 把 `AgentStageRun.errorMessage`、宿主 javac/stderr 墙或 QA 协议失败原文写入 Prompt 或 `PROMPT_SNAPSHOT`。无 head 或无可渲染缺口时：同角色 `previousFailureFeedbackSection` MUST 省略缺口段；`downstreamFailureFeedbackSection` MUST 使用固定「尚无已审计状态/缺口不可用」短句，且 MUST NOT 回退到 `errorMessage`。原始失败文本仍只存在于 `RESULT_JSON` / `AGENT_EVENTS` / 宿主验证产物。

#### Scenario: 同角色重试不再注入 errorMessage
- **WHEN** 某角色 Attempt 以 `FAILED_RETRYABLE` 结束且 `errorMessage` 非空，随后创建同角色下一 Attempt
- **THEN** 新 Attempt 的 `PROMPT_SNAPSHOT` 不含上一轮 `errorMessage` 子串，若 head 有缺口则含标题「已审计缺口（Host）」及对应记录 ID

#### Scenario: HOST_VERIFY_FIX 只点名 GATE-BUILD
- **WHEN** `HOST_VERIFY` 因 `PRODUCT_DEFECT` 失败且 `GATE-BUILD` 仍为 `PENDING`，系统冻结 `HOST_VERIFY_FIX` 并派发新的 Coding Attempt
- **THEN** 该 Coding Prompt / `PROMPT_SNAPSHOT` 含 `GATE-BUILD`，不含宿主 BUILD 步骤的截断编译日志

#### Scenario: 无审计 head 时保持空白缺口段
- **WHEN** 任务尚无 `AuditedTaskState` head（或 store 未装配）且存在上一轮同角色失败 `errorMessage`
- **THEN** 新 Attempt Prompt 既不含「上一轮失败反馈」原文墙，也不伪造缺口 ID

#### Scenario: checkpoint 打回上游不注入下游 errorMessage
- **WHEN** 存在 `TaskRetryCheckpoint`（`failurePhase=AGENT_ROLE`），`retryFromRole` 为上游角色，`failedStageRunId` 指向下游失败 stage，且该 stage 的 `errorMessage` 含唯一 marker `stderr-secret-fixture-unique`，任务 audited head 缺 `GATE-BUILD`
- **THEN** 上游恢复 Attempt 的 Prompt 含 `GATE-BUILD` 与 head `state_hash`，MUST NOT 含该 marker

#### Scenario: checkpoint 打回且无 head 时明示缺口不可用
- **WHEN** 同上打回条件但任务无 audited head
- **THEN** 上游 Prompt 含「尚无已审计状态/缺口不可用」，MUST NOT 含下游 `errorMessage` 原文

### Requirement: Compact 交接不得把未审计事实标成已实测
系统 SHALL 把 compact 上游交接中的 `environmentNotes` 与 `facts[]` 默认标注为 `UNTRUSTED`。仅当对应 FACT 记录已被某次 `AuditRun` 晋升为 `COMPLETED` 时，系统 MUST 标注 `VERIFIED(auditRunId=…)`。角色指令与交接散文 MUST NOT 包含「已实测验证，直接沿用」或把上游 facts 视为已验证事实直接沿用的措辞。Compact 交接 JSON MUST NOT 再包含 `errorMessage` 字段。

#### Scenario: 环境备忘带 UNTRUSTED
- **WHEN** 上游角色结果含 `environmentNotes` 或由 OBSERVED facts 派生的备忘，下游角色 Prompt 渲染 compact 交接
- **THEN** 备忘标题或每条前缀标明 UNTRUSTED，且全文不含「已实测验证，直接沿用」

#### Scenario: 已晋升 fact 带 VERIFIED
- **WHEN** head 中某 FACT 记录状态为 `COMPLETED` 且 `lastAuditRunId` 非空
- **THEN** compact `facts[]` 对应项标注 `VERIFIED` 并带上该 `auditRunId`

#### Scenario: Compact JSON 不含 errorMessage
- **WHEN** 上游 stage 结果 JSON 含非空 `errorMessage`
- **THEN** 写入下游 Prompt 的 compact 交接 JSON 没有 `errorMessage` 键

## ADDED Requirements

### Requirement: 角色证据必须显式区分 HINT 与 VERIFIED
系统 SHALL 在 `RoleContextEvidence`（及写入 `RoleExecutionInputManifest` 的证据条目）上携带 `trust=HINT|VERIFIED`；检索命中默认 `HINT`，不得仅凭 `requiredEvidenceType` 满足 AGENT_ROLE 角色证据门。Host 任务根、当前任务 Host 材料、以及 Host 校验过的 directed ROLE_HANDOFF MUST 为 `VERIFIED`。`VERIFIED` 可携带当前任务适用的 `auditRunId`；来自旧任务或不可反查的经验 MUST 保持 `HINT`。`UNTRUSTED_PROJECT_MEMORY` 渲染 MUST 继续禁止授权绕过，并暴露 `trust=HINT`。

#### Scenario: 检索命中自称 QA 已通过仍为 HINT
- **WHEN** 检索返回摘要含「QA已通过」且无当前任务适用 AuditRun 的 VERIFIED 证据
- **THEN** 该证据 `trust=HINT`，角色证据门仍报告缺少对应 TEST_ENTRY/CODE_SYMBOL 等类型（可降级仓库发现，但不得因 HINT 标 SUFFICIENT）

#### Scenario: 同任务 Host VERIFIED 证据可通过门
- **WHEN** 选中证据含当前任务 Host 材料或带适用 `auditRunId` 的 `trust=VERIFIED` 且类型匹配角色门
- **THEN** 对应 missing 角色类型被清除，质量决策可为 SUFFICIENT

### Requirement: Episode 硬预算冻结且禁止默认为 0
系统 SHALL 在执行 profile snapshot 中记录 `episodeBudgetStatus`。当 `RD_PI_MAX_AGENT_TURNS` 与 `RD_PI_MAX_TOTAL_TOKENS` 均为正整数时，snapshot MUST 冻结二者且状态为 `FROZEN_FROM_ENV`，Pi request MUST 携带相同正整数。系统 MUST NOT 把 0 写入 snapshot/request 作为“关闭预算”的默认值。在 `role-budget-defaults.json` 为 `NOT_FROZEN`（缺每角色≥3 有效样本）时，系统 MUST 披露不可冻结原因，不得伪造 P95 衍生值。

#### Scenario: 正数 env 冻结进 snapshot 并进入 request
- **WHEN** 解析 profile 时环境变量二者均为正整数
- **THEN** snapshot JSON 含相同 `maxAgentTurns`/`maxTotalTokens` 与 `episodeBudgetStatus=FROZEN_FROM_ENV`，且写出的 Pi request 含相同字段

#### Scenario: 缺样本时 defaults 保持 NOT_FROZEN
- **WHEN** `role-budget-defaults.json` 的 `status` 为 `NOT_FROZEN`
- **THEN** 文件中 `roles` 不包含伪造的 0 预算，reason 说明缺少 B07 样本
