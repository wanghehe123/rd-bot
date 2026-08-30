## Context

见 `proposal.md` 的动机与范围。本设计基于当前静态代码链路：`RequirementDeliveryEngine` 将生产角色执行委托给 `RequirementAgentStageOrchestrator`；stage run、event 和 immutable artifact 落在 PostgreSQL；阶段成功后 `captureExperience()` 独立写入 `WorkflowExperienceStore`；`ProjectScopedRequirementKnowledgeSearchAdapter` 把 legacy experience 作为独立证据通道加入检索；`RoleContextBuilder` 在有界字符预算内形成不可变 role context package。

现有 `rd_experience_entries` 以 `task_id` 为必填且随任务级联删除，保存随机生成的 experience ID，只能以 `reusable/failure/redacted` 和 project/repository 元数据过滤，缺少长期记忆需要的逻辑身份、revision、有效期、冲突状态、operation 幂等和恢复账本。因此执行证据、legacy experience 和项目记忆必须成为三个不同概念。

`docs/openspec/historical-spec-provenance-audit.md` 把 role context、项目检索和 lifecycle 相关历史文档归类为 `IMPLEMENTED_CLAIM`、`NEEDS_CAPABILITY_AUDIT` 或计划材料。本设计只把它们用于理解风险，不把历史声明当成当前实现证明。OpenViking 的当前强制边界来自 `RULE.md` 与 `docs/superpowers/specs/2026-08-13-openviking-projection-protocol-spec.md`：PostgreSQL 是 desired/observed 真相，外部索引可重建，越过发送边界的未知结果不得盲重放。

本轮规划只运行了 OpenSpec 状态命令和 `rg`/`sed` 静态检查，未运行 Maven、Node、PostgreSQL、HTTP 或浏览器验证。以下均为待实现设计。

### 实现验证（2026-08-30）

实现与验证已完成；详见 `verification-evidence.md`。真实 PostgreSQL 证据：`ProjectAgentMemoryPostgresRealSmokeTest`（task delete 来源保留、并发单 head、fencing settle）于 `127.0.0.1:55432/rdbot_acceptance` 执行通过。

### 理论来源与 RD-Bot 映射边界

用户指定的四层记忆来源可定位为 Codex task `codex://threads/01a0494c-0c3f-7830-ba06-7bd09b143233` 中引用的本地原文 `/Volumes/WishDisk/xiaoshuo/企业级 MultiAgent 的记忆系统：短期上下文与四层记忆架构实现｜得物技术.html`；补充理论材料为 `/Volumes/WishDisk/codes/ai-agent-book/book/chapter3.md`。原文主张四层分别服务不同生命周期：Working Memory 服务当前一步推理，Session Memory 保存会话历史，User Memory 保存跨 Agent 的用户偏好/稳定事实，Agent Memory 保存某个 Agent 的任务经验与协作约定；Session 结束后再筛选、去重并异步沉淀长期记忆。这是来源观点，不是 RD-Bot 当前实现证明。

本 change 的 RD-Bot 综合映射为：Working Memory 对应一次 Pi/role execution 的即时 Prompt 与 runtime context，因此继续保持临时且明确排除出持久记忆；Session Memory 对应 task、stage run、role context package、checkpoint 和 immutable artifact，继续由现有任务证据链负责；User Memory 涉及个人身份、同意、授权、删除和跨 Agent 共享，本期明确不建设；原文 Agent Memory 被收窄为 **Project Agent Memory**，只保存当前 RD 项目内经验证的 semantic/episodic/procedural 内容。该收窄是结合 RD-Bot 项目隔离、交付证据和治理要求做出的设计选择，不是原文结论。

## Goals / Non-Goals

**Goals:**

- 以真实 `RdProject` 作为每条新记忆的唯一安全边界。
- 保留 task/stage/artifact 作为事实证据，同时建立项目所有、可版本化、可冲突治理的长期记忆。
- 让记忆写入具备 at-least-once delivery 下的端到端幂等、CAS、lease/fencing 和崩溃恢复。
- 通过独立检索 channel 复用当前 retrieval/context/Pi 链路，并在迁移期与 legacy experience 共用预算和去重。
- 让管理员能查看、确认、纠正、失效、删除和按项目切换记忆读路径。
- 在不改变 PostgreSQL 真相边界的前提下，为后续 OpenViking detail projection 留出可验证扩展点。

**Non-Goals:**

- 不建设用户画像、个人偏好或跨租户 User Memory。
- 不新增默认跨项目或组织级经验共享；未来若需要必须另建 capability 和授权模型。
- 不把 Working Memory、Pi attempt state 或完整原始对话长期化。
- 不把 OpenViking、向量库、Redis 或 JVM cache 作为记忆真相源。
- 不改变当前四角色顺序、Pi-only 执行、任务状态机、审批、重试 checkpoint 或 QA 证据协议。
- 不在首个读路径中引入新的 embedding 模型；先用 PostgreSQL 结构化/词法候选和 shadow 数据确定必要性。

## Decisions

### 1. 新建 Project Memory Core，不扩展 `rd_experience_entries`

选择独立领域表和 Port，而不是让 `WorkflowExperienceEntry` 同时承担快照、候选、当前事实和治理状态。

原因：

- legacy record 强制非空 `taskId`，当前部分读取还需要回查来源任务；长期项目记忆不能依赖任务永久存在。
- `rd_experience_entries.task_id` 使用 `ON DELETE CASCADE`，与项目长期资产的所有权冲突。
- 迁移期必须同时读取旧经验和新记忆；把新数据继续写回旧表无法验证两者差异或快速回滚。

备选方案：直接给 `rd_experience_entries` 增列。它改动较少，但会固化错误所有权，并把写时状态机、不可变 revision 和检索文档混入同一行，否决。

### 2. 模块边界遵循现有依赖方向

- `rag.project.memory`：记忆模型、枚举、Store/Query Port、检索 request/result 和内存 contract 实现。`rag` 不调用模型或外部系统。
- `engine.project.memory`：candidate extraction 结果校验、resolver、consolidation、reconciliation、promotion policy 和 shadow evaluation 编排。
- `bootstrap.persistence`：PostgreSQL Row/Mapper/Store、事务和 claim 适配。
- `bootstrap.controller.admin.projectmemory`：只做请求校验、View 与异常翻译。
- `bootstrap` 调度配置：有界 worker、配置开关和生命周期管理。

`engine` 只依赖 `rag` Port；PostgreSQL、OpenViking 和 HTTP 类型不得泄漏到 `engine`/`rag`。

### 3. PostgreSQL 保存逻辑 head、不可变 revision、来源快照和 operation

新增独立迁移 `p19_project_agent_memory.sql`，不回写 `p1_multi_agent_orchestration.sql` 或其它历史文件。

#### `rd_project_memories`

保存稳定逻辑身份与当前 head：

- `id BIGINT`
- `project_id BIGINT REFERENCES rd_projects(id) ON DELETE RESTRICT`
- `scope_role VARCHAR(64)`，空表示项目通用，非空表示当前固定 Agent role
- `memory_type SEMANTIC|EPISODIC|PROCEDURAL`
- `logical_key`，由规范化主体/主题而不是整段 LLM 文本生成
- `head_revision_id`、`head_version`
- `row_version`，用于 CAS/fencing
- aggregate lifecycle（`ENABLED|DELETED`）、创建、更新时间和软删除信息

唯一约束覆盖 `(project_id, scope_role, memory_type, logical_key)`。`(id, head_revision_id)` 必须以复合外键引用同一 memory 的 revision，防止把其它逻辑记忆的 revision 设为 head；只有该行引用的 ACTIVE revision 可检索。

#### `rd_project_memory_revisions`

保存不可变版本：

- `memory_id`、`version` 唯一
- lifecycle status：`CANDIDATE|ACTIVE|SUPERSEDED|EXPIRED|REJECTED|QUARANTINED|DELETED`
- title、summary、`content_json`
- confidence、importance、evidence quality
- `valid_from/valid_to`
- `supersedes_revision_id`
- `created_by_operation_id`
- content hash、Schema version、创建时间

revision 内容只插入不覆盖；lifecycle transition 以 CAS 和审计事件原子表达。`supersedes_revision_id` 必须用 `(memory_id, supersedes_revision_id)` 复合外键引用同一 memory；新 ACTIVE、旧 SUPERSEDED、head CAS 与 operation settle 在一个事务完成。QUARANTINED 竞争 revision 可以与当前 ACTIVE head 并存，但永远不成为可检索 head，直到显式冲突裁决创建新的 transition/revision。

#### `rd_project_memory_sources`

保存 revision 的可审计来源：

- `task_id/stage_run_id/artifact_id` 使用可空 FK 和 `ON DELETE SET NULL`
- 永久保存 project、source URI、source content hash、repository revision、extractor version、Schema version 和有界脱敏摘要
- 禁止保存凭证、原始私有 Pi event 或未受预算约束的完整工具输出

即使 task/artifact 后续删除，来源快照仍可证明“记忆从何而来”，但不得伪装成原始证据仍然可用。

#### `rd_project_memory_operations`

保存可靠异步巩固：

- deterministic operation key：对带字段名和长度前缀的 canonical byte encoding 计算 SHA-256；输入包含 operation kind、project ID、source kind/source identity、source content hash、extractor version 和 Schema version
- status、attempt/max-attempts、lease owner/until、fencing token、row version、next visible、checkpoint、last error
- unique operation key 吸收重复 stage completion；stage 来源使用 stageRunId，legacy 使用 legacy experience ID，人工治理使用 host mutation request ID，均不得以空值占位或字符串直接拼接
- claim 使用 `FOR UPDATE SKIP LOCKED`
- settle 必须匹配 operation ID、owner、fencing、row version 且 lease 未过期

operation insert 使用 `INSERT ... ON CONFLICT ... RETURNING` 或等价读回，而不是把 `DO NOTHING` 当成功。冲突后必须核对 project、kind、source identity/hash、extractor/Schema version 等不可变字段；同 key 不同 payload 时 fail closed、写安全审计且不执行既有 operation。

另建 `rd_project_memory_legacy_links` 保存 legacy experience ID 到 candidate/revision 的迁移关系和判定结果。

### 4. 保留字符串 API ID，但新表只接受可验证 BIGINT 项目 FK

Java/API 继续使用现有 String project ID，Port 边界必须通过与 `PostgresPersistenceSupport.parseId` 等价的严格解析确认其对应 `rd_projects.id`。新记忆表使用 BIGINT FK。历史 task 中空白、非数字、project/repository 冲突的身份只能进入迁移 quarantine；不得借机在本 change 内迁移整个 `rd_tasks.project_id` 类型。

### 5. 最终化事务只登记 operation，异步工作不阻塞交付

阶段最终化的 PostgreSQL 事务在保存权威 task/stage/command 结果时，按上述确定性键登记或校验 memory operation。即使 capture policy 为 OFF，也登记不含记忆内容的 `SKIPPED_BY_POLICY` 终态 marker，以证明该终态证据已被策略处理；OFF 不运行 extractor，也不会在未来自动回放旧 marker。事务提交后：

```text
terminal artifact
  → operation claim
  → extractor（候选结构）
  → deterministic validator/redactor
  → resolver（ADD/UPDATE/SUPERSEDE/NOOP/QUARANTINE）
  → revision + source + head CAS + operation settle（单事务）
```

worker、LLM、索引或投影失败不会回滚已经提交的交付；operation 保持 PENDING/RETRYABLE/NEEDS_HUMAN。reconciliation 只扫描“具备终态 artifact、但不存在对应 deterministic operation”的窗口并补登记，不重新执行交付。

备选方案：保留当前 `captureExperience()` 告警式 best effort。它无法发现崩溃窗口，也无法吸收重复，否决。

### 6. 激活策略以完整交付证据为门槛

- 各阶段成功结果可产生 CANDIDATE，但不直接激活。
- SEMANTIC：需要完成 delivery review，或管理员确认其为当前项目事实。
- EPISODIC：成功交付事件可自动激活；失败事件必须关联权威 failure provenance，且默认不参与程序建议。
- PROCEDURAL：必须同时关联完整交付成功、QA 成功和适用 role；人工确认可作为明确替代路径。
- CODE_CHANGE 内容保持证据或情景记忆，不能直接变成“以后都这样做”的程序规则。

任何 extractor 输出先通过 host-owned Schema、长度、敏感字段和来源校验。LLM 不决定 project scope、授权、最终状态或 source identity。

### 7. Resolver 使用稳定逻辑身份、CAS 和冲突隔离

resolver 在 project + role + type + logical key 下读取当前 head：

- 无 head 且证据充分：ADD。
- 内容等价：NOOP，并补来源引用而不创建重复 revision。
- 新事实明确取代旧事实：UPDATE/SUPERSEDE，先插 revision，再 CAS 推进 head。
- 历史事件可并存：创建不同 logical key/episode，不把时间变化误判为冲突。
- 证据相互矛盾且不能自动裁决：写入 QUARANTINED candidate/revision，不改变 ACTIVE head。

若 CAS 失败，worker 必须重读一次并重新决策；仍冲突则隔离。禁止无限自旋或 last-write-wins。

### 8. 新建独立 `ProjectMemoryRetrievalChannel`

新通道通过 `ProjectMemorySearchPort` 查询 PostgreSQL，先执行索引谓词：

- 精确 project FK
- scope role
- ACTIVE head
- valid time
- 已脱敏、最低 confidence/evidence quality
- 有界 candidate limit

候选排序组合 lexical match、role/type applicability、recency/temporal fit、importance、confidence、source quality 和 stale/conflict penalty。首版不依赖 embedding；所有权重和上限由配置冻结并进入 retrieval audit。

返回 `RoleContextEvidence`，source URI 使用：

```text
rd-memory://projects/{projectId}/memories/{memoryId}/revisions/{version}
```

`ProjectScopedRequirementKnowledgeSearchAdapter` 或其上层 channel aggregator 负责把 knowledge、project memory 和 legacy experience 合并。新旧历史经验共同占当前历史经验配额（初始不高于总 topK 的 30%），先按 source artifact/hash、content hash、logical identity 去重，再交给 `RoleContextBuilder` 受总字符预算约束。

旧 `RequirementDeliveryEngine.materialsWithReusableExperience()` 和 orchestrator legacy 预取必须在 cutover 前收敛到同一 scoped aggregator，避免绕过项目硬过滤和重复注入。

### 9. Prompt 采用宿主拥有的不可授权数据边界

context assembler 把项目记忆放入独立、带版本的 `UNTRUSTED_PROJECT_MEMORY` block，保留 memory/revision/source hash，但不把其中内容拼接为系统指令。role contract 同时声明优先级：

```text
系统/RULE/授权与工具策略
> 当前用户请求与验收标准
> 当前数据库、代码、运行时和仓库证据
> ACTIVE 项目记忆
> legacy workflow experience
```

memory 内容不得修改工具 allowlist、credential relay、网络策略、审批或 QA 规则；不得单独满足 acceptance。若与当前证据冲突，retrieval audit 记录 stale/conflict，并触发候选重新巩固，而不是让模型自行选择。

### 10. 捕获与读取模式分离，并提供按项目策略档位

为每个项目独立保存 `capture_mode=OFF|SHADOW|ACTIVE` 和 `read_mode=LEGACY|SHADOW|DUAL|PRIMARY`。管理面提供兼容的策略档位：

- OFF：`capture=OFF, read=LEGACY`；最终化仍登记 `SKIPPED_BY_POLICY` marker，但不提取或读取新记忆。
- SHADOW：`capture=SHADOW, read=SHADOW`；可靠生成候选并执行检索，但不注入 Prompt，只记录对比。
- DUAL_READ：`capture=ACTIVE, read=DUAL`；新旧通道共同参与同一预算与去重。
- PRIMARY：`capture=ACTIVE, read=PRIMARY`；新记忆为主，legacy 只在明确回退条件下补充或完全关闭。

模式改变只影响之后的读取/捕获，不删除数据。回滚到 legacy 不需要 schema rollback。显式 legacy inventory/backfill 是唯一允许处理历史 `SKIPPED_BY_POLICY` 来源的入口，且必须使用新的确定性 operation identity。

### 11. Legacy 迁移先清点和隔离，不直接批量激活

迁移作业按项目输出：eligible、duplicate、ambiguous、rejected。只有 `reusable=true`、`failure=false`、`redacted=true`、项目明确、来源可核验且完整交付成功的条目才可进入 extractor；其余保留 legacy 或 quarantine。相同 source/hash/逻辑身份先聚合，再创建 candidate/revision。每行保存判定原因和 legacy link，批次可重复运行。

PRIMARY 切换前必须完成 shadow 数据集与回滚演练；任何项目可独立切换，禁止全库一次性 cutover。

### 12. 管理治理不把任务删除当作记忆删除

管理 API/页面按项目展示 head、revision、来源、状态、适用 role、最近 retrieval audit 和迁移判定。确认/纠正/失效/软删除使用 expected row version；版本冲突返回 409，不覆盖他人操作。

所有 mutation 必须由 host 注入不可伪造的 operator principal，并经过 fail-closed `ProjectMemoryMutationAuthorizer`：普通治理需要 `PROJECT_MEMORY_GOVERN`，物理 purge 另需更高权限 `PROJECT_MEMORY_PURGE`。请求体中的 actor 字段不能作为身份来源；每次允许或拒绝都记录 operator、project、action、request ID、原因、前后 revision/row version 和时间。若当前部署没有可信认证上下文，mutation endpoints 必须保持禁用，仅开放按现有 admin 边界保护的只读查询；跨项目 principal 一律拒绝。

项目禁用或逻辑删除时，自动捕获和检索停止，数据保持只读审计。物理 purge 是独立项目级命令，需要 `PROJECT_MEMORY_PURGE`、显式操作者、原因、预计行数、短期有效确认 token 和审计记录；确认 token 只是防误操作机制，不能替代认证授权。不得由 task DELETE 或 project 普通软删除隐式触发。

### 13. 本 change 只冻结 OpenViking 后续边界

本 change 的实现范围止于 PostgreSQL retrieval、投影 SPI/配置边界和默认关闭测试，不创建 OpenViking memory outbox、binding、adapter 或线上任务。只有本地跨项目泄漏为零且质量/延迟/成本门槛通过后，才新建独立 OpenSpec change 设计 ACTIVE detail 投影。未来投影必须使用独立 owned root，不复用知识文档的业务身份，也不把候选/QUARANTINED 内容投影。

外部投影沿用现有原则：PostgreSQL desired/observed 真相、submit 前持久化发送标记、`UNKNOWN_REMOTE_RESULT` 只读核验、版本/owner/checksum 核验后才推进 observed version、可从本地重建。OpenViking 不参与 memory resolver 的事实裁决。

### 14. Shadow 门禁与验证基线

每项目冻结数据集和 legacy baseline，至少记录：

- cross-project leakage（必须为 0）
- Recall@K、Precision@K
- stale/conflict rate、abstention quality
- duplicate-context rate、source citation coverage
- p95、examined-row count、token share
- 相对 legacy 的通过/失败回归

门禁结果及阈值版本化，PRIMARY 切换绑定具体 gate revision。静态单测不能替代真实 PostgreSQL 并发/崩溃、HTTP 管理面和最终 Pi context 验证。

## Risks / Trade-offs

- [新核心引入表和双读复杂度] → 先 OFF/SHADOW，按项目 canary；legacy 保持可用并提供即时回滚。
- [最终化事务新增 operation 写入可能放大数据库故障] → operation insert 使用确定性键和同库短 SQL，不调用模型/外部系统；数据库不可用本来就无法提交权威 stage 状态。
- [错误候选被自动提升] → 激活绑定完整 delivery/QA 或人工确认；失败输出不自动成为 PROCEDURAL。
- [并发 resolver 产生双 head] → 唯一逻辑身份、row-version CAS、单事务 head 推进；二次冲突进入 QUARANTINED。
- [任务删除导致来源不可核验] → 独立保存有界脱敏来源快照和 hash，FK 删除后置空且标记原始实体不可用。
- [Prompt injection 或陈旧记忆影响执行] → host-owned untrusted block、固定优先级、工具/授权不受 memory 控制、与当前证据冲突时降权/隔离。
- [PostgreSQL lexical 检索对中文和大规模历史不足] → 先用索引谓词限制候选并收集 examined-row/quality 数据；只有指标证明必要时才增加可重建向量投影。
- [治理面 purge 难以恢复] → 与软删除分开、显式预览范围和审计；具体确认交互在实现前按破坏性操作规则再次评审。
- [OpenViking 投影复制知识协议可能造成耦合] → 延后到独立阶段，仅复用可靠投影原则，不复用知识文档业务真相或 URI 身份。

## Migration Plan

1. **Schema dormant**：部署新表、Port 和配置，所有项目默认 OFF；运行 SQL policy、store contract、FK/CAS/idempotency 测试。
2. **Durable capture**：最终化事务登记 operation，worker/reconciliation 运行但只生成 CANDIDATE；故障注入证明重复、崩溃和 lease fencing。
3. **Shadow retrieval**：按少量项目启用 SHADOW，建立冻结数据集并与 legacy 比较，不改变 Prompt。
4. **Legacy inventory**：清点、hash、分组和 quarantine；保存 legacy link，不批量激活模糊记录。
5. **DUAL_READ canary**：对达标项目启用双读，共享配额和去重；验证实际 role context、Pi input manifest 与检索审计。
6. **PRIMARY cutover**：逐项目绑定 gate revision 切换；保留一键回到 legacy 的配置，不删除 legacy 表。
7. **Governance**：开放确认、纠正、失效、软删除和审计 UI；purge 仍保持独立高风险操作。
8. **Projection follow-up decision**：只有 PostgreSQL shadow 门禁稳定后，才允许创建独立 OpenSpec change 设计/实现 OpenViking detail projection；本 change 不启用远端投影。

回滚：任一阶段先把项目模式切回 OFF 或 legacy；停止 worker 后保留 operation 和 revision 供诊断。数据库迁移不做破坏性 down migration，旧 `rd_experience_entries` 在整个 change 中保留。

## Open Questions

- 首个 canary 项目和冻结评测数据集由实施前的项目清点选择；这不改变架构、spec 或任务顺序。
- PostgreSQL 首版 lexical ranking 的具体权重和 candidate limit 由 shadow 基线冻结；所有权重必须版本化并可回滚。
- OpenViking detail projection 的独立 owned-root 名称在 P5 开始前按当前真实合同测试确定，不在本设计中猜测 URI。
