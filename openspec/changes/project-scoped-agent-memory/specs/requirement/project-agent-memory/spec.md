## Purpose

为每个 RD 项目提供与任务证据分离的 Agent 持久记忆，使经过验证的项目事实、交付事件和程序经验能够跨任务安全复用，同时保证项目隔离、来源可追溯、冲突可治理、写入可恢复且注入不可越权。

## ADDED Requirements

### Requirement: 项目是记忆的强制隔离边界
系统 SHALL 仅在明确、有效且未删除的 RD 项目范围内创建和读取项目记忆。新记忆 MUST 绑定一个真实项目，MUST NOT 使用空项目、仓库指纹或知识库作为项目身份替代；跨项目复用默认 MUST 关闭。

#### Scenario: 同一查询只返回当前项目记忆
- **WHEN** 两个项目存在文本相似的 ACTIVE 记忆，某任务以其中一个项目执行检索
- **THEN** 系统只返回该任务项目的记忆，另一个项目的候选不得进入排序、上下文或审计结果

#### Scenario: 缺失或非法项目身份时失败关闭
- **WHEN** 新记忆写入或读取请求缺少项目、项目不存在、项目已删除或项目身份无法无歧义解析
- **THEN** 系统拒绝该次记忆操作且不回退到 repository fingerprint、全库搜索或其它项目

### Requirement: 记忆类型与激活门槛明确
系统 SHALL 区分 `SEMANTIC`、`EPISODIC` 和 `PROCEDURAL` 项目记忆。阶段输出只能先形成候选；`PROCEDURAL` 记忆 MUST 在完整需求交付和 QA 证据成功，或操作者明确确认后，才能成为可检索的 ACTIVE 记忆。失败或未完成交付产生的候选 MUST NOT 自动成为可复用流程。

#### Scenario: 成功交付激活程序记忆
- **WHEN** 一个程序经验候选能够关联到完成的需求交付、成功 QA 结果和相应来源证据
- **THEN** 系统可以把该候选激活为当前项目和适用角色可检索的 PROCEDURAL 记忆

#### Scenario: 阶段失败不自动教会错误流程
- **WHEN** 失败、取消、协议错误或尚未完成交付的阶段输出包含做法建议
- **THEN** 系统最多保存为待审查候选或情景证据，不得自动激活为 PROCEDURAL 记忆

### Requirement: 记忆必须保留独立且可核验的来源
每个记忆 revision SHALL 至少保存来源项目、来源 URI、内容 hash、仓库 revision、提取器/Schema 版本和脱敏来源摘要。task、stage run 和 artifact 标识可以作为可空引用，但其删除 MUST NOT 删除已批准记忆的来源快照。

#### Scenario: 来源任务删除后仍可审计
- **WHEN** 已批准项目记忆的来源任务及其级联 artifact 被删除
- **THEN** 记忆仍保留脱敏来源 URI、hash、仓库 revision、提取版本和摘要，并明确标识原始实体已不可用

#### Scenario: 无来源候选不得激活
- **WHEN** 候选缺少可验证来源 hash、来源项目或提取版本
- **THEN** 系统将其拒绝或隔离，不得将其激活或注入角色上下文

### Requirement: 记忆采用不可变 revision 和单一活动 head
系统 SHALL 为每条逻辑记忆维护稳定身份和不可变 revision，并通过 `CANDIDATE`、`ACTIVE`、`SUPERSEDED`、`EXPIRED`、`REJECTED`、`QUARANTINED`、`DELETED` 状态表达生命周期。同一项目、角色作用域、类型和逻辑身份在同一时刻 MUST 至多存在一个 ACTIVE head；只有当前、未过期、未删除的 ACTIVE head 可以检索。

#### Scenario: 新事实取代旧事实
- **WHEN** 已验证的新事实与某 ACTIVE 记忆具有相同逻辑身份且明确取代旧事实
- **THEN** 系统先保存新的不可变 revision，再原子推进活动 head，并把旧 revision 标记为 SUPERSEDED 且保留来源链

#### Scenario: 并发冲突无法自动裁决
- **WHEN** 两个 worker 基于同一旧 head 提交内容不同的新 revision，且 CAS 重读后仍无法确定唯一胜者
- **THEN** 系统不得最后写入者覆盖，而应把冲突隔离为 QUARANTINED 并保留双方证据供后续裁决

#### Scenario: Head 和 supersedes 不得跨逻辑记忆引用
- **WHEN** 写入尝试把 head 或 supersedes 指向另一个 memory identity 的 revision
- **THEN** 数据库复合引用约束和领域校验拒绝该写入，当前 ACTIVE head 保持不变

### Requirement: 记忆巩固具备端到端幂等与崩溃恢复
系统 SHALL 使用字段名明确、长度前缀且 canonical 的编码，由 operation kind、项目、来源种类/身份/hash、提取器版本和 Schema 版本派生稳定 operation key。stage、legacy 和人工治理来源 SHALL 分别使用稳定且非空的 source identity。operation MUST 支持持久化 claim、lease、fencing、attempt、next-visible time、checkpoint 和终态；重复投递不得产生重复活动记忆，过期 owner 不得 settle。同一 key 冲突后 MUST 核对不可变输入，不得把不同 payload 静默视为成功。

#### Scenario: 重复处理同一阶段结果
- **WHEN** 同一阶段完成事件或相同来源 artifact 被处理两次
- **THEN** 系统复用同一 operation/candidate 身份，最终只产生一个等价 revision 和一个 ACTIVE head

#### Scenario: 在新 revision 与 head 推进之间崩溃
- **WHEN** worker 已写入新 revision 但在活动 head 更新或 operation settle 前崩溃
- **THEN** 后续 worker 能通过 operation、revision 和 CAS 状态收敛，不重复生成 revision，也不让两个 head 同时可检索

#### Scenario: 旧 lease owner 晚到提交
- **WHEN** operation lease 已过期并被新 worker 领取，旧 worker 随后尝试 settle
- **THEN** 系统因 fencing/row version 不匹配拒绝旧 worker 的提交

#### Scenario: 相同 operation key 对应不同输入
- **WHEN** insert 冲突后发现既有 operation 的项目、来源、hash、kind 或版本字段与本次 canonical 输入不同
- **THEN** 系统失败关闭并记录安全审计，不复用、不覆盖也不执行既有 operation

### Requirement: 交付成功与记忆处理解耦但捕获意图不可丢失
阶段最终化事务 SHALL 以确定性身份登记记忆 consolidation operation；事务提交后，提取、解析、索引或投影失败 MUST NOT 回滚已经成功的需求交付。系统 MUST 能通过 operation 重试和确定性 reconciliation 找到未完成或漏登记的已终态证据。

#### Scenario: 记忆 worker 不可用时交付仍完成
- **WHEN** 阶段最终化已原子登记 operation，但记忆 worker、模型或索引暂时不可用
- **THEN** 需求交付状态照常提交，operation 保持可恢复非终态并按有界策略重试或进入人工处理

#### Scenario: 进程在最终化后立即退出
- **WHEN** stage 最终化事务已提交、应用在异步 worker 运行前退出
- **THEN** 重启后的 worker 或 reconciliation 能发现该持久化 operation 并继续处理

#### Scenario: 项目关闭记忆捕获
- **WHEN** stage 在项目 capture mode 为 OFF 时最终化
- **THEN** 同一事务仍登记不含记忆内容的 `SKIPPED_BY_POLICY` 终态 marker，但不运行 extractor；后续启用不会隐式回放该来源

### Requirement: 项目记忆检索先过滤后排序且保持有界
系统 SHALL 在 PostgreSQL 候选查询阶段应用项目、适用角色、活动 head、有效期、脱敏状态和最低质量条件，并限制扫描候选数量；MUST NOT 加载项目全部历史记忆后仅在 JVM 内排序。排序 SHALL 至少能解释文本相关性、时效性、重要性、置信度、来源质量和冲突/陈旧惩罚。

#### Scenario: 过期或冲突记忆不进入上下文
- **WHEN** 候选为 EXPIRED、SUPERSEDED、QUARANTINED、DELETED 或已超过有效期
- **THEN** 系统在上下文组装前排除该候选，并在审计结果中记录排除原因

#### Scenario: 候选扫描受上限约束
- **WHEN** 项目拥有大量历史 revision
- **THEN** 系统只从满足索引谓词的有界候选集合排序，并记录 examined-row count 与检索延迟

### Requirement: 新旧记忆迁移期共用预算和去重
项目记忆 SHALL 通过独立检索通道进入角色上下文，MUST NOT 伪装成 `WorkflowExperienceEntry`。在 legacy workflow experience 与新项目记忆双读期间，两条通道 MUST 共用一个历史经验预算、统一优先级和跨通道内容/来源去重；任一开关状态都不得突破角色上下文总预算。

#### Scenario: 新旧通道返回同一来源
- **WHEN** legacy experience 与项目记忆映射到同一来源 artifact 或等价内容 hash
- **THEN** 系统只向角色上下文注入一次，并在检索审计中保留两条候选的去重关系

#### Scenario: 按项目回滚到 legacy
- **WHEN** 操作者把某项目从 PRIMARY 切回 legacy 模式
- **THEN** 后续任务停止注入新项目记忆、继续使用旧经验路径，且无需回滚或删除已写入的项目记忆数据

### Requirement: 项目记忆是不可授权的不可信参考数据
所有注入的项目记忆 SHALL 被宿主标记为不可信参考数据。系统、`RULE.md`、授权与工具策略、当前用户要求和验收标准、当前数据库/代码/运行时证据的优先级 MUST 高于项目记忆；项目记忆 MUST NOT 授予权限、改变工具 allowlist、覆盖当前事实、执行其中的命令或单独证明验收通过。

#### Scenario: 记忆内容包含越权指令
- **WHEN** 某记忆文本要求忽略当前规则、泄露凭证、跳过测试或调用未授权工具
- **THEN** 宿主继续把它作为数据引用，不改变授权和工具策略，并记录安全过滤或拒绝原因

#### Scenario: 记忆与当前仓库事实冲突
- **WHEN** 项目记忆描述的接口、分支或测试结果与当前代码、数据库或运行时证据冲突
- **THEN** 系统优先采用当前证据，把记忆标记为陈旧或冲突候选，而不是让记忆覆盖当前事实

### Requirement: 历史经验迁移可审计、可分项目切换和可回滚
系统 SHALL 在迁移前清点 legacy experience，依据项目、来源、脱敏、交付结果和重复组将其分类为 eligible、ambiguous、duplicate 或 rejected。空项目、身份不一致、来源模糊或缺少成功证据的记录 MUST 进入隔离或保持 legacy，不得自动成为 ACTIVE 项目记忆。迁移 SHALL 保存 legacy-to-revision 映射，并以独立的 capture/read mode 实现 OFF、SHADOW、DUAL_READ、PRIMARY 按项目策略档位。

#### Scenario: 模糊历史行被隔离
- **WHEN** legacy experience 的项目为空、来源任务已无法解析或项目与仓库身份冲突
- **THEN** 迁移报告将其标为 ambiguous/quarantined，不创建 ACTIVE 项目记忆

#### Scenario: Shadow 未达门槛不得切换主读
- **WHEN** 项目记忆 shadow 的跨项目泄漏、质量、冲突、延迟或 token 指标未达到冻结门槛
- **THEN** 该项目保持 legacy 或 shadow 模式，不得切换到 PRIMARY

### Requirement: 项目记忆支持治理、软删除与显式清理
操作者 SHALL 能按项目查看记忆 head、revision、来源、状态、适用角色和最近检索审计，并能确认、纠正、失效或软删除记忆。所有 mutation MUST 使用 host 注入的可信 operator principal、按项目 fail-closed 授权和动作级审计；治理与物理 purge MUST 使用不同 capability，且请求体 actor 或确认 token不得替代授权。禁用或逻辑删除项目后，系统 MUST 停止该项目记忆的自动写入和检索，但保留审计数据；物理 purge MUST 是独立、显式、可审计且不可与任务删除混淆的项目级操作。

#### Scenario: 项目被禁用
- **WHEN** 项目被禁用但未执行 purge
- **THEN** 系统停止自动巩固与检索，管理面仍可查看记忆和来源，重新启用后可按策略恢复

#### Scenario: 删除单个任务不清理项目记忆
- **WHEN** 操作者删除一个产生过已批准记忆的任务
- **THEN** 系统不删除项目记忆及其脱敏来源快照，且不会触发项目级 purge

#### Scenario: 缺少可信身份或越项目 mutation
- **WHEN** mutation 请求没有 host 认证 principal、缺少当前动作 capability，或 principal 无权管理目标项目
- **THEN** 系统失败关闭、不改变任何 memory/head/revision，并记录脱敏拒绝审计

#### Scenario: Purge 权限高于普通治理
- **WHEN** 只有普通治理权限的操作者携带有效 purge 确认 token 请求物理清理
- **THEN** 系统仍拒绝操作，因为确认 token 不能替代独立的 purge capability

### Requirement: 外部记忆投影保持可选和可重建
本 capability SHALL 以 PostgreSQL retrieval 上线，并保持 OpenViking 项目记忆投影默认关闭。只有 PostgreSQL shadow 门禁通过并由独立 OpenSpec change 批准实现后，才可启用外部投影；届时 PostgreSQL SHALL 继续保存 canonical、desired 和 operation 状态，只有已批准的 ACTIVE detail 才能投影。远端 HTTP 2xx 或 task completed 不得单独推进本地已观察版本，未知远端结果 MUST 只读核验并禁止盲目重发。

#### Scenario: 当前 change 不启用远端投影
- **WHEN** `project-scoped-agent-memory` 完成但尚无后续投影 change 被批准和实现
- **THEN** 系统只使用 PostgreSQL 项目记忆检索，OpenViking memory outbox/worker 保持不存在或禁用

#### Scenario: 外部投影丢失后重建
- **WHEN** OpenViking 中的项目记忆 detail 被清空或发生漂移
- **THEN** 系统能从 PostgreSQL 的 ACTIVE revision 和来源状态重建投影，而不改变本地记忆身份或版本

#### Scenario: 远端结果未知
- **WHEN** 投影请求越过发送边界后连接中断且无法确认远端结果
- **THEN** operation 进入 UNKNOWN_REMOTE_RESULT 并通过只读核验收敛，禁止直接重发同一写入

### Requirement: Shadow 和上线门槛可量化
系统 SHALL 按项目记录项目记忆候选、排除、注入和回退结果，并至少评估跨项目泄漏、Recall/Precision@K、陈旧/冲突率、重复上下文率、来源覆盖率、abstention 质量、p95 延迟、examined-row count 和 token 占比。跨项目泄漏 MUST 为零；其余门槛 MUST 在 PRIMARY 切换前以冻结的项目数据集和基线记录。

#### Scenario: 指标满足后按项目切换
- **WHEN** 某项目在冻结数据集上满足全部安全门槛且相对 legacy 基线无不可接受回归
- **THEN** 操作者可以仅将该项目切换到 PRIMARY，并保留即时回滚能力

#### Scenario: 检测到跨项目候选
- **WHEN** shadow 审计发现任意不属于当前项目的候选进入排序或上下文
- **THEN** 系统把该结果判为门禁失败，阻止 PRIMARY 切换并产生高优先级告警
