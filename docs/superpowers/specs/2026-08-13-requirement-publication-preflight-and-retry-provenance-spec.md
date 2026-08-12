# 需求发布预检、失败 provenance 与 QA 元数据通道

日期：2026-08-13
状态：已实施，并经 GitHub 私有仓 docs-only 冒烟验证（任务 `7493354884037742592` COMPLETED，PR #9）
范围：发布预检把 GitHub 缺席分支当成 Absent；终态失败必须留下可解析 provenance；QA docs-only 判定只走 `dockerMetadataJson`。

相关且仍有效的规格（本文不重复其正文）：

- QA 证据引用与 docs-only allowlist：`2026-07-28-qa-evidence-reference-and-production-mode-spec.md`
- Pi 生命周期与 `/work/cache`：`2026-07-28-pi-qa-protocol-and-workspace-hygiene-spec.md`
- 检查点重试与 AI 评审：`2026-07-13-rd-task-stage-retry-and-ai-delivery-review-design.md`
- 仓库约束：`RULE.md` §3.5.3、§3.5.5

过时交接 `docs/superpowers/plans/2026-08-12-checkpoint-bound-retry-handoff.md` 只保留结论指针，不再当作运行手册。

## 1. 已验证问题与根因

| 症状 | 不是什么 | 根因 |
| --- | --- | --- |
| 发布停在 `UNKNOWN_REMOTE_RESULT` / `WAIT_RECONCILE` | 项目没有 GitHub URL | `GET /commits/{workBranch}` 对尚不存在的 requirement 分支返回 **422 `No commit found for SHA`**。适配器只把 404/`not found` 当缺席，422 被抛成未知远程结果。 |
| `GET .../failure-recovery` 409 `RETRY_POINT_AMBIGUOUS` | 事件顺序错了 | 技术耗尽或发布失败写了任务终态，但没有匹配 version/fence 的 `rd_task_failure_provenance`。 |
| `/retry-preview` 409，同时 `/failure-recovery` 200 | preview 与 retry 语义不同 | preview 走无 provenance 的启发式；retry 走 snapshot。 |
| 容器按 docs-only 跳过浏览器，宿主拒绝“变更集不可判定” | Playwright/React 事件 | Pi 把 `qaCandidateChangedFilesJson` 写进 `repositoryMetadata`→`githubMetadataJson`；宿主只读 `dockerMetadataJson`。 |
| Pi `developer` 角色 HTTP 400（`opencode-go`） | 密钥没配 | `compat.supportsDeveloperRole=false` 必须放在 **`models[0]`**。Provider 级 compat 会被 Pi `applyExtension` 丢掉。 |

换一个“有 GitHub 链接的项目”不能单独修好 422 预检。上一次失败任务 `7493233919224057856` 已经绑着真实仓库；新任务 `7493354884037742592` 能 COMPLETED，是因为预检把 422 映射为 Absent。

## 2. 护栏

### 2.1 发布预检：缺席 vs 未知

`EngineRequirementPublicationReconcileAdapter`：`CodePlatformPort.findBranchHead` 空 = 确认缺席；传输错误必须抛。

`GitHubCodePlatformAdapter.findBranchHead`（HTTP 与 `GH_CLI_LOCAL_SMOKE`）：

- 确认缺席 → `Optional.empty()` → `RemoteBranchHead.Absent` → 允许 push：HTTP **404**；HTTP **422** 且 body 含 `no commit found`（空 body 的 422 同样视为缺席）；CLI stderr/stdout 含 `404` / `422` / `not found` / `no commit found`。
- 仍为未知 → 抛错 → `markPreparedPublicationUnknown`：超时、5xx、连接中断、其它 4xx。禁止重放 push/PR。

远端已有 head 但 operation/patch 标记不匹配 → `NEEDS_HUMAN`，不是 Unknown。

### 2.2 失败 provenance

每次新的任务终态失败，必须在同一最终化事务留下一条可解析 provenance：

| 失败种类 | 写入路径 | `failedStage` | `failurePhase` |
| --- | --- | --- | --- |
| 技术耗尽（命令 attempt 用尽） | `exhaustCommand`：先插 PREPARED marker，再写 provenance | 命令 stage | `phaseForStage`；`PUBLICATION` 与 `PUBLICATION:<opId>` 均为 `PR_PUBLICATION` |
| 计划内发布失败（含 `WAIT_RECONCILE`） | `finalize()`，不是 `exhaustCommand` | `PUBLICATION:<operationId>` | `PR_PUBLICATION` |

`ExternalEffectReceipt.isFinalizable()` 对 `UNKNOWN_REMOTE_RESULT` 仍为 false。`allowsOutcomeRecording` 只允许非 finalizable 的 PUBLICATION receipt 搭配失败 disposition，以便记录结局而不把未知结果当成成功提交。

佐证（corroboration）必须接受：

- 复用的 PREPARED marker（`attempt_no <= failedAttempt`）；
- 命令 `policy_run_id` 为空时的 checkpoint policy；
- 命令 stage 为 `PUBLICATION` 或 `PUBLICATION:<opId>`，且 ledger 行存在；
- 被 dispatch 复用、后又 FINALIZED 的 DEAD_LETTERED 命令 marker。

禁止 SQL 回写任务 version/fence 来“对齐”旧 provenance。历史任务 `7493233919224057856` 已用幂等 backfill 补过 v33/f34 的发布 provenance；不要再对该 ID 做发布重试（账本曾为 WAIT_RECONCILE）。新工作用新任务。

### 2.3 Preview 与 snapshot

`TaskRetryEngine.preview()` 必须返回 `failureRecoveryService.snapshot(taskId).retryPoint()`。`POST /retry` 已经走 snapshot。角色全部成功但发布失败时，preview 不得因为没有失败 `AgentStageRun` 而 409。

### 2.4 QA 元数据通道

判定键只允许出现在 `dockerMetadataJson`。生产者（Pi）必须写入该通道；宿主读者不得放宽到 github/repo metadata。docs-only allowlist 与三处锁步仍以 QA spec §3.3 为准。

### 2.5 Pi provider compat

`rd-pi-bridge.mjs` 把 `compat: { supportsDeveloperRole: false, supportsReasoningEffort: false }` 放在 `models[0]`。改这条路径后重建 `Dockerfile` 与 `Dockerfile.qa`，否则运行中的后端仍提供旧容器规则。

## 3. 验收

- [x] HTTP 422 `No commit found` 与 gh CLI 同等 stderr → `findBranchHead` 为空（`GitHubCodePlatformAdapterTest`）。
- [x] 发布失败写入 `PR_PUBLICATION` provenance；preview 与 snapshot 一致（engine 聚焦测试）。
- [x] Pi QA 键出现在 `dockerMetadataJson`（`DockerPiAgentExecutorTest#shouldPublishDeterminableQaCandidateChangedFilesInDockerMetadata`）。
- [x] 真实 GitHub 私有仓 docs-only 冒烟：项目 `7487468535443230720`，任务 `7493354884037742592`，约 9 分钟 COMPLETED，发布账本 `COMMITTED`，PR https://github.com/wanghehe123/next-js-16-sqlite-drizzle-local-kbr-20260724-001/pull/9 。四角色 attempt 1 均 SUCCEEDED。未 approve/merge。

已知非阻塞项（不要为了变绿而改生产行为）：

- `DockerPiAgentExecutorTest` 两条 aspirational：QA 只读 repo mount、QA 独立 candidate workspace 仍保留任务 cache。
- 冻结计划 `2026-08-11-checkpoint-bound-retry-dispatch-implementation.md` 的全量 HTTP/concurrency/crash 矩阵未宣称完成；已验证的是角色→QA→PR 主路径。

## 4. 验证命令

```bash
./mvnw -pl bootstrap -Dtest=GitHubCodePlatformAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl engine -Dtest=TaskRetryEngineTest#previewUsesAuthoritativePublicationProvenanceWhenNoAgentStageFailed -Dsurefire.failIfNoSpecifiedTests=false test
cd bootstrap/src/main/resources/executor/pi && node --test test/protocol.test.mjs
```

## 5. 失败处置

| 症状 | 先查 | 处置 |
| --- | --- | --- |
| `UNKNOWN_REMOTE_RESULT` 且 last_error 含 422 / No commit found | `GitHubCodePlatformAdapter.findBranchHead` 是否把 422 当缺席；运行中的 jar 是否含该修复 | 加载修复后**新建**任务；不要重放已 WAIT_RECONCILE 的 operation |
| `RETRY_POINT_AMBIGUOUS` | provenance 的 version/fence 是否等于任务快照；是否有 FINALIZED/PREPARED marker | 修最终化路径；禁止手改 version/fence |
| preview 409、recovery 200 | preview 是否仍走旧启发式 | 重启已含 snapshot 对齐的 jar |
| 容器 docs-only、宿主 UNDETERMINABLE | `dockerMetadataJson` | 修 Pi 生产者，不放宽宿主 |
| Pi 400 developer role | `models[0].compat`；镜像是否重建 | 重建两个 Pi 镜像 |
