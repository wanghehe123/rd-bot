# MEA 后端剩余阶段与 Coding 追踪读取实施计划

> **For agentic workers:** 使用 `superpowers:executing-plans` 按独立批次实施；代码探索使用 Luna。步骤以 `- [ ]` 跟踪。本文件是计划，不能据它把未运行的测试、未部署的版本或未完成的真机任务写成成功。

**Goal:** 先验证当前 P3 修复的真实运行闭环，补齐 Coding 内 MEA 与角色完整产物读取，再按完整方案推进基线、Fresh Executor、稳定契约、预算恢复、审计记忆与模型路由。

**Architecture:** 保持 PostgreSQL 状态/command/审计/发布账本为真值；Manager 仍是 Host 纯策略，Executor 结果仍为声明，只有 Host 审计写回可晋升验收记录。只读展示与业务治理使用独立 change/分支；前者查询既有数据，后者逐阶段修改已有执行与事务边界，不新建第二套调度器。

**Tech Stack:** Java 21、现有 Spring Boot 多模块、PostgreSQL、Pi Docker/credential-relay、OpenSpec、现有需求 HTTP API、waimai 独立验收脚本。

**Spec:** `docs/superpowers/plans/2026-09-04-mea-next-phases-waimai.md` 为完整任务范围；`docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md` 的 K5–K8、阶段 0–7、§6.4 为已冻结决策/阈值。现有行为以 `RULE.md` 与当前代码、`openspec/specs/requirement/{delivery-platform,audit-only-writeback,manager-decision-command}/spec.md` 为准。前端合同见同目录 `2026-09-06-mea-task-workbench-frontend-plan.md`。

## Global Constraints

- 用户定义：四角色表示完整交付流程；MEA 是 Coding 执行过程的定义。本计划不把 Reviewer/Architect 全流程重写成一个顶层 Executor。
- 用户选择顺序 A：先列新版 package/部署验证、W1g 完整交付、P3-W2 ASK 与 P3-W3 pause 真机，再推进后续阶段。本轮只编写计划，没有执行这些动作。
- P3-W1 已由 W1e `7502196308401328128` 满足原 Task 11 退出条件。W1g 是当前修复版本的端到端验证，不是追改 P3-W1 的退出定义。
- W1e 最终因 `successful stage missing: QA_AGENT` 恢复失败；不把其三 AC 通过说成任务 `COMPLETED`。W1 PR #38 仅证明普通 3.0 流程。
- 固定四个 Agent role：`REQUIREMENT_REVIEWER`、`SOLUTION_ARCHITECT`、`CODING_AGENT`、`QA_AGENT`。Manager、Host Verify、DeterministicAuditor 不加入该枚举。
- Coding 成功只续 `HOST_VERIFY`；其成功与协议有效 QA 后才续 `MANAGER_DECIDE:<sourceCommandId>`。`MANAGER_GAP_FIX` 使用现有世代身份和有界合同。
- 同一 remediation round 跨 Coding→Host Verify→Manager→QA；QA 后下一 Manager 退出该世代。Manager round、remediation round、stage attempt、command retry attempt 不混用。
- `WAITING_USER_INPUT` 不复用 `WAITING_APPROVAL`；`paused=true` 不 claim，包括 answer resume。已有合法状态图与完成写入者集合不绕过。
- `AuditedTaskState` 只通过冻结 plan 在 `recordOutcome/finalize` 写回；默认 `ENFORCE`；`unaudited_claim_promoted_to_completed=0`。
- 变更 prompt/QA 结果协议时同步 Host、`result-tool.mjs` 与角色合同，按 AGENTS 重建两个 Pi 镜像。纯读取/UI 变更不重建镜像。
- 外部副作用必须沿用 operation ID 与账本 reconcile；不可因 HTTP 超时就推断远端没有发生。
- 不恢复下线的 BugFix、Coding Benchmark/评测控制台、仅模型 HTTP 或 Claude 执行路径。基线用独立脚本和需求 API。
- 每个行为变化新建 OpenSpec delta；不直接编辑主 spec、不重开已落地 P3 架构。归档须匹配真实实施与验证，且遵循实施会话授权。
- 当前 AGENTS 引用的 `model-escalation` skill 在本轮检索路径中未找到。本文沿用既有冻结决策；涉及新增 schema、版本并发、回执恢复和模型协议的实现任务，开工时须重新检查该 skill，并完成相应架构/事务审查，记录实际审查方式与缺失情况，不谎称 advisor 已复核。

## 1. 当前基线、来源与证据边界

### 1.1 本轮已核对

规划基线为 `main` / `3fcd7db65cb7a3aabace2d04ffa5d30fdf69e714`。P3 代码已提交。以下归档资料在共享工作区存在，但规划时尚未提交：

- `openspec/changes/archive/2026-09-06-mea-manager-decision-command/`
- `openspec/specs/requirement/manager-decision-command/spec.md`
- `docs/superpowers/qa/2026-09-06-mea-p3-manager-handoff.md` 等三份交接/计划修订。

它们由其他任务产生，本轮没有修改。实施分支从可追溯提交创建，不自动夹带这些 patch。交接中“VM 仍为 12:26 jar”是历史记录，本轮未连接云端，实际部署 SHA、jar hash、镜像 digest 均由 B01 重新采集。

### 1.2 来源分级

| 来源 | 等级与用途 |
| --- | --- |
| 当前 RULE / 主 spec | 强制合同；旧 audit spec 的 Host→QA 描述须与更新的 Manager delta/RULE 一起读 |
| 完整计划 Task 9–15、总体方案阶段卡 | `PLAN_OR_DECISION`；保留范围与退出条件，不复用其已过时“当前代码没有…”判断 |
| P3 handoff 与 W1e evidence JSON | 历史实际运行证据/实现声明，严格按 task/version 范围使用 |
| 本轮三位 Luna 源码及测试源码追踪 | 确认当前入口和已有能力；本轮没跑业务测试，不提升成新的 `VERIFIED_CURRENT` |
| `docs/openspec/historical-spec-provenance-audit.md` | 采用其来源分级方法；其旧 capability 数量不是当前清单 |
| 本计划所有新 API、类型、表与测试 | 拟实施合同，不能作为“已经存在”的证明 |

### 1.3 范围覆盖

| 原计划 | 本次处理 |
| --- | --- |
| Tasks 1–8 / Phase 1 | 保留已接线/ENFORCE 的既有成果；B00/B01核验，不从旧 header 从零重做 |
| Task 9 / Phase 0 | B07–B08，独立基线脚本与预注册阈值 |
| Task 10 / Phase 2 | B09–B11，按当前源码差异补 Fresh/信任标签/预算/隔离与真机，不重复实现已经存在的部分 |
| Task 11 / Phase 3 | B01–B03补当前版本真机；保留 W1e 的原退出结论 |
| Task 12 / Phase 4 | B12–B15，稳定契约、动态材料版本、输入白名单、ART-PR |
| Task 13 / Phase 5 | B16–B19，预算、无进展、执行回执、故障矩阵与发布回放 |
| Task 14 / Phase 6 | B20–B22，审计晋升、生产接线、检索信任与真机 |
| Task 15 / Phase 7 | B23–B24，已有路由上的策略与可比较验收 |
| 总体方案§7观测协调 | B08统一成功口径，供后续基线与成本比较使用 |
| 本次新增展示需求 | B04–B06，Coding MEA只读快照与完整角色结果；独立于后续治理发布 |

## 2. 分支、依赖与交付顺序

| 批次/分支 | OpenSpec change | 开工依赖 | 交付目标 |
| --- | --- | --- | --- |
| `codex/mea-p3-live-closeout` | `mea-p3-live-closeout` | 包含3fcd7db6的干净基线 | B00–B03：新版真机及必要的最小修复，不重做P3 |
| `codex/mea-coding-read-model` | `mea-coding-read-model` | B00合同可先准备；B03后联调 | B04–B06：前端只读依赖，可先于长阶段合入 |
| `codex/mea-baseline-evaluation` | `mea-baseline-evaluation` | B03；首轮比较前冻结阈值 | B07及B08脚本部分 |
| `codex/mea-delivery-outcome-metrics` | `mea-delivery-outcome-metrics` | 先核验既有观测主spec/归档 | B08生产指标口径；不恢复评测UI |
| `codex/mea-fresh-executor-episode` | `mea-fresh-executor-episode` | Phase1 ENFORCE证据+B03 | B09–B11 |
| `codex/mea-stable-contract-and-backcheck` | `mea-stable-contract-and-backcheck` | B11；P3 Manager | B12–B15 |
| `codex/mea-budget-and-recovery-governance` | `mea-budget-and-recovery-governance` | B15；预算与契约版本字段冻结 | B16–B19 |
| `codex/mea-audited-memory-promotion` | `mea-audited-memory-promotion` | B19；Auditor写回稳定 | B20–B22 |
| `codex/mea-role-model-routing` | `mea-role-model-routing` | B08/B11/B15/B19/B22证据齐全 | B23–B24 |

前端分支为 `codex/mea-task-workbench`，仅与读取分支通过合同交接。不要建一个长期混合前后端/所有阶段的大分支。每一批从含其依赖的已验证提交创建，记录 base SHA、依赖合入 SHA、目标 change、独立验收报告。是否 commit/push/merge 由实施会话授权决定，本文件不执行 Git 写操作。

工作区规则：独立 worktree；后端不提交前端 build 输出；两端若需全量测试，先记录静态目录基线并把生成资产交给前端 owner。新增 SQL migration 文件按实施时现有序号分配，不能盲占旧计划中的 p23；本文指定表/约束与命名后缀，B12/B16任务负责将分配出的真实文件名写入 change。

## 3. 已有链路与文件地图

以下均为当前存在的源码锚点；表后新增文件明确标为新建。

| 路径 | 关键符号/用途 |
| --- | --- |
| `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java` | `planStage`、`planRoleExecutionStage`、`planHostVerifyStage`、`planManagerDecisionStage`、`buildManagerGapFixIntent`、`ensureFreshQaAttemptForManagerGapFix` |
| `engine/src/main/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestrator.java` | `runInternal`、角色合同、前次失败/compact handoff构造 |
| `engine/src/main/java/com/wish/rd/engine/requirement/manager/ManagerPolicy.java` | Host纯决策；不依赖执行器/工作区 |
| `engine/src/main/java/com/wish/rd/engine/requirement/manager/ManagerDecisionStore.java` | 已有 `listByTask/findLatest/findBySourceCommand`，不重复新造Manager store |
| `engine/src/main/java/com/wish/rd/engine/requirement/job/RequirementStageCommandStore.java` | 现有身份查询；当前无task分页列表 |
| `engine/src/main/java/com/wish/rd/engine/agent/AgentStageRunStore.java` | `listByTask/findById`，实际角色Attempt |
| `engine/src/main/java/com/wish/rd/engine/agent/AgentStageArtifactStore.java` | 精确task/stage/type产物查询；防整任务扫描 |
| `engine/src/main/java/com/wish/rd/engine/requirement/verify/HostVerificationStore.java` | `listByTask/find/listSteps/listArtifacts` |
| `engine/src/main/java/com/wish/rd/engine/requirement/audit/AuditedTaskStateStore.java` | head、AuditRun、completion binding；历史revision读取需核对/扩展 |
| `engine/src/main/java/com/wish/rd/engine/requirement/audit/DeterministicAuditor.java` | `auditQaWithClaims` 等唯一审计变更生成 |
| `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresManagerDecisionStore.java` | 决策持久化；领域ManagerDecision目前不携带createdAt |
| `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskManagerDecisionMapper.java` | 真实决策行与创建时间 |
| `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementStageCommandStore.java` | command适配 |
| `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageCommandMapper.java` | task/paused/waiting claim SQL及command读取 |
| `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresAuditedTaskStateStore.java` | 审计revision/head |
| `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskAuditedStateMapper.java` | 审计持久化读取 |
| `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskRemediationController.java` | 已有`/remediations`，不是Manager决策列表 |
| `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskAuditedStateController.java` | `/audited-state`与`/audit-runs` |
| `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskExecutionOverviewController.java` | 四角色stage及事件读取，`resultPreview`不等于完整结果 |
| `engine/src/main/java/com/wish/rd/engine/requirement/review/impl/AiReviewPackageBuilder.java` | 当前复核输入收集入口；Phase4白名单重点 |
| `deploy/cloud-server/start-backend.sh` | 既有云端启动/重启方式 |
| `deploy/cloud-server/run-codex-memory-live-task.py` | 复用需求提交/证据导出方式；不盲用硬编码任务标题 |

精确追踪主线：`RequirementDeliveryDispatchService.submit → EngineRequirementStageExecutor.plan → RequirementDeliveryEngine.planStage → DeterministicAuditor → RequirementStageFinalizationPort.recordOutcome/finalize`。修改Pi诊断时再补 `execution-profile resolver → DockerPiAgentExecutor → rd-pi-bridge.mjs`。

## 4. 前后端共用读取合同（拟新增）

### 4.1 Coding MEA路由与范围

```http
GET /admin/rd-tasks/{taskId}/coding-mea?codingStageRunId={id}&limit=50&cursor={opaque}
```

- `codingStageRunId` 可省略：选择该task最新真实Coding stage；不存在则返回 `available=false` 与 `NO_CODING_STAGE`，不创建记录。
- 显式ID必须属于该task且role为CODING_AGENT，否则404；历史BugFix只读且不伪装需求MEA。
- `limit` 默认50，范围1–100；cursor为服务端不透明分页位置，绑定task和选中Coding identity，跨任务/选择复用返回400。
- 输出局部Coding因果邻域与历史页，不输出全量Prompt、result、stderr或AGENT_EVENTS。通过references引导用户按需读取。
- 只读聚合查询在一个PostgreSQL只读快照内执行。拟新增适配器使用 `@Transactional(readOnly=true, isolation=REPEATABLE_READ)`，类不为final；不跨进程远程调用、不获取调度写锁。
- 不新建表、不缓存第二份业务真值。分页以已有command稳定排序键为依据，关系仍依靠显式ID。

### 4.2 JSON字段合同

以下 TypeScript 用于跨端对齐，均为**拟新增**。ID用字符串，时间用epochMillis；状态值沿用相应领域枚举，不将它们统一成一个虚构状态。

```ts
type CodingMeaQuery = {
  codingStageRunId?: string
  limit?: number
  cursor?: string
}
type StateSlice = {
  available: boolean
  unavailableReason: string | null
  stateVersion: number | null
  stateHash: string | null
  records: AuditedRecord[] // 与现有管理API字段一致
  recordsTruncated: boolean
}
type CodingMeaResponse = {
  schemaVersion: 1
  taskId: string
  codingStageRunId: string | null
  available: boolean
  unavailableReason: string | null
  snapshotReadAtEpochMillis: number
  taskVersion: number
  taskStatus: string
  paused: boolean
  head: StateSlice
  codingStages: StageReference[]
  qaStages: StageReference[]
  commands: CommandReference[]
  decisions: DecisionReference[]
  remediations: RemediationReference[]
  hostVerifications: HostVerificationReference[]
  auditRuns: AuditedTaskAuditRun[] // 复用既有审计DTO
  links: MeaLink[]
  page: { hasMore: boolean; nextCursor: string | null }
}
type StageReference = {
  stageRunId: string
  role: string
  attemptNo: number
  status: string
  resultArtifactId: string | null
  startedAtEpochMillis: number | null
  finishedAtEpochMillis: number | null
}
type CommandReference = {
  commandId: string
  stage: string
  role: string
  status: string
  stageRunId: string | null
  stageLinkReason: string | null
  commandAttemptNo: number
  remediationRoundId: string | null
  remediationKind: string | null
  remediationNo: number | null
  remediationSourceStageRunId: string | null
  createdAtEpochMillis: number
  updatedAtEpochMillis: number
}
type DecisionReference = {
  managerCommandId: string | null
  roundNo: number
  sourceCommandId: string
  decisionHash: string
  route: string
  executorRoute: string | null
  targetRecordIds: string[]
  boundedContractPreview: string
  boundedContractTruncated: boolean
  rationale: string
  stateVersion: number
  stateHash: string
  stateAtDecision: StateSlice
  commandCreatedAtEpochMillis: number | null
}
type RemediationReference = {
  roundId: string
  kind: string
  remediationNo: number
  sourceStageRunId: string | null
  targetCodingStageRunId: string | null
  targetQaStageRunId: string | null
  firstCommandId: string | null
  status: string
}
type HostVerificationReference = {
  runId: string
  codingStageRunId: string
  parentRunId: string | null
  status: string
  docsOnly: boolean
  failureCategory: string | null
}
type MeaLink = {
  fromType: 'COMMAND' | 'STAGE' | 'DECISION' | 'HOST_VERIFY' | 'AUDIT'
  fromId: string
  toType: 'COMMAND' | 'STAGE' | 'DECISION' | 'HOST_VERIFY' | 'AUDIT'
  toId: string | null
  relation: string
  available: boolean
  unavailableReason: string | null
}
```

DTO复用边界：`AuditedRecord/AuditedTaskAuditRun` 字段以现有 `/audited-state`、`/audit-runs` 为准，包含证据URI/hash和来源identity，绝不夹带原始执行JSON。列表中的decision identity使用 `decisionHash`；link relation固定为 `DECIDED_AFTER`、`EXECUTES`、`VERIFIES`、`AUDITS`、`CONTINUES_AS`，先在B04的fixture/schema中枚举锁定。

### 4.3 关联、历史与截断语义

1. Manager读端已有 `listByTask`，复用它。ManagerDecision没有领域createdAt，本期只返回已关联Manager command的创建时间，文案必须称“决策命令创建时间”；不从roundNo推算时间，也不为展示改动决策hash/写入合同。
2. `ManagerDecision.sourceCommandId` 是来源command，不是Manager command自身。Manager command通过其完整stage身份和相同task/世代查询验证，不能当两个字段相同。
3. Coding/QA绑定使用审计 `commandId + subjectStageRunId`、retry binding、remediation目标等既有持久化身份。`RequirementStageCommand.attemptNo` 是命令执行重试次数，不能当Agent Attempt号。无法唯一绑定时返回null与原因。
4. 普通首次Coding没有前置Manager是正常情况；QA后普通Manager退出旧remediation也是正常情况，不补造round或关联。
5. `head` 是快照时的最新状态；`stateAtDecision` 按该decision的stateVersion/stateHash读取revision。历史版本缺失时 `available=false`，禁止用当前head顶替。
6. 每个state slice最多返回128条、优先阻断记录/目标AC，超出置 `recordsTruncated=true`；前端完整核验继续走已有审计页。部分列表不能推出“全部完成”。
7. bounded contract预览上限2000字符，超出明确标记；完整合同通过新增 `GET /admin/rd-tasks/{taskId}/manager-decisions/{decisionHash}` 按需返回（同归属校验、无secret）。其响应为该decision的既有领域字段，保留实际全文，不从Prompt重建。
8. 分页不能截断单条对象。通过页内引用指向页外节点时保留ID并标 `OUTSIDE_PAGE`；不因页外就判断该节点不存在。
9. task、stage、command、decision、verification、AuditRun、产物均双向校验归属。用户给出的URI只能经已有证据reader处理，不直接HTTP请求任意URI。
10. 查询不调用finalize、ManagerPolicy.decide、executor、publication或任何更新端口；API缺数据不触发补跑。

### 4.4 完整角色结果读取

本轮已核对：没有按task/stage读取完整RESULT_JSON的现成接口。`AgentStageArtifact`只保留最多20,000字符的contentPreview，`rd-agent-stage://`不是可用的通用对象存储下载地址。本change新增：

```http
GET /admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/result
```

响应合同：`taskId, stageRunId, role, attemptNo, artifactId, commandId, finalizationId, source, available, unavailableReason, contentType, content, truncated, contentSha256, downloadPath`。`source` 为 `FINALIZATION_RESULT|ARTIFACT_PREVIEW|UNAVAILABLE`；下载地址为本任务受控API路径或null。

实现完整数据链是本任务的一部分：

1. 通过精确AuditRun的 `commandId + subjectStageRunId`、retry binding或已有durable identity找到该stage对应的command。不能按角色、command retry次数或时间猜测。
2. 新增已持久化stage结果查询Port/mapper，从 `rd_requirement_stage_finalizations.result_json` 读取匹配command及已验证stage身份的结果，明确关联最终应用的finalization/plan digest；不使用只服务恢复的 `findLatestPrepared(commandId)` 代替历史结果查询。
3. finalization可能包含聚合输出，必须按命令主体提取该角色结果；QA复用既有authoritative QA解包规则，不把Coding根对象当QA结果。无法证明主体一致时返回不可用，不选“最后一个同名角色”。
4. 预览默认最多20,000字符，`truncated`准确。完整内容有真实finalization来源时，`GET /admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/result/content` 返回脱敏后的完整角色JSON，流式输出、Content-Type/Disposition正确；每次重新验证归属。`contentSha256`是本次可读内容hash，不能冒充原始未脱敏artifact hash。
5. 历史记录只有artifact preview时保留 `source=ARTIFACT_PREVIEW`、`unavailableReason=FULL_RESULT_NOT_PERSISTED_OR_NOT_BOUND`、downloadPath=null；不要添加假的完整下载或重跑角色恢复内容。

别人的stage404；当前尚无结果200 available=false；存储失败保留既有错误响应，不伪装空成功。本读取不更改旧stage/command绑定，不为历史页面做数据库回填。若未来需要对所有失败/未finalized角色保存完整输出，应独立扩展产物持久化合同，不偷偷加入本只读变更。

## 5. 第一批：运行验证与展示依赖

### B00：冻结当前版本与工作边界

**Files:** 新建 `openspec/changes/mea-p3-live-closeout/{proposal.md,design.md,tasks.md}`；验收报告 `docs/superpowers/qa/2026-09-06-mea-p3-live-closeout.md`。

- [ ] 读取RULE、当前P3主spec/归档、完整计划Task11、handoff和W1e JSON；记录各自来源分级。
- [ ] 保存main SHA与未提交文件清单；从可追溯提交建立独立分支/worktree，不接管P3归档。
- [ ] 在change中固定W1g、W2、W3探针输入、唯一marker、成功与失败判断，保留P3-W1原退出结论。
- [ ] 记录当前云端运行任务清单和部署owner，安排有界验证窗口；不得为本轮验证中断无关真实任务。
- [ ] 将本批“计划的命令”和“实际跑过的命令”分栏。核对只读历史证据不计入新版通过。

**退出：** 后续任务能准确指出验证的是哪个jar/镜像/项目/输入；无含糊“最新环境”。

### B01：验证本机修复并部署同一构建物

**Files:** 聚焦现有 `RequirementDeliveryEngine`、`RequirementAgentStageOrchestrator`、`PiQaRemediationPlanner`、`DockerPiAgentExecutor`、`RepairWorkspaceFactory` 测试；部署复用 `deploy/cloud-server/start-backend.sh`。只有发现实际失败才改业务代码，归属本批delta。

- [ ] 验证协议完整产品失败的QA阶段可进入SUCCEEDED并由Manager读取，而业务验收仍可有PENDING。
- [ ] 验证ENVIRONMENT/AUTHENTICATION/QA_INFRASTRUCTURE/FLAKY/REQUIREMENT_AMBIGUITY不被产品缺口Manager吞掉。
- [ ] 验证MANAGER_GAP_FIX附件、fresh QA Attempt、同round传播、退出世代、完成写入者与Manager纯度。
- [ ] 按第9节命令运行聚焦测试；package同一源码版本，保存jar SHA-256与源码diff摘要。若含prompt/bridge资源变动，跑Pi测试并重建两镜像，记录digest。
- [ ] 用已核对部署路径仅同步构建物和本次必要资源，再调用既有start脚本；不覆盖 `.env.opencode.local` 或云端secret配置，不使用`pkill -f`。
- [ ] 用真实HTTP确认启动、需求shell/audited-state读链和实际jar hash；记录服务端默认ENFORCE的生效证据。

```bash
./mvnw -pl engine -am -Dtest=ManagerPolicyTest,RequirementDeliveryStageExecutionTest,RequirementDeliveryEngineTest,RequirementAgentStageOrchestratorTest,PiQaRemediationPlannerTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest=RequirementDeliveryDispatchServiceTest,RdTaskControllerTest,ManagerDecisionPurityPolicyTest,RequirementCompletionWriterPolicyTest,PiRemediationFinalizationWriterTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am package -DskipTests
```

package的skipTests只避免重复已执行测试，不替代测试。准确产物路径用本次构建输出确认，上传后比较哈希，不按文件名认版本。

**退出：** 服务启动成功且实际构建包含两项修复；没有把“package成功”写成“真机闭环成功”。

### B02：W1g新版完整交付

**Files:** 新增 `deploy/cloud-server/mea-live/p3-w1g.json`、`deploy/cloud-server/mea-live/verify_p3.py`；证据写B00报告目录。

- [ ] 使用固定 `codex-run-test-waimai` / projectId `7499721648870920192`，新建唯一marker任务，保留三AC，确保探针能观察一次真实AC-003缺口。
- [ ] 先确认第一轮Coding没有提前实现缺口。若已经实现，归类“普通成功对照”，不能充当缺口修复探针；调整测试输入/受控测试前置后新建任务，保留所有结果，禁止篡改QA结果或DB状态伪造缺口。
- [ ] 每次关键变化采集task、stage、command、Manager、round、audited head/revisions、AuditRun与证据引用；采样能覆盖AC PENDING窗口，而非只有末态。
- [ ] 断言Manager合同含AC-003，同round完成Coding→Host→QA→auditQa；AC未完成时没有进入复核。
- [ ] 继续到复核、发布、COMPLETION，检查成功QA Attempt能被恢复读取；确认PR存在且completion binding与最终head一致。
- [ ] 如仍失败，保留provenance与已审计head；只修实际失败点并用新任务复验，不自动续跑历史DEAD_LETTERED任务。

```text
必要断言：
pending_snapshot.AC003 == PENDING
pending_snapshot.commands 不含已入队 DETERMINISTIC_REVIEW
gap_contract.targetRecordIds 含 AC-003
Coding/Host/QA 的 remediationRoundId 相同
post_qa_snapshot.AC001/002/003 == COMPLETED
task.status == COMPLETED
completion_binding.(stateVersion,stateHash,auditRunId) 与最终完成依据一致
remote.PR 存在且属于预期仓库/分支
```

**退出：** 原P3-W1局部闭环+新修复版本完整交付均有证据，普通成功任务不能替代该退出。

### B03：P3-W2 ASK与P3-W3 pause真机

**Files:** 新建 `deploy/cloud-server/mea-live/p3-w2.json`、`p3-w3.json`；扩展 `verify_p3.py`。若发现不可达分支或真实缺陷，修改现有Manager/answer/claim路径并补对应测试，仍属`mea-p3-live-closeout`。

- [ ] 先用集成用例证明真实输入能走到Manager ASK；不能仅直接调用纯函数或手写DB状态。如果当前策略没有可达入口，记录缺口并在新delta补最小入口后再真机。
- [ ] 使用实际缺少推进材料的需求进入 `WAITING_USER_INPUT`，采集Manager source decision/hash、task version/fence。
- [ ] 等待至少两个已配置dispatcher轮询周期，记录角色command无新增claim；不用固定“等2秒”替代调度周期证据。
- [ ] 通过真实 `/answer` 提交正文、唯一answerRequestId及当前版本绑定；重复相同请求不得重复resume，旧版本回答必须拒绝。
- [ ] 验证 `USER_ANSWER_RESUME` 后再入Manager并推进；非 `WAITING_APPROVAL`，不调用泛化submit。
- [ ] 在新任务存在PENDINGcommand时通过真实pause入口暂停，跨两个claim周期验证role/Manager/answer-resume均不领取；已在执行中的command不因本测试伪装“被撤回”。
- [ ] resume后验证原有合法PENDINGcommand可被领取，没有重复派发；保存前后HTTP及DB证据。

**退出：** W2/W3真实入口、等待期间claim守卫和恢复链都有证据；B01–B03失败时后续业务阶段不据“单测通过”跳过该批门槛。

### B04：冻结Coding读取DTO、真实关联与完整结果入口

**Files:** 新建 `openspec/changes/mea-coding-read-model/{proposal.md,design.md,tasks.md}`、`specs/requirement/coding-mea-read-model/spec.md`、`fixtures/coding-mea-v1.json`。

- [ ] 按第4节固化字段、枚举、分页、时间与nullable；前端F01/F02采用同一fixture。
- [ ] 逐一核对command→stage、decision→command、round→QA、audit→历史state的实际可用关联，标注不存在关系的原因。
- [ ] 将已确认的完整结果读取缺口写入design；按4.4定义stage→command→已落盘finalization→角色结果的来源与无法绑定时的降级。
- [ ] 固定不变约束：无新的业务表/调度状态，GET不改数据，不读取task全量大文本，Manager时间不改变其领域hash。
- [ ] 建立历史缺Manager、两个QA Attempt、W1e三快照、跨任务ID、分页页外引用fixture。

**退出：** 接口合同可被前后端分别实现，fixture不含运行secret，不谎称是真实API响应。

### B05：实现快照读取端口、投影和查询

**Files（拟新增）:**

- `engine/src/main/java/com/wish/rd/engine/requirement/query/CodingMeaReadPort.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/query/CodingMeaSnapshot.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/query/CodingMeaQueryEngine.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresCodingMeaReadAdapter.java`
- `engine/src/test/java/com/wish/rd/engine/requirement/query/CodingMeaQueryEngineTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresCodingMeaReadAdapterTest.java`

**Files（修改）:** 第3节command mapper/adapter读取方法、历史审计revision查询；复用现有各Store映射，不拷贝其写入逻辑。

**接口（拟新增）:** `CodingMeaReadPort.readSnapshot(taskId, codingStageRunId, limit, cursor)` 返回 `CodingMeaSnapshot`；snapshot包含第4节投影所需的原始有界引用，`CodingMeaQueryEngine.query`负责业务关联/展示DTO，不修改决策。

- [ ] 给mapper添加task-scoped稳定分页与所需显式ID批量查询；所有join校验task，不按command attemptNo猜stage。
- [ ] 在只读REPEATABLE_READ适配器中加载同快照task/head/历史revision/commands/decisions/stages/round/Host/Audit引用；同一列表不因每个stage重复全量读取。
- [ ] 纯QueryEngine生成第4节响应与缺失关系原因，初始Coding无Manager不报系统错误。
- [ ] 不取完整artifact body；历史state按decision版本读，版本不存在时保持unavailable。
- [ ] 分页游标校验task/selection，测试相同createdAt下ID排序、页外连接和newer writes不回填到旧快照。
- [ ] 通过真实PostgreSQL并发读用例：读事务中间写入新head，响应只能属于一个一致快照，不能拼成不存在的决策/状态组合。

```text
query test:
decision.stateVersion=8，currentHead.version=10。
预期stateAtDecision来自revision8，不是head10；若revision8不存在则available=false。
ownership test:
taskA的command引用taskB的stage → 关系不可用/请求拒绝，不泄漏taskB摘要。
purity test:
查询前后task/command/decision/audit表行数、版本、hash完全相同。
```

**退出：** 精确身份与一致快照通过单元和真实Postgres读取测试，无新增写路径。

### B06：HTTP、完整产物与前端合同联调

**Files（拟新增）:** `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskCodingMeaController.java`、对应同包 `RdTaskCodingMeaControllerTest.java`；`RdTaskStageResultController.java`/Test；`engine/src/main/java/com/wish/rd/engine/requirement/query/StageResultReadPort.java`、`StageResultQueryEngine.java`及同包测试；`bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresStageResultReadAdapter.java`及测试。

**修改:** `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageFinalizationMapper.java` 新增精确已落盘结果读取；复用已有归属与QA解包规则。前端owner负责`codingMeaService.ts`及`viteProxy.test.ts`，本任务提供fixture和真实响应。

- [ ] Controller只做参数/归属/响应与错误翻译，关联业务留在QueryEngine。
- [ ] 提供聚合、Manager合同全文、单stage结果读取；测试task不匹配、错误role、无artifact、截断、存储失败和历史无Manager。
- [ ] 实施StageResultReadPort/QueryEngine，通过精确identity读取finalization结果并解出该角色内容；artifactId仅作来源元数据，不能从不存在的generic reader下载。
- [ ] 用>20,000字符完整结果、混合Coding根对象+QA嵌套结果、两个QA Attempt、没有持久化完整内容的旧stage建立测试；分别验证正确正文、归属、truncated/source/downloadPath。
- [ ] 用真实HTTP验证404隔离、正常读取与空状态；查询前后任务/command版本不变。
- [ ] 向前端提供W1e与新W1g/W2/W3响应identity；联调完整证据读取和历史Attempt切换。
- [ ] 跑读取模块测试、真实Postgres、Vite代理合同与OpenSpec；记录API耗时、payload大小和查询次数以发现重复全量读取。

**退出：** 前端F02–F10不再需要模拟生产数据；API只有读能力，默认payload不含Prompt/结果/事件正文。

## 6. 基线与 Fresh Executor

### B07：建立独立waimai基线任务集

**Files（拟新增）:** `deploy/cloud-server/mea-eval/cases.json`、`thresholds.json`、`run_cases.py`、`checkers/`、`test_run_cases.py`；报告 `docs/superpowers/qa/2026-09-06-mea-baseline-evaluation.md`。复用已有 `run-codex-memory-live-task.py` 的真实API骨架，不直接改其固定任务为所有场景共用。

**输入/输出：** 输入固定项目/代码基线、case定义、模型/镜像/预算快照；输出每次taskId、系统结果、独立checker结果、证据与失败分类。

- [ ] 创建 `mea-baseline-evaluation` change并在首次新比较前写入原总体方案§6.4阈值；已有历史结果须披露，不能声称回到历史结果未知之前“预注册”。
- [ ] 建立至少12例：多文件改动、路由/页面完整性、只改文案的假完成、下单回归、晚到约束、需要输入/审批、执行中断、finalize中断、过时记忆、checkpoint续做、错误分支、非目标保持。每例定义原始要求与独立checker，不让agent看到checker实现/秘密断言。
- [ ] checker只验证原始要求、约束和已冻结评测规则；不得把未告知的任意新业务要求作为产品失败。
- [ ] 每例保存受控repository起点、case hash、checker hash、输入材料hash、镜像digest、profile/provider/model/reasoning、预算；重试生成独立runId，不能覆盖前次结果。
- [ ] `run_cases.py` 使用真实需求创建/submit/poll；末态后运行独立checker，HTTP/agent失败与checker失败分别记录。
- [ ] 同组至少3次；执行器看不到hidden checker目录，checker在独立验证工作区读取最终分支/产物。
- [ ] 分类为PRODUCT、AGENT、INFRA、HARNESS；没有checker结果为UNKNOWN，不归成功或默默剔除。

```json
{
  "falseCompleteReductionMin": 0.50,
  "endToEndPassRateMustNotRegress": true,
  "injectedFailureRecoveryRateMin": 0.80,
  "verifiedProgressLossRateMax": 0,
  "medianCostPerSuccessRatioMax": 1.8,
  "managerTokenShareMax": 0.10,
  "unauditedClaimPromotedToCompletedMax": 0,
  "auditorMutatesProtectedStateMax": 0
}
```

**R0边界：** 现有W1/历史PR单例不是12例×3的R0。若需复跑旧shape版本，必须在隔离评测部署、独立数据和明确版本中执行，不能把当前生产ENFORCE关回SHADOW。模型/版本/镜像无法复现时标不可比较；不据此填写R0/R1/R3阈值通过。R0错误完成率若为0，使用“新组也为0”的绝对条件，并同时报告样本数，不计算除零下降百分比。

**验证：** `python3 -m unittest discover -s deploy/cloud-server/mea-eval -p 'test_*.py'`；再跑真实12例×3并保留失败。脚本测试至少覆盖重复runId拒绝、UNKNOWN不变成功、checkpoint路径不存在、checker非零、API超时。

### B08：修正成功口径并产出可比较汇总

**Files（已有）:**

- `engine/src/main/java/com/wish/rd/engine/admin/observability/DeliveryObservabilityQueryService.java`
- `engine/src/main/java/com/wish/rd/engine/admin/observability/model/DeliveryLedgerSnapshot.java`
- `engine/src/test/java/com/wish/rd/engine/admin/observability/DeliveryObservabilityQueryServiceTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/observability/PostgresDeliveryObservabilityRealSmokeTest.java`

**Files（新增）:** `deploy/cloud-server/mea-eval/summarize.py`、`test_summarize.py`；生产口径走独立 `mea-delivery-outcome-metrics` change。

- [ ] 核对 `upgrade-delivery-observability` 当前归档/主spec状态；先按原方案协调既有实现，再通过新delta改变成功口径，不顺手覆盖在途工作。
- [ ] 当前 `SUCCESS={COMPLETED,COMMITTED,MERGED}` 改为：COMPLETED成功；COMMITTED在途；MERGED只有历史存在COMPLETED事件才计成功。
- [ ] WAITING_USER_INPUT与WAITING_APPROVAL属于在途，前者不计SUCCESS/FAILURE/TERMINAL；保留暂停作为独立标志。
- [ ] 扩展snapshot读取所需历史完成事实，基于状态事件而非PR存在或当前枚举猜测。
- [ ] usage沿用attempt去重与CNY字段，usageAvailable=false不记为0成本。纯Host Manager模型token为0/不适用，Host墙钟另计。
- [ ] 汇总器同时报告系统完成率、hidden checker通过率、false-complete、false-block、恢复、成本和四类失败；禁止复用旧脚本shape/regex的deliveryComplete作为hidden真值。

```text
单测矩阵：
COMMITTED + PR存在 + 无COMPLETED事件 → 非成功、在途
MERGED + 曾COMPLETED → 成功
MERGED + 仅曾COMMITTED → 非成功
WAITING_USER_INPUT → 非成功、非失败、非终态
COMPLETED + hidden checker失败 → 系统完成但false_complete=true
usageAvailable=false → 成本未知，不能进入已知成本中位数且必须报告未知比例
```

**验证：** `DeliveryObservabilityQueryServiceTest`；显式开启 `-Drd.integration.delivery-observability.enabled=true` 的真实Postgres/HTTP smoke，并提供测试环境连接；脚本unittest。退出：R0/R1/R3含相同分母规则、样本身份和不可比原因。

### B09：补齐所有恢复入口的已审计缺口Prompt

**Files（已有）:**

- `engine/src/main/java/com/wish/rd/engine/requirement/audit/AuditedGapSection.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestrator.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- `engine/src/test/java/com/wish/rd/engine/requirement/audit/AuditedGapSectionTest.java`
- `engine/src/test/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestratorTest.java`
- `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`

**当前差异：** 两处 `previousFailureFeedbackSection` 已读取head/lastRun并调用 `AuditedGapSection.render`，后者已有MAX_IDS=16/MAX_CHARS=2000，不读取旧errorMessage。旧完整计划的“新增该能力”步骤不可原样执行。`recoveryPromptSection/downstreamFailureFeedbackSection` 是需继续核验的另一条checkpoint入口。

- [ ] 创建Phase2 change并列已实现/剩余/未真机三个清单；保留现有renderer和测试。
- [ ] 用包含唯一stderr marker的失败角色、下游失败、checkpoint恢复、Host fix、QA协议重试分别构造新Prompt；先证明哪个入口仍泄露旧原文。
- [ ] 所有本轮必要失败反馈统一从已审计head构造，输出stateVersion/hash、missing/blockers/untrusted、证据URI；不得附旧AGENT_EVENTS长段。
- [ ] 缺head/lastRun时明确“尚无已审计状态/缺口不可用”，不把空缺口当完成，不回退raw errorMessage。
- [ ] 保留操作员当前补充材料、原始目标与本轮有界合同；fresh不是删除原始需求。
- [ ] 两条engine/orchestrator入口调用同一renderer，测试边界16个ID/2000字符、中文截断、重复URI、缺lastRun、无旧原文。

```text
输入：oldFailureMarker="stderr-secret-fixture-unique"，audited head缺GATE-BUILD。
输出必须含GATE-BUILD和head hash；不得含oldFailureMarker。
原始错误仍能在旧RESULT_JSON/AGENT_EVENTS诊断入口读取，不能删除历史证据。
```

**验证：** `AuditedGapSectionTest,RequirementAgentStageOrchestratorTest,RequirementDeliveryEngineTest`。退出：测试覆盖实际所有恢复入口，不能只凭两处方法已改就宣称Phase2全完成。

### B10：compact handoff与检索信任标记

**Files（已有）:**

- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestrator.java` 的handoff/environmentNotes/facts构造
- `engine/src/main/java/com/wish/rd/engine/requirement/RoleExecutionInputManifestBuilder.java`
- `rag/src/main/java/com/wish/rd/rag/context/ProjectMemoryUntrustedContext.java`
- `rag/src/main/java/com/wish/rd/rag/context/RoleContextBuilder.java` 的 `buildFromEvidence/fitEvidenceToBudget`
- `rag/src/main/java/com/wish/rd/rag/context/model/RoleContextEvidence.java`
- `engine/src/main/java/com/wish/rd/engine/retrieval/DeepRetrievalOrchestrator.java` 的证据选择/缺失判定
- `bootstrap/src/main/java/com/wish/rd/bootstrap/rag/impl/ProjectScopedRequirementKnowledgeSearchAdapter.java` 的chunk到证据类型映射
- 测试：`rag/src/test/java/com/wish/rd/rag/context/RoleContextBuilderTest.java`、`engine/src/test/java/com/wish/rd/engine/retrieval/DeepRetrievalOrchestratorTest.java`、`DeepRetrievalOrchestratorIterativePolicyTest.java`

- [ ] 以当前compact handoff fixture重核已有UNTRUSTED标记，不重复写第二套包装。
- [ ] environmentNotes/facts默认UNTRUSTED，删除所有无审计支撑的“已实测验证，直接沿用”；只有可反查AuditRun的条目显示 `VERIFIED(auditRunId=...)`。
- [ ] 在角色证据输入模型显式区分HINT/VERIFIED与Host材料来源；检索命中默认HINT，不能单凭类型名满足角色证据门。
- [ ] VERIFIED必须有当前任务/契约适用的审计引用；来自旧任务的已审计经验仍只是当前任务HINT。
- [ ] 保留 `rag/src/main/java/com/wish/rd/rag/retrieval/navigator/KnowledgeEvidenceAllowlist.java` 的binding、IN_SYNC、version、scope校验；新trust字段不能替代已有外部知识准入门。
- [ ] 保持 `UNTRUSTED_PROJECT_MEMORY` 边界，绝不注入工具授权/审批绕过指令。
- [ ] 对比manifest、最终Prompt、容器输入三处：信任字段不得中途被纯字符串formatter吞掉。

```text
测试：一个检索命中写着“QA已通过”，无当前AuditRun → HINT，QA/完成门仍待执行。
测试：有AuditRun引用但task/contract不匹配 → HINT或拒绝，不晋升VERIFIED。
测试：同任务有效Host证据 → VERIFIED + auditRunId，可被后续独立核验。
```

**退出：** 相同信任语义从Port→manifest→Prompt贯通，历史claim未被措辞升级。

### B11：角色episode预算、隔离回归与P2真机

**Files（已有）:**

- `rag/src/main/java/com/wish/rd/rag/project/agent/model/AgentExecutionProfile.java` 及其snapshot、管理DTO/mapper
- `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/EngineRequirementExecutionProfileResolver.java`
- `exec/src/main/java/com/wish/rd/exec/repair/docker/RepairWorkspaceFactory.java`
- `exec/src/main/java/com/wish/rd/exec/repair/pi/impl/DockerPiAgentExecutor.java`
- `exec/src/test/java/com/wish/rd/exec/repair/pi/DockerPiAgentExecutorTest.java`
- `bootstrap/src/main/resources/executor/pi/src/rd-pi-bridge.mjs`

**Files（新增）:** `deploy/cloud-server/mea-live/verify_p2.py`；该change的profile预算migration/测试。

明确复用的profile文件：`rag/src/main/java/com/wish/rd/rag/project/agent/model/AgentExecutionProfileSnapshot.java`、`rag/src/main/java/com/wish/rd/rag/project/agent/AgentExecutionProfileSnapshotService.java`、`bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/agent/AgentExecutionProfileAdminController.java`、`bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/AgentExecutionProfileMapper.java`、`AgentExecutionProfileSnapshotMapper.java`。新增预算字段须贯通request/model/SQL/snapshot/bridge request。

已有 `rag/src/main/java/com/wish/rd/rag/project/agent/model/RoleExecutionBudget.java` 只冻结模型上下文上限、输出预留和输入估算，不是episode硬预算。Reviewer的估算审批也不是硬预算；本任务复用Pi bridge已有maxAgentTurns/maxTotalTokens钩子，任务级墙钟/无进展/跨角色累计由B16–B19负责。

- [ ] 核对provider-attempt隔离已存在与实际启用的测试；当前QA安装目录和provider workspace规则沿用RULE，不重新套只读repo挂载。
- [ ] 增加 `maxAgentTurns/maxTotalTokens` 的profile配置、验证与冻结snapshot传递；新episode不能默认为0关闭。
- [ ] 预算值按B07每角色至少3个有效样本计算：`ceil(1.5 * P95(actualTurns))` 与 `ceil(1.5 * P95(actualInputTokens+actualOutputTokens))`，冻结实际正数在change的 `role-budget-defaults.json`。缺样本先补基线，不偷偷用0。项目更小预算按既有审批/阻断合同处理，不越权放大。
- [ ] Pi BUDGET_EXCEEDED映射为Host blocker/provenance并保留head；禁止当产品缺陷触发Coding修复。实际结果/usage不可得时记录未知，不制造低成本。
- [ ] 同步profile schema/API/snapshot/bridge读取合同，验证旧snapshot仍可按旧版本解码，重试复用冻结值而非读取已被管理员改过的profile。
- [ ] 跑P2-W1 Coding中断/reclaim、P2-W2真实Host构建失败、P2-W3协议重试，读取新 `PROMPT_SNAPSHOT` 验证无旧stderr marker且hash-bound缺口存在。
- [ ] P2-W1继续到ENFORCE完成；采集workspace/provider attempt目录identity，证明不是同一QA目录复用。

**验证：** Pi `npm test`、`DockerPiAgentExecutorTest`、`RepairWorkspaceFactoryTest`、profile resolver测试、B09/B10测试；按AGENTS重建镜像并真机。退出：实现/Prompt快照/真实恢复三种证据都有，不以取消@Disabled的测试数量作为完成证明。

## 7. 完整计划后续治理阶段

### B12：冻结稳定任务契约v1

**Files（新增）:**

- `engine/src/main/java/com/wish/rd/engine/requirement/contract/TaskContract.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/contract/TaskContractStore.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/contract/TaskContractBuilder.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskContractStore.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskContractMapper.java`
- 同包 `TaskContractBuilderTest`、`PostgresTaskContractStoreTest`

**修改:** 现有 `AuditedContractRef`、`RequirementDeliveryEngine` 的POLICY_APPLY/plan codec/审计合同读取；新migration后缀 `_mea_task_contracts.sql`，任务开始分配唯一序号并写入change。

**拟新增核心合同：** `taskId, contractVersion, parentVersion, originalRequestHash, materialSetHash, acceptanceCriteria[{criteriaId,text,blocking}], nonGoals[], forbiddenShortcuts[], finalConsumer, stateCarrier, contractHash, createdAtEpochMillis`。已有AuditedContractRef不是版本契约表，不重复更名掩盖缺能力。

- [ ] 创建 `mea-stable-contract-and-backcheck` delta；先写契约不能被中间计划弱化的测试。
- [ ] 在POLICY_APPLY冻结v1，主键/唯一键 `(task_id, contract_version)`；规范化hash覆盖实际合同内容，创建时间不决定语义hash。
- [ ] 验收ID稳定：v2保留未改变条目ID，新增用新ID；删除/弱化有显式变更记录，不能重排全部AC后复用旧证据。
- [ ] TaskContractStore只追加版本，当前引用随task同事务CAS推进；并发相同请求幂等，不同内容同版本冲突。
- [ ] 更新计划序列化版本与向后解码；冻结plan包含contractVersion/hash，不能运行中默读最新合同。
- [ ] 既有任务仅有AuditedContractRef时保留legacy标识。能从原始材料完整重建才迁入版本合同；无法证明时明确阻断/需核验，不把当前计划反推成原始需求。

```text
contract v1: AC001=首页标记；AC002=下单不回归。
Coding中间计划只保留AC001。
断言：TaskContract仍有AC002；completion gate不能依据中间计划删除它。
```

**验证：** builder/store/plan codec测试、真实Postgres并发版本CAS。退出：稳定权威输入可反查，旧状态引用兼容边界明确。

### B13：追加材料触发契约版本与REPLAN/ASK

**Files:** 修改现有任务材料写入口/事务端口、`TaskContractStore`、`ManagerPolicy`、`RequirementDeliveryEngine.planManagerDecisionStage`、`RequirementStageFinalizationPort` 与其Postgres适配器；新增 `TaskContractRevisionTest` 和材料Controller集成测试。

- [ ] 把EXECUTING期间新增材料识别为合同修订请求：同事务保存材料身份、契约v+1和待决状态，不能先写材料后异步补版本。
- [ ] 可确定的新AC触发REPLAN；材料存在阻断歧义触发ASK/WAITING_USER_INPUT。现有sourceCommandId/Manager决策幂等性仍保留。
- [ ] 已领取旧合同command的结果可记历史claim/证据，但不得以旧plan digest完成新contractVersion。
- [ ] 在recordOutcome和finalize两个窗口验证contractVersion/hash：落盘前漂移拒绝冻结旧完成计划；落盘后漂移不让重放覆盖新版本。
- [ ] 保留新旧revision和supersession原因，原材料及原AC不删除，前端读取可显示版本变化。

```text
并发场景：command在v1执行 → 用户追加AC003形成v2 → v1结果到达。
预期：v2 AC003仍PENDING；旧结果不能使v2 COMPLETED；Manager REPLAN或ASK。
重复同一材料提交：不重复增contractVersion。
```

**验证：** `RdTaskControllerTest`相关材料场景、ManagerPolicyTest、finalization真实Postgres race；P4-W2真实HTTP追加材料并观察版本+1。退出：旧plan与新契约不会错配完成。

### B14：只读契约反查与三维门禁

**Files（已有）:** `engine/src/main/java/com/wish/rd/engine/requirement/review/impl/AiReviewPackageBuilder.java`、`AiDeliveryReviewEngine`、`AiReviewPackageBuilderTest`、`AiDeliveryReviewEngineTest`、`ContractAuditVerdict`、`AuditedCompletionGate`、`DeterministicAuditor`。

**拟新增:** `engine/src/main/java/com/wish/rd/engine/requirement/contract/ContractAuditInput.java`、`ContractAuditResult.java`；对应输入白名单/输出验证测试。

- [ ] 先用spy捕获真正提交给现有review执行路径的payload；断言仅有原始请求、稳定契约、已审计head、当前Executor有界摘要。
- [ ] 从package builder剔除PROMPT_SNAPSHOT、AGENT_EVENTS、整段stage/retrieval/context artifacts；不能只从页面隐藏却仍发送模型。
- [ ] 模型型反查复用既有允许执行路径；不能为Auditor新增Host直连模型HTTP或把Manager改成模型Agent。
- [ ] 扩展契约结论为ALIGNED/NEEDS_REVISION/INVALID，保留UNKNOWN用于历史/不可用并失败关闭；输出含blockingUnknowns及对应criteriaId/原始约束引用。
- [ ] Host验证结构化反查结果，将其作为审计输入形成冻结mutation；模型响应不能直接写AuditedRecord COMPLETED。
- [ ] 完成条件同时检查completion=COMPLETE、integrity=CLEAN、contractAudit=ALIGNED、blockingUnknowns为空、当前contractVersion/hash一致。
- [ ] P4-W1真机：复制文案/构建绿但遗漏下单约束，必须仍不完整或NEEDS_REVISION；保留原始输入与拒绝理由。

```text
输入白名单测试：payload recursively 不出现 artifactType=PROMPT_SNAPSHOT/AGENT_EVENTS。
反查测试：QA CURRENT漏checkout，原contract仍要求checkout → 非ALIGNED或AC保持PENDING。
未知测试：review超时/无结构输出 → UNKNOWN/失败provenance，不默认ALIGNED。
```

**验证：** AiReviewPackageBuilderTest、AiDeliveryReviewEngineTest、AuditedCompletionGate/DeterministicAuditor测试及P4-W1/W3。退出：内容白名单作用于真实执行payload，结论不能越过Host完成权。

### B15：最终PR消费者证据与Phase4退出

**Files:** 现有 `RequirementPublicationLedger`、`RequirementPublicationReconciliationService`、`RequirementPublicationStore`、`PostgresRequirementPublicationStore`、GitHub适配器、`RequirementDeliveryEngine`的publication/completion阶段；新增/扩展publication gate测试。

- [ ] `ART-PR`证据核对预期仓库、真实PR、目标分支、分支包含已审计commit、稳定body marker；仅“有PR URL”不算。
- [ ] 通过既有Port执行GitHub读取，持久化远端检查回执并引用到AuditRun；网络失败/未知远端结果保留UNKNOWN，先reconcile，不盲重建PR。
- [ ] 完成前重新核对当前契约/审计head；已发布PR但head降级不得COMPLETED。
- [ ] P4-W1/W2/W3及PR篡改/分支错误场景通过后，记录Phase4退出，不改旧W1e结论。

```text
PR存在但branch不含审计commit → ART-PR未通过，任务不可完成。
PR创建响应丢失但远端已有同operation marker → reconcile到同一PR，不创建第二个。
```

**验证：** publication reconciliation/crash-window测试、真实HTTP与GitHub读取回执；不删除验证产生的PR来“清理失败证据”。

### B16：任务级预算账本

**Files（新增）:** `engine/src/main/java/com/wish/rd/engine/requirement/budget/TaskBudgetLedger.java`、`TaskBudgetStore.java`、`TaskBudgetPolicy.java`；Postgres adapter/mapper与同包测试；migration后缀 `_mea_task_budget_ledgers.sql`。

**修改:** 现有RoleExecutionBudget、usage解析、Manager输入、finalization冻结plan及failure provenance。

- [ ] 创建 `mea-budget-and-recovery-governance` delta，明确episode预算来自B11、任务预算聚合跨角色/重试/修复/审计执行，不重复设计profile字段。
- [ ] `rd_task_budget_ledgers`持久化source identity、task、role/stage、reservation/settlement、turns、input/output/cache tokens、CNY、wallclock、usageAvailable；以来源事件唯一键去重。
- [ ] 预留与结算在既有事务边界内进行，重复OUTCOME重放不重复扣费；未知usage保留未结算预留，不当成0。
- [ ] 冻结任务预算上限与每轮观察快照；用户调预算产生明确版本，不回改已冻结执行snapshot。
- [ ] 耗尽产生 `BUDGET_EXHAUSTED` provenance与Host blocker，走现有合法非完成状态；保留已审计head/产物，不新增“部分COMPLETED”状态。
- [ ] Manager仍纯函数读取预算值并产出BLOCKED/ASK，数据库结算留在finalize，不在策略函数内写账本。

```text
同一stageRunId usage事件回放两次 → 总token/CNY只增加一次。
未结算执行中崩溃 → 预留保留并reconcile，不能凭0usage释放预算无限重试。
Reviewer预算极小 → BUDGET_EXHAUSTED，原审计head/hash保留，task非COMPLETED。
```

**验证：** budget policy/store单测、真实Postgres去重/CAS、P5-W3真机；CNY字段必须带币种，不把USD直接标人民币。

### B17：无进展检测与Manager停止

**Files（新增）:** `engine/src/main/java/com/wish/rd/engine/requirement/manager/ManagerProgressPolicy.java` 及测试；修改Manager输入、预算/决策历史读取、frozen plan codec。

- [ ] 默认连续阈值N=3，配置允许2–10，冻结进task治理snapshot；重复同一command回放不增加轮数。
- [ ] 实现原合同“连续N轮同state_hash且无新verified证据”的停止规则，计数基于实际持久化轮次。
- [ ] 用真实AuditedTaskState hash算法测试：若版本/claim时间导致hash变化，增加显式progressDigest作为补充观察，包含contractVersion、验收/门状态与已核验证据内容hash，排除时间戳/无审计claim增长；在delta清楚记录此规则，不悄悄更改state_hash定义。
- [ ] 新增可核验证据或契约修订重置无进展计数；改rationale、换attempt、刷新时间均不算进展。
- [ ] 达阈值时Manager BLOCKED；只有缺少用户输入可解除的情形才ASK。保留目标AC、最后证据与停止理由，不续HOST_VERIFY_FIX。

```text
三轮：same semantic state + 新UNTRUSTED claim + 无新独立证据 → 停止。
第三轮出现新的Host证据并使一个AC通过 → 计数重置。
同一冻结plan恢复三次 → 仍只是一轮，不误触发无进展。
```

**验证：** ManagerProgressPolicyTest、ManagerPolicyTest、dispatcher tests及P5-W4真机。退出：有界循环停止可解释，既不无限返工也不把技术重放当业务停滞。

### B18：执行回执与结果已算出窗口恢复

**Files（新增）:** `engine/src/main/java/com/wish/rd/engine/requirement/job/RequirementStageExecutionReceipt.java`、`RequirementStageExecutionReceiptStore.java`；Postgres adapter/mapper与测试；migration后缀 `_mea_stage_execution_receipts.sql`。

**修改:** `InterruptedStageRecoveryService`、实际Pi执行结束结果收集入口、`RequirementDeliveryDispatchService` reclaim、`RequirementStageFinalizationPort`/Postgres适配器。

- [ ] 将回执identity冻结为task/command/stageRun/providerAttempt和输入snapshot/候选patch/结果hash；同identity不同hash失败关闭。
- [ ] 容器退出后、recordOutcome之前，先把可恢复原始结果/回执耐久化；回执来源于Host观察与已落盘结果，不是Agent自报“完成”。
- [ ] reclaim先查冻结outcome；已存在则只finalize。无outcome但有完整回执时解析校验已有结果并构造outcome，不再启动QA容器。
- [ ] 只有回执证明执行未发生或结果不可恢复且现有策略允许时才新开执行；远端/容器状态未知保留需核验，不能盲判缺席。
- [ ] receipt写入与lease/fence校验防旧worker覆盖；结果采集失败保留可诊断路径，不能删除任务repo/cache。
- [ ] 从实际crash点验证receipt持久化完成在崩溃之前，单纯新增数据库表不算封住窗口。

```text
QA容器退出 → receipt持久化 → JVM终止 → reclaim。
预期：同stage结果被复用；QA容器启动计数不增加；audit mutation恰好一次。
旧fence worker写receipt/结果 → 拒绝，不影响新owner。
```

**验证：** receipt store/InterruptedStageRecoveryService tests、`PostgresRequirementStageFinalizationRealSmokeTest`、P5-W1真机。

### B19：发布回放与四边界故障矩阵

**Files（已有）:**

- `engine/src/test/java/com/wish/rd/engine/requirement/publication/RequirementPublicationCrashWindowAcceptanceTest.java`
- `engine/src/test/java/com/wish/rd/engine/requirement/publication/RequirementPublicationReconciliationTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresRequirementPublicationContinuationRealSmokeTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/executor/RequirementPublicationBareGitCrashWindowAcceptanceTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/executor/RequirementPublicationReconcileSchedulerTest.java`

**新增:** `deploy/cloud-server/mea-live/verify_p5.py`、`docs/superpowers/qa/2026-09-06-mea-recovery-matrix.md`。

- [ ] 固定Manager/Executor/Auditor/持久化四边界，与派发前/执行中/结果已算出/OUTCOME_RECORDED后/finalize中/PR发布中交叉；不适用组合写理由，其余每格关联一个实际测试。
- [ ] P5-W2在push/publication中断，先reconcile远端commit/PR与operation marker；确认一个PR、已审计进展不丢失。
- [ ] 检验recordOutcome后只decode冻结plan、同version/hash幂等、不同hash冲突保留待恢复；不因技术重试创建第二个Manager round。
- [ ] 汇总重复容器/重复PR/重复memory operation/审计进展丢失四类副作用；不可只检查最终HTTP 200。
- [ ] 在允许的测试窗口用既有start脚本方式重启JVM，不影响无关任务；保留所有失败与基础设施分类。

**退出：** P5-W1–W4均有证据，故障恢复率≥80%，已核验进展丢失率0%，远端副作用不重复；样本和实际故障矩阵完整可复算。

### B20：审计记忆晋升模型与持久化

**Files（已有）:**

- `engine/src/main/java/com/wish/rd/engine/project/memory/ProjectMemoryPromotionPolicy.java`
- `engine/src/main/java/com/wish/rd/engine/project/memory/ProjectMemoryConsolidationEngine.java`
- `engine/src/main/java/com/wish/rd/engine/project/memory/ProjectMemoryPromotionEvidence.java`
- `rag/src/main/java/com/wish/rd/rag/project/memory/model/ProjectMemoryRevision.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/ProjectMemoryRevisionMapper.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/ProjectMemoryMapper.java`
- `engine/src/test/java/com/wish/rd/engine/project/memory/ProjectMemoryPromotionPolicyTest.java`、`ProjectMemoryConsolidationEngineTest.java`

**新增:** `mea-audited-memory-promotion` change；migration后缀 `_mea_audited_memory_promotion.sql`，保留p19不重写。

- [ ] 在evidence/revision增加auditRunIds及来源task/contract/record范围；至少一个可解析有效审计引用才有ACTIVE资格。
- [ ] 不再凭deliverySucceeded/qaSucceeded/administratorConfirmed布尔值绕过审计；管理员确认保留操作来源，但不能创造技术证据。
- [ ] 逐条验证audit属于该项目/来源task，证据内容hash与记忆断言匹配；引用存在但无关也不能晋升。
- [ ] INSERT显式写redacted/confidence/evidence_quality/valid_to，与检索谓词一致；redacted=true必须由脱敏处理结果产生，不通过强设true绕过保护。
- [ ] confidence/quality由已定义验证规则计算，不能为了命中统一填1；低质量保持候选/隔离并记录理由。
- [ ] 历史ACTIVE无auditRunIds不批量伪造引用；保持可回溯legacy/隔离策略，重新审计后才按新规则晋升。

```text
deliverySucceeded=true + auditRunIds=[] → 非ACTIVE。
auditRunId属于其他project → 拒绝/隔离。
有效审计+脱敏完成+quality达标+未过期 → 可ACTIVE并可检索。
```

**验证：** promotion/consolidation tests、mapper参数与真实Postgres谓词一致性，旧revision读兼容测试。

### B21：打通生产finalize→operation→worker

**Files（已有）:**

- `engine/src/main/java/com/wish/rd/engine/requirement/job/RequirementStageFinalizationPort.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/threading/RequirementDeliveryDispatchService.java` 的 `finalizeStageOutcome`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementStageFinalizationAdapter.java`
- `engine/src/main/java/com/wish/rd/engine/project/memory/ProjectMemoryFinalizationRegistrar.java`
- `engine/src/main/java/com/wish/rd/engine/project/memory/ProjectMemoryReconciliationDraftFactory.java`
- `engine/src/main/java/com/wish/rd/engine/project/memory/ProjectMemoryOperationWorker.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/projectmemory/ProjectMemoryOperationWorkerConfiguration.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresProjectMemoryReconciliationSource.java`

**新增:** `engine/src/main/java/com/wish/rd/engine/project/memory/AuditedProjectMemoryOperationHandler.java`及测试，具体extractor/validator/resolver复用现有Consolidation端口。

- [ ] 当前dispatcher兼容构造器固定none()、handler为`claim -> checkpointJson()`，首先建立真实生产入口测试暴露该空链路。
- [ ] 仅在有可用AuditRun证据的已冻结finalization构造enabled draft；operationId由来源command、内容hash、extractor/schema版本确定，同回放不重复注册。
- [ ] 保留finalize同事务registrar，不在controller或响应后临时写memory operation。
- [ ] 替换stub为extract→validate audit refs/redaction→resolve→CAS的有界handler；每步持久化checkpoint，保持现有lease/fence/有限重试。
- [ ] reconciliation从已finalized命令恢复遗漏operation时复用相同key与policy，不能把SKIPPED_BY_POLICY全部改成可晋升。
- [ ] 只有handler/DB/端到端验证通过后在受控项目开启worker；开关开=true本身不算接线完成。

**验证：** ProjectMemoryOperationWorkerTest、ProjectMemoryReconciliationTest、ProjectMemoryOperationWorkerConfigurationTest、finalization测试。退出：真实dispatcher路径传入enabled draft，且worker产生实际候选/校验结果而非仅复制checkpoint。

### B22：记忆检索边界与P6真机

**Files（已有）:** `rag/src/main/java/com/wish/rd/rag/project/memory/impl/InMemoryProjectMemorySearchPort.java`、`rag/src/main/java/com/wish/rd/rag/retrieval/impl/ProjectMemoryRetrievalChannel.java`、`rag/src/main/java/com/wish/rd/rag/context/ProjectMemoryUntrustedContext.java`、`RoleExecutionInputManifestBuilder`；`bootstrap/src/test/java/com/wish/rd/bootstrap/ProjectAgentMemoryPostgresRealSmokeTest.java`。

**新增:** `deploy/cloud-server/mea-live/verify_p6.py`与生产finalize→worker→检索集成测试。

- [ ] P6-W1用真实ENFORCE完成任务触发memory operation，导出memory/revision/source/operation/audit引用；非空只是第一条件，还需实际检索命中并核对来源。
- [ ] P6-W2在受控测试项目植入“QA可跳过构建”的假/过时经验，确认仍跑Host+QA，AC不能由memory单独完成；保留对抗记录，不改真实生产知识。
- [ ] P6-W3读取新Prompt/manifest，命中一律为UNTRUSTED_PROJECT_MEMORY/HINT，即使来源过去曾审计通过。
- [ ] 真实smoke覆盖生产finalizer→worker→consolidation→query，而非仅现有head CAS/operation fencing。
- [ ] 验证重复finalize/reconcile不产生重复operation/revision；与B19恢复矩阵交叉一例。

**验证：** 显式 `-Drd.integration.stage-finalization.enabled=true` 的ProjectAgentMemoryPostgresRealSmokeTest，使用测试数据库连接；P6-W1–W3真实HTTP/导出/Prompt证据。

### B23：在已有profile/snapshot上增加风险模型路由

**Files（已有）:** `AgentExecutionProfile`及snapshot、`EngineRequirementExecutionProfileResolver`、`AgentRuntimeExecutorConfiguration`、`DockerPiAgentExecutor`、`PiCredentialRelayService`、provider/profile管理端与mapper；复用B11episode预算字段。

profile管理/持久化的精确路径同B11，测试扩展 `rag/src/test/java/com/wish/rd/rag/project/agent/AgentExecutionProfileServiceTest.java`、`AgentExecutionProfileSnapshotServiceTest.java`、`bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/agent/AgentExecutionProfileAdminControllerTest.java`、`bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresAgentExecutionProfileStoreTest.java`。snapshot的append-only/hash不变式继续有效。

**Files（新增）:** `engine/src/main/java/com/wish/rd/engine/requirement/routing/RoleModelRoutingPolicy.java`及测试；`mea-role-model-routing` delta与配置migration。

- [ ] 依据B07–B22已验证风险场景定义LOW/HIGH路由规则：schema/API/并发恢复/权限或未知风险走HIGH；普通有界文案/单文件实现可LOW。风险输入来自Host分类与冻结契约，不能仅采信Executor自报riskLevel。
- [ ] policy只产出registered profile/provider/model引用及理由，不调用模型；resolver冻结选中profileVersion/model/reasoning/预算/risk/route版本进snapshot。
- [ ] 不在计划里硬编码未经项目登记的模型ID/价目；映射由当前可用provider配置与B24实测决定，缺HIGH profile时明确阻断，不静默降级。
- [ ] Manager仍是纯Host策略，模型token为0；Auditor先确定性，B14只读契约反查才使用既有受控模型路径，不能引入Host直连HTTP。
- [ ] retry保持原snapshot；只有新episode/显式replan生成新路由并记录原因，不能恢复中漂移model。
- [ ] 记录实际选中模型/usage/cost与缺失值，UI/指标能反查stage/profile version，禁止用配置价格代替实际usage证据。

```text
HIGH风险但HIGH profile不可用 → 明确阻断，不落LOW。
profile在执行中被管理员修改 → 已冻结stage仍用旧snapshot。
同command恢复 → 不重新路由；新episode可按新版本策略形成新snapshot。
```

**验证：** routing policy、profile resolver/snapshot/数据库/管理API合同测试；ManagerDecisionPurityPolicyTest持续通过。

### B24：固定预算消融与最终交接

**Files:** 复用B07/B08脚本；新增 `docs/superpowers/qa/2026-09-06-mea-role-routing-acceptance.md`、本次run manifest与汇总JSON；不新增评测产品页面。

- [ ] 相同12例×至少3次，用冻结模型版本/镜像/profile/输入/预算比较R0/R1/R3，条件允许加入R2/R4；不能把具有不同组件的组错标。
- [ ] 对所有组使用同一hidden checker与失败分类；逐项披露不可复現模型/缺成本/infra失败，不挑选成功样本。
- [ ] 验证端到端通过率不低于R0、false-complete较R0下降至少50%（R0为0则本组也0）、median cost/success≤1.8×R0、恢复≥80%、已核验进展丢失0、未经审计晋升0、受保护状态修改0。
- [ ] 分开报告“满足成本上限”和“相对基线成本下降”。原总体方案的角色优化目标要求成本下降，只有前者不能声称已实现降本。
- [ ] 逐阶段交接代码SHA、迁移文件、镜像digest、实际命令、taskId/PR/审计绑定和未覆盖项；只把已实施且验证匹配的change进入归档流程。
- [ ] 全部required checks完成后再发布最终结论；没有可比R0时保留“绝对结果已测、相对门槛未证实”，不伪造通过。

**退出：** 结果可从原始run manifest复算，前端已能显示真实Coding MEA与四角色证据，后端各阶段证据边界清晰。

## 8. 阶段退出与前端衔接总表

| 批次 | 必须留下的结果 | 前端消费 |
| --- | --- | --- |
| B01–B03 | 新jar/镜像身份、W1g闭环、W2 answer与W3claim守卫 | F10真实任务集；不要求前端等Phase7 |
| B04–B06 | 只读DTO、历史快照、完整结果来源、HTTP/PG证据 | F01/F02/F05/F06/F10 |
| B07–B08 | 任务集、hidden checker、阈值、正确成功分母 | 既有观测页口径；不增加评测入口 |
| B09–B11 | 新Prompt无旧stderr、信任标记、预算snapshot、P2恢复 | Prompt与状态栏可看到来源变化，无需布局重做 |
| B12–B15 | 契约版本/三维审计/ART-PR真实证据 | 审计详情按新版本增量呈现；不把旧结果当新contract完成 |
| B16–B19 | 预算/停止原因/回执/故障矩阵 | 已有状态与provenance位置展示诚实未完成 |
| B20–B22 | 有AuditRun的记忆revision、真实检索、hint边界 | 输入依据显示来源，永远不是当前验收证明 |
| B23–B24 | 冻结风险路由、真实模型usage、可比验收 | 次级元数据显示profile/model/成本，不扩大首屏杂乱 |

本轮前端分支只要求B06合同与已有字段。Phase4–7未来新增UI字段应通过该阶段明确的小型前端任务更新service/审计详情，不能让F01–F10承担所有未来治理功能。

## 9. 验证命令与证据规范

以下是实施时的命令模板，本轮未执行；测试名来自当前源码或相应任务明确的新建测试。新增测试须先存在且被实际执行，不能用Surefire的空测试成功作证明。

```bash
# 任务状态图
./mvnw -pl rag -Dtest=RdTaskTransitionPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test

# P3与Fresh聚焦
./mvnw -pl engine -am -Dtest=ManagerPolicyTest,RequirementDeliveryStageExecutionTest,RequirementDeliveryEngineTest,RequirementAgentStageOrchestratorTest,AuditedGapSectionTest,RequirementStageExecutionPlanCodecTest -Dsurefire.failIfNoSpecifiedTests=false test

# Pi与workspace
./mvnw -pl exec -am -Dtest=DockerPiAgentExecutorTest,RepairWorkspaceFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test

# 完成权、事务代理与Manager纯度
./mvnw -pl bootstrap -am -Dtest=RequirementDeliveryDispatchServiceTest,RdTaskControllerTest,ManagerDecisionPurityPolicyTest,RequirementCompletionWriterPolicyTest,TransactionalProxyPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test

# 读取合同，新测试在B05/B06创建后执行
./mvnw -pl bootstrap -am -Dtest=CodingMeaQueryEngineTest,PostgresCodingMeaReadAdapterTest,RdTaskCodingMeaControllerTest,RdTaskStageResultControllerTest -Dsurefire.failIfNoSpecifiedTests=false test

# 交付契约与publication既有回归
./mvnw -pl engine -am -Dtest=AiReviewPackageBuilderTest,AiDeliveryReviewEngineTest,RequirementPublicationCrashWindowAcceptanceTest,RequirementPublicationReconciliationTest -Dsurefire.failIfNoSpecifiedTests=false test

# 记忆既有回归
./mvnw -pl engine -am -Dtest=ProjectMemoryPromotionPolicyTest,ProjectMemoryConsolidationEngineTest,ProjectMemoryOperationWorkerTest,ProjectMemoryReconciliationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Pi资源变更时在 `bootstrap/src/main/resources/executor/pi` 执行 `npm test`，再按AGENTS重建该目录Dockerfile与Dockerfile.qa。前端提交前完整执行：

```bash
cd frontend
node --experimental-strip-types --test test/*.test.ts
npm run typecheck
npm run build
```

真实数据库：状态/事件/CAS/派发变更必须跑 `PostgresRdTaskStateAtomicRealSmokeTest`；finalization变更跑 `PostgresRequirementStageFinalizationRealSmokeTest`。读取聚合也必须新增真实PG snapshot测试。测试数据库使用测试连接，密码从现有环境注入，不写到报告或命令示例。

已核对 opt-in：ProjectAgentMemoryPostgresRealSmokeTest用 `-Drd.integration.stage-finalization.enabled=true`；PostgresDeliveryObservabilityRealSmokeTest用 `-Drd.integration.delivery-observability.enabled=true`。其他smoke在执行前读取测试注解/配置并记录实际开关；skipped不算pass。

每批提交前按RULE运行 `./mvnw test` 与该批真实HTTP链；布局相关由前端完成浏览器验证。业务实装/验收后归档时运行 `openspec validate --all --strict`。不在仅计划阶段跑业务构建来制造“已验证实现”的印象。

每份验收报告最少包括：源码SHA/dirty diff摘要、jar hash、Pi image digest、配置名称与生效值（脱敏）、case/run/task/command/stage/round/audit identity、输入/证据hash、实际命令与退出码、HTTP关键字段、DB不变量、远端副作用、失败分类、未验证项。

## 10. 给后端 Agent 的开工指令

```text
在 /Users/wish233/Documents/RD-Bot 实施本计划，每批独立worktree与第2节指定分支。
先读RULE.md、当前主spec、P3交接/W1e证据、本计划与完整原计划；不要按旧header重做Phase1/P3。
先B00-B03：核对构建物、部署新版、W1g完整交付、W2 ASK与W3pause真机；部署/故障注入遵循接手会话授权。
P3-W1已由W1e满足原退出条件，W1g是新版本闭环验证，不能把两者混写。
随后B04-B06交付Coding内MEA读取和stage完整结果给前端；先锁定DTO，不修改调度来迎合页面。
四角色仍是完整流程；Coding内MEA只引用既有QA Attempt/Host审计，不创造新角色。
Phase2已有AuditedGapSection与两处previousFailure反馈，先核对其余checkpoint入口和未真机项。
Phase4-7按B12-B24逐批delta实施；保持Host完成权、版本/fence/operation/reconcile边界。
严禁把resultPreview当完整产物、把current head当旧Manager的状态、把命令重试次数当Agent Attempt。
不要接管其他任务的P3归档/未提交文件，不恢复已下线评测控制台，不复制secret，不盲重跑远端副作用。
交接必须列出实际测试/HTTP/数据库/真机证据与缺项；未通过的任务保持未勾选。
```

## 11. 本轮计划交付检查

本轮仅新增两份计划，未修改业务代码或既有P3归档资料。已检查B00–B24连续、原Task9–15覆盖、前端F01–F10依赖、拟新增与既存能力区分、接口字段/时间/identity一致性，以及Markdown围栏和占位词。没有运行业务测试、HTTP/PG/真机、package/部署，没有创建开发分支、commit或archive。后续实施者必须按各任务留下新的执行证据。
