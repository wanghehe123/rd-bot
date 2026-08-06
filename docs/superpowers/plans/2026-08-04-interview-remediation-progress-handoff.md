# RD-Bot 面试改造进度交接

> 日期：2026-08-04  
> 分支：`fix/interview-remediation-loop`（基于 `main`，**尚未 commit / 尚未 push**）  
> 计划：`docs/superpowers/plans/2026-08-03-rd-bot-interview-remediation-execution-plan.md`  
> 核对方式：对照执行计划 WP-0～WP-8 + 工作区 diff / 未跟踪文件 + 已跑通的定向测试切片

## 1. 一句话结论

**截至 2026-08-06 复盘：handoff 完成度偏乐观（尤其 WP-3、WP-4）。工程切片约 35–45%；P0 面试退出门槛约 40%（WP-1 强，WP-2/3 弱）。收尾执行计划见 `docs/superpowers/plans/2026-08-06-interview-remediation-remaining-completion-plan.md`（Target A = 面试可信；Target B = 全量计划）。勿把 `resume_optimized.md` 一并提交。**

### 审计校正完成度（相对原 handoff）

| WP | 原 handoff | 2026-08-06 审计 | 备注 |
|----|----------:|---------------:|------|
| WP-0 | ~60% | **~50%** | `docs/superpowers/qa/*` 仍被 gitignore |
| WP-1 | ~85% | **~80%** | ledger 真；缺 branch 幂等 / 双实例证明 |
| WP-2 | ~65% | **~63% / 默认退出 ~30%** | Coding 仍 bridge+明文密钥 |
| WP-3 | ~70% | **~25–30%** | Spring Oracle 未注入 adapter；bundle 可缺省绕过 |
| WP-4 | ~80% | **~68%** | 仅近终态/终态 CAS；热路径仍 upsert |
| WP-5 | ~60% | **~57%** | host reject 可用；无 Redis half-open |
| WP-6 | ~60% | **~58%** | 仅 `recover()` 公平 |
| WP-7 | ~50% | **~48%** | iterative 正确未默认接线 |
| WP-8 | ~40% | **~45%** | skeleton+verify OK；无 raw 跑数 |

审计子代理：[WP-1/4](71821d50-8cb6-4c81-afa1-bcf8f29cc637) · [WP-2/5](8b32ade8-5549-4cca-a9f6-ea20c0ff0a35) · [WP-3](b760a362-a344-4369-8116-0d74879ba128) · [WP-0/6/7/8](ba5ecf26-0965-4eff-bae6-0ff10bddc765)

## 2. 工作区状态（交接时核对）

| 项 | 状态 |
|---|---|
| Git 分支 | `fix/interview-remediation-loop` |
| 相对 `main` 已提交 commits | **无**（工作全在 uncommitted / untracked） |
| 已修改文件（tracked） | ~27 个，约 +2291 / −83（含误脏的 `resume_optimized.md` 1 行） |
| 未跟踪核心产物 | `engine/.../publication/**`、Postgres publication store/mapper、reconcile adapter/scheduler、`ContainerSecurityPolicy`、`FindOpenPullRequest*` / `FindBranchHead*`、执行计划 md |
| `docs/qa/*` WP-0 stubs | **磁盘存在**，但被 `.gitignore:40 docs/qa/*` 忽略，默认 `git add` 进不了仓库 |
| Dynamic `/loop` | 待命心跳（约 30m）；无 SMALL 指令则只读拉长 |
| `cursor-goal` | active，turns **5/8**；objective 文案仍停在较早 checkpoint（publisher open-PR），**落后于实际进度** |

### 保护约束（继续执行时必须遵守）

- 不改 `RULE.md`；不擅自改执行计划正文（除非用户明确要求演进）。
- **不要提交** 用户私有的 `resume_optimized.md` 脏改。
- Host / bridge / Prompt 协议对齐；Pi 路径变更需 `npm test`（pi）+ `DockerPiAgentExecutorTest`。
- 禁止一个 mega-PR；按 WP 切片合并。

## 3. 工作包完成度矩阵

| WP | 计划退出门槛 | 本分支实际 | 完成度 |
|---|---|---|---|
| **WP-0** 指标与故障矩阵 | Q1–Q12 口径 + 故障矩阵 + 可复现元数据 | `docs/superpowers/qa/*` + `benchmarks/interview-claims/` | **~60%** |
| **WP-1** 外部发布幂等与对账 | 崩溃窗口不重复分支/PR | Ledger + reconcile + crash-window tests | **~85%** |
| **WP-2** Pi 容器安全 | 恶意仓无法读密钥 / 越权写 / 任意外传 | policy + opaque lease + network=none when relay on；sidecar 未完 | **~65%** |
| **WP-3** Host Oracle | Host 独立业务断言 | AssertionSpec + gate on QA path | **~70%** |
| **WP-4** 主任务 CAS | `rd_tasks.version` 拒绝过期写 | DDL + CAS API；近终端/终态（committed/reporting/complete/reject/needs-human/retryable/cancel/dead-letter）已迁 CAS；更早中间态仍 upsert | **~80%** |
| **WP-5** Provider 路由降级 | 可审计降级 | Gate + catalog + orchestrator reject；`FAILED_VALIDATION` 允许纯生成 fallback；`WAITING_POLICY`→人工 | **~60%** |
| **WP-6** 公平调度背压 | 百任务不饥饿 | selector + claim planner + recover() 用 listInFlight 计项目在途 | **~60%** |
| **WP-7** 迭代检索评测 | 多轮检索+停止 | StopGate + Loop + `retrieveIterative` | **~50%** |
| **WP-8** 指标实验包 | 简历数字一键复现 | package skeleton + verify；数字仍待复现 | **~40%** |

## 4. 已落地切片明细（可交接验证）

### 4.1 WP-1 Publication Ledger & Reconcile

**领域（engine）**

- `RequirementPublication` / `RequirementPublicationStatus` / `RequirementPublicationReplayDecision`
- `RequirementPublicationLedger`：`prepare`、`markBranchConfirmed`、`markPullRequestConfirmed`、`markCommitted`、`markUnknownRemoteResult`、`reconcileBranchConfirmed`、`reconcilePullRequestConfirmed`、`deferReconcile`、`decideReplay`
- `RequirementPublicationStore` + `InMemoryRequirementPublicationStore`（含 `findDueForReconcile`）
- `RequirementPublicationReconcilePort` + `RequirementPublicationReconciliationService`
- `RequirementDeliveryEngine`：发布前 PREPARED；`resolvePublicationRemotePlan`（复用/跳过/WAIT）；超时/5xx → UNKNOWN；resume 前 `maybeReconcileUnknownPublication`

**基础设施（bootstrap / exec）**

- DDL：`rd_requirement_publications`（`p0_knowledge_productionization.sql`）+ reconcile 部分索引
- `PostgresRequirementPublicationStore` + mapper（insert 幂等 + version CAS）
- `CodePlatformPort.findOpenPullRequest` / `findBranchHead` + GitHub/Mock 适配
- `EngineRequirementPullRequestPublisherAdapter`：open PR + `taskId`/`operationId` marker 复用
- `EngineRequirementPublicationReconcileAdapter`
- `RequirementPublicationReconcileScheduler`（`rd.requirement-publication.reconcile.*`，默认 enabled）

**代表性测试**

- `RequirementPublicationReconciliationTest`
- `RequirementDeliveryEngineTest`（PREPARED / timeout→UNKNOWN 等）
- `RequirementDeliveryResumeFromCheckpointTest`（reuse / skip push / WAIT / reconcile PR / reconcile branch）
- `EngineRequirementPullRequestPublisherAdapterTest`、`GitHubCodePlatformAdapterTest`
- `PostgresRequirementPublicationStoreTest`、`InMemoryRequirementPublicationLedgerWiringTest`
- `RequirementPublicationReconcileSchedulerTest`、`EngineRequirementPublicationReconcileAdapterTest`

**建议验证命令**

```bash
./mvnw -pl bootstrap,engine,exec -am \
  -Dtest=GitHubCodePlatformAdapterTest,EngineRequirementPullRequestPublisherAdapterTest,RequirementPublicationReconciliationTest,PostgresRequirementPublicationStoreTest,InMemoryRequirementPublicationLedgerWiringTest,RequirementPublicationReconcileSchedulerTest,EngineRequirementPublicationReconcileAdapterTest,RequirementDeliveryResumeFromCheckpointTest,RequirementDeliveryEngineTest#shouldMarkUnknownRemoteResultWhenBranchPushTimesOut,RequirementDeliveryEngineTest#shouldCreatePreparedPublicationBeforeBranchPush \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### 4.2 WP-2 Container Security（部分）

- `ContainerSecurityPolicy` + `ContainerRunRequest` / `ProcessContainerRunner`（`--read-only`、cap-drop、no-new-privileges、limits、tmpfs）
- `DockerPiAgentExecutor`：coding/QA policy；Reviewer/Architect/QA `/work/repo:ro`
- `rd.executor.pi.credential-relay-enabled`（默认 `false`；`true` 时 fail-closed，**无真实 relay**）
- QA evidence：`result-tool.mjs` / `rd-pi-bridge.mjs` 与 Host 对齐的 manifest 预校验（协议裂缝修补）

**建议验证命令**

```bash
cd bootstrap/src/main/resources/executor/pi && npm test
./mvnw -pl exec,bootstrap -am \
  -Dtest=DockerPiAgentExecutorTest,ProcessContainerRunnerTest,ContainerRunnerContractTest,PiAgentExecutorPropertiesTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### 4.3 WP-0（仅本地 stub）

- `docs/qa/interview-scenario-acceptance-plan.md` — Q1–Q12 矩阵骨架  
- `docs/qa/fault-injection-matrix.md` — 故障 ID → WP 映射骨架  

若要进仓库：强制 `git add -f docs/qa/...`，或迁到不被 ignore 的路径（如 `docs/superpowers/qa/`）。

## 5. 明确未完成 / 缺口

### WP-1 剩余

- 端到端崩溃窗口验收（push 成功后杀进程、GitHub 201 后 DB 失败等真实/半真实样本）
- `UNKNOWN → PREPARED`（确认未执行）与完整 `NEEDS_HUMAN` 自动路径
- 对账成功后 **自动重投 REJECTED 任务**（当前只推进 ledger，不自动 resubmit）
- 远端 head 与 `candidatePatchSha256` 的严格一致性策略（branch-head 仅确认 tip 存在）

### WP-2 剩余

- Host credential relay 真实实现（eval 有 pattern；Pi 生产路径未接）
- 默认 `network=none` / 受控 egress（今日仍 `bridge`）
- QA 独立可写 workspace 副本（注释中仍有已知限制）
- Claude executor 与 Pi 安全 policy 对齐（若仍走 Claude 路径）

### WP-4 剩余

- 中间态（MATERIAL_*/CONTEXT_*/PLAN_*/WAITING_*/EXECUTING/VALIDATING/PR_CREATING/…）仍 `transitionAndSave` 盲写
- fencing token 与「管理台编辑 vs 状态推进」分 command 未拆

### WP-5 剩余

- Redis/PostgreSQL 共享 half-open probe
- 工具副作用路径：fallback 前查 operation ledger + 强制干净工作区
- 高风险任务默认禁弱模型降级（策略表）

### WP-2 / WP-3 / WP-6～WP-8

- WP-2 relay sidecar + 受控 egress；WP-3 Host Oracle 仍是最大 P0 缺口
- WP-6 尚未 stage command 化；WP-7 iterative 勿默认接线；WP-8 简历数字待复现

## 6. 面试口径建议（当前可说 / 不可说）

**可说（有代码+测试支撑）**

- 发布侧有 operation ledger，超时/5xx 进 UNKNOWN，resume/scheduler 用远端查询对账，避免盲重放。
- Pi 容器具备只读根、cap-drop、角色 repo 只读挂载；QA evidence 在容器内预校验与 Host 对齐。
- credential-relay **开关已预留**，默认行为未变。
- 主任务终端态（cancel/complete/reject/needs-human）走 `version` CAS，与 cancel 竞态只允许一条边成功。
- Provider fallback 有能力门控；不安全降级进 `WAITING_POLICY`/人工，而非静默换弱模型。

**不可说 / 需降调**

- Exactly-Once、强沙箱无外传、Host Oracle 100% 检出、全状态机 fencing、百任务公平调度、简历数字一键复现。

## 7. 建议的下一步（交接后优先序）

1. **整理提交**：按 WP 切 commit（WP-1 / WP-4+5 / WP-2 / docs）；排除 `resume_optimized.md`。  
2. **选线开工**：  
   - A：WP-4 中间态 CAS 或 fencing token  
   - B：WP-2 relay + 受控 egress  
   - C：WP-3 Host Oracle（最大、P0 缺口）  
3. `/loop` 可继续 SMALL 切片；大包需人工点优先级。

## 8. 关键代码入口（给下一位）

| 主题 | 入口 |
|---|---|
| 发布状态机 | `engine/.../publication/RequirementPublicationLedger.java` |
| 发布编排 | `engine/.../RequirementDeliveryEngine.java`（`publishValidatedResult` / `resolvePublicationRemotePlan`） |
| 对账服务 | `RequirementPublicationReconciliationService` + `RequirementPublicationReconcileScheduler` |
| GitHub 查询 | `GitHubCodePlatformAdapter.findOpenPullRequest` / `findBranchHead` |
| 容器硬化 | `ContainerSecurityPolicy`、`ProcessContainerRunner`、`DockerPiAgentExecutor` |
| QA 协议 | `bootstrap/.../executor/pi/src/result-tool.mjs`、`rd-pi-bridge.mjs` |
| WP-0 stubs | `docs/qa/interview-scenario-acceptance-plan.md`、`docs/qa/fault-injection-matrix.md`（gitignore） |

## 9. 进度自评（对照计划原则）

| 原则 | 评估 |
|---|---|
| 先正确性/安全，再能力标签 | 方向正确；P0 仍缺 WP-3 |
| 状态机与外部副作用分离 | WP-1 ledger 已落地主干 |
| Host 授权与判定 | 安全边界部分落地；业务 Oracle 未做 |
| 可复现实验 | WP-0 仅 stub；无实验包 |
| 分层 engine/exec vs bootstrap | 基本遵守 |

**总体：** 相对计划全文约 **25–35%** 工程进度（以 P0 加权则更高：WP-1 接近可演示，WP-2 半程，WP-3 未动）。适合作为「发布一致性 + 容器硬化」阶段性演示，**尚不能**宣称完整面试场景 P0 闭环。
