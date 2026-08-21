## Purpose

定义 PI QA 对真实产品缺陷的结构化打回、Coding 修复上下文、协议失败重试和跨实例恢复合同，使必要修复能自动推进且不会形成无证据或无上限的重试回路。

## ADDED Requirements

### Requirement: PI-v2 QA 必须显式请求产品修复
系统 SHALL 仅在来源 QA Attempt 的冻结 execution profile 为 PI 且声明 `PI_QA_REMEDIATION_V2` 时使用 v2 remediation 合同。QA 失败结果 MUST 通过结构化 `remediationRequest` 和 `bugFindings` 明确表达是否需要 Coding 修复；`failureCategory` 不得作为 PI-v2 产品打回白名单。

#### Scenario: QA 明确请求 Coding 修复
- **WHEN** PI-v2 QA 返回 FAILED、`remediationRequest.requested=true`、target 为 Coding 且包含有效 bug findings
- **THEN** 系统按 PI-v2 产品修复条件评估该请求，不因 failure category 不是 PRODUCT_DEFECT 或 REGRESSION 而拒绝

#### Scenario: 非产品失败不打回 Coding
- **WHEN** QA 将环境、鉴权、基础设施、需求歧义或 flaky 问题标记为 FAILED 且 `requested=false`
- **THEN** 系统不得创建 Coding remediation Attempt

#### Scenario: 成功结果不能请求修复
- **WHEN** QA 结果不是 FAILED 却携带 requested=true 或非空 bug findings
- **THEN** 容器内和 Host schema 校验均拒绝该结果

### Requirement: 每个打回缺陷必须有验收和文件证据
系统 SHALL 要求每个 `bugFinding` 至少绑定一条失败的当前验收或回归验收，以及该验收实际引用并在 manifest 中存在的文件证据。Host MUST 权威校验证据文件存在、非空、bytes 和 SHA-256 与 manifest 一致；仅在 JSON 中声明路径不得视为有效证据。

#### Scenario: 有效 finding 被接受
- **WHEN** finding 引用失败验收，evidence ID 同时出现在该验收引用和 manifest，且 Host 校验文件 bytes/hash 一致
- **THEN** 该 finding 可参与 remediation 决策

#### Scenario: 伪造或断链证据被拒绝
- **WHEN** finding 未关联失败验收、evidence 未被该验收引用、文件缺失/为空或 bytes/hash 不匹配
- **THEN** 系统拒绝整个 remediation request，不得创建任何新 Attempt

### Requirement: PI-v2 产品修复最多自动执行两轮
系统 SHALL 对每个任务最多成功 claim 两个 PI-v2 `QA_PRODUCT_FIX` remediation round，并同时遵守 Coding 与 QA 的 `attemptNo <= 3` 硬上限。产品修复轮次不足时 MUST 转人工，不得突破任一上限。

#### Scenario: 第一轮产品修复
- **WHEN** 初始 QA 提交有效 remediation request、尚无成功产品修复 claim 且 Coding/QA 下一 Attempt 均未超过 3
- **THEN** 系统创建绑定同一 remediation round 的下一 Coding Attempt 和后续 QA Attempt

#### Scenario: 第二轮产品修复
- **WHEN** 后续 QA 再次提交有效 remediation request、已有一个成功产品修复 claim 且仍有 Coding/QA Attempt 容量
- **THEN** 系统创建第二个且最后一个产品修复 round

#### Scenario: 第三次请求或 Attempt 耗尽
- **WHEN** 已成功 claim 两轮产品修复，或任一目标角色下一 attemptNo 将超过 3
- **THEN** 系统不创建新 Attempt并进入明确的人工处置状态，告警展示计划上限、已占用和剩余容量

### Requirement: Legacy QA 路由必须保持兼容
系统 SHALL 对没有冻结 `PI_QA_REMEDIATION_V2` capability 的历史/新 PI Attempt，以及 Claude Code、MODEL_ONLY 等其他 runtime，保留现有结构化结果合同、现有 `FAILED + PRODUCT_DEFECT|REGRESSION + CODING_AGENT` predicate 和最多一轮 remediation 行为。

#### Scenario: 无 capability 的新 PI
- **WHEN** 一个新 PI QA Attempt 未冻结 `PI_QA_REMEDIATION_V2`
- **THEN** 系统使用 legacy predicate 和一轮上限，不强制 v2 remediation 字段

#### Scenario: 非 PI runtime
- **WHEN** QA Attempt 的冻结 runtime 为 Claude Code 或 MODEL_ONLY
- **THEN** 本 change 不改变其既有 QA 路由和结果合同

### Requirement: Coding 必须收到完整且受控的修复包
系统 SHALL 为每个产品修复 round 生成 bounded、sanitized、hash-bound 的 `qa-remediation/request.json` attachment，并在 Coding Prompt 中提供来源 QA、round、摘要、reason、每个 finding 的验收、复现、expected/actual、已验证证据 hash 和必做 TODO。Prompt MUST 引用受控 attachment，且不得嵌入对象存储 URL、原始浏览器会话、超大日志或未经验证的外部路径。

#### Scenario: Coding 上下文包含可执行反馈
- **WHEN** 产品修复 round 被可靠创建并派发 Coding
- **THEN** Coding 有权读取与该 round hash 一致的 remediation attachment，且 Prompt 能逐项定位需要修复的 finding 和证据

#### Scenario: 修复包超限或含敏感数据
- **WHEN** remediation package 超过协议上限或 sanitizer 发现凭据/不安全路径
- **THEN** 系统在派发前失败关闭并转人工，不得截断后继续 Coding

### Requirement: Remediation 必须持久化、原子且幂等
系统 SHALL 以 PostgreSQL ledger 和 immutable execution-plan intent 作为 remediation 真值。记录来源 outcome、claim round、创建 target stages/profile snapshots/first command 和推进来源 disposition MUST 在规定的事务/CAS 边界完成；重复消费、崩溃恢复或多实例竞争不得增加轮次或生成不同身份。

#### Scenario: 同一来源失败被重复消费
- **WHEN** 多次处理相同 source stage failure 和 remediation kind
- **THEN** 所有处理返回同一个 ledger、target stage 和 command identity，成功 claim 计数只增加一次

#### Scenario: 两个实例竞争下一轮
- **WHEN** 两个实例并发处理同一任务的不同有效 QA source failure
- **THEN** 数据库锁/CAS 使 roundNo 唯一且不重复创建 target stages 或 commands

#### Scenario: outcome 记录后崩溃
- **WHEN** immutable intent 已进入 `OUTCOME_RECORDED`，但进程在 target objects 全部 finalization 前崩溃
- **THEN** 恢复路径使用相同 intent、IDs、snapshot JSON/hash 完成或告警，不重新执行 QA、不重新解析 live profile且不增加 round

#### Scenario: finalization 事务失败
- **WHEN** 插入 ledger、target stage、snapshot、command 或更新来源 disposition 任一步失败
- **THEN** 整个 finalization 回滚，不留下 orphan snapshot、半个 round 或不可派发 stage

### Requirement: Profile 决策必须在线性化点冻结
系统 SHALL 在写 `OUTCOME_RECORDED` 前对 intent 涉及的 source/target execution profiles 做最后一次一致性校验并与 Admin versioned update 线性化。marker 成功后 finalization 和恢复 MUST 只使用 immutable intent，不得因 live profile 后续变化而改变目标 runtime、capability 或 snapshot。

#### Scenario: Admin 更新先完成
- **WHEN** Admin profile version 更新在线性化竞争中先提交
- **THEN** recordOutcome 发现 prepared version 漂移并回滚，marker 保持可重试且不写 remediation intent/target objects

#### Scenario: outcome marker 先完成
- **WHEN** recordOutcome 先锁定并验证 profile 后成功写入 immutable intent
- **THEN** Admin 可在事务提交后继续更新，但后续恢复仍使用 marker 内原 target snapshot，不查询 live profile 改道

### Requirement: Eligible Pi 协议失败最多进行一次 QA→QA retry
系统 SHALL 只依据经 Host 验证、canonical hash 匹配的 `PiProtocolFailureReceipt/v1` 判定协议重试。每个任务最多成功 claim 一个 `QA_PROTOCOL_RETRY`；该路径只创建 QA Attempt，绝不得创建 Coding Attempt。

#### Scenario: settled 后一次恢复仍未提交结果
- **WHEN** receipt 证明 event stream 可信、容器已终止、Agent settled、缺少 RESULT_SUBMITTED，且 bounded bridge recovery applicable/issued/exhausted 均为 true
- **THEN** 系统可创建一次新的 QA Attempt并注入上一轮受控协议诊断

#### Scenario: Agent 提交有效结果但未 settled
- **WHEN** receipt 证明缺少 AGENT_SETTLED、结果来源为 `AGENT_RD_SUBMIT_RESULT`、role schema 已接受且 accepted digest 与持久化结果一致
- **THEN** 系统可创建一次新的 QA Attempt，且 synthetic bridge result 不能满足该条件

#### Scenario: schema 拒绝后恢复耗尽
- **WHEN** receipt 证明 Agent settled、缺少有效 RESULT_SUBMITTED、recovery 已耗尽且最后拒绝为 ROLE_SCHEMA并有 rejection digest
- **THEN** 系统可创建一次新的 QA Attempt

#### Scenario: 非 allowlist 或第二次协议失败
- **WHEN** receipt 缺字段/矛盾/hash 不匹配、事件流不可信、artifact/identity/state 校验失败，或已 claim 一次协议重试
- **THEN** 系统转人工且不创建 Coding 或 QA Attempt

### Requirement: Target runtime 隔离必须失败关闭
系统 SHALL 要求进入 PI-v2 产品修复或协议重试分支后，所有目标 Attempt 的冻结 profile 均为 PI 并具备所需 capability。任何目标 profile 缺失、非 PI、capability 不足或 Attempt 容量不足 MUST 在创建目标对象前转人工。

#### Scenario: PI QA 指向非 PI Coding
- **WHEN** 有效 PI-v2 QA remediation request 的目标 Coding profile 解析为 Claude Code、MODEL_ONLY 或缺少所需 capability
- **THEN** 系统不创建 remediation Attempt并记录可审计的人工处置原因

#### Scenario: 协议重试目标 QA 不合格
- **WHEN** eligible protocol receipt 的下一 QA profile 非 PI或缺少协议重试所需 capability
- **THEN** 系统不创建 QA retry并转人工
