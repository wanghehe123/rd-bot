# Requirement Fresh Executor Episode Specification

## Purpose

定义需求交付每个角色 Attempt 的 fresh episode 合同：派发给 Pi 的 Prompt 与 `PROMPT_SNAPSHOT` 只能携带 Host 已审计缺口与信任标注，不得回灌上一轮原始失败文本；QA Attempt 必须使用与 Coding 隔离的 provider-attempt 工作区，同时保留任务级包缓存。

## Requirements

### Requirement: 重试 Prompt 只能携带有界已审计缺口
系统 SHALL 在同一角色新 Attempt、`HOST_VERIFY_FIX` Coding Attempt 以及 QA 协议重试 Attempt 的角色 Prompt 中，用 Host 已审计缺口段替代上一轮 `errorMessage` 原文。缺口段 MUST 来自该任务 `AuditedTaskState` head（及最近 `AuditRun` 的 missing/blockers/untrusted/sourceRefs），MUST 包含 `state_version` 与 `state_hash`，缺口 ID 合计 MUST ≤ 16，全文 MUST ≤ 2000 字，证据字段 MUST 只含 URI。系统 MUST NOT 把 `AgentStageRun.errorMessage`、宿主 javac/stderr 墙或 QA 协议失败原文写入 Prompt 或 `PROMPT_SNAPSHOT`。无 head 或无缺口时 MUST 省略该段，而不是回退到 `errorMessage`。原始失败文本仍只存在于 `RESULT_JSON` / `AGENT_EVENTS` / 宿主验证产物。

#### Scenario: 同角色重试不再注入 errorMessage
- **WHEN** 某角色 Attempt 以 `FAILED_RETRYABLE` 结束且 `errorMessage` 非空，随后创建同角色下一 Attempt
- **THEN** 新 Attempt 的 `PROMPT_SNAPSHOT` 不含上一轮 `errorMessage` 子串，若 head 有缺口则含标题「已审计缺口（Host）」及对应记录 ID

#### Scenario: HOST_VERIFY_FIX 只点名 GATE-BUILD
- **WHEN** `HOST_VERIFY` 因 `PRODUCT_DEFECT` 失败且 `GATE-BUILD` 仍为 `PENDING`，系统冻结 `HOST_VERIFY_FIX` 并派发新的 Coding Attempt
- **THEN** 该 Coding Prompt / `PROMPT_SNAPSHOT` 含 `GATE-BUILD`，不含宿主 BUILD 步骤的截断编译日志

#### Scenario: 无审计 head 时保持空白缺口段
- **WHEN** 任务尚无 `AuditedTaskState` head（或 store 未装配）且存在上一轮失败 `errorMessage`
- **THEN** 新 Attempt Prompt 既不含「上一轮失败反馈」原文墙，也不伪造缺口 ID

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

### Requirement: QA episode 工作区与 Coding 隔离并共享 cache
系统 SHALL 为每个 `QA_AGENT` Pi 执行创建独立 provider-attempt 工作区：`repo/`、`input/`、`output/` 位于任务根下 `provider-attempts/<stageRunId>/`，MUST 挂载同一任务级 `cache/` 到 `/work/cache`。QA 容器的 `/work/repo` MUST 保持可写（`npm run build` 需要写 `.next/` 等被忽略产物），MUST NOT 看到 Coding 工作区未提交私货，MUST NOT 挂任务根 `workspaces/<taskId>/repo`。系统 MUST NOT 为此清空或重建任务级 `cache/`。`READ_ONLY_REPO_ROLES` MUST 仍只含 `REQUIREMENT_REVIEWER` 与 `SOLUTION_ARCHITECT`。

#### Scenario: QA repo 来自独立 provider-attempt
- **WHEN** Host 启动 `QA_AGENT` Pi 容器
- **THEN** `/work/repo` 的宿主路径包含 `/provider-attempts/`，该目录存在，且任务根 `repo/` 不在该容器的 mounts 里

#### Scenario: 候选补丁隔离且 cache 保留
- **WHEN** QA 命令附带 `candidate-patch.diff`，任务根已有 Coding `repo/`/`output/` 私货和 `cache/` 标记文件
- **THEN** QA 工作区收得到补丁附件，看不到 Coding 私货文件，且任务 `cache/` 标记文件仍在
