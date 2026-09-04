## Context

动机见 `proposal.md`。行为合同见 `specs/requirement/host-verification-pipeline/spec.md`。

当前入口仍是 `RequirementDeliveryEngine.submit` → `RequirementAgentStageOrchestrator.run`。四角色顺序由 `AgentRole.requirementDeliveryOrder()` 固定。Coding 成功后循环直接进入 `QA_AGENT`。QA 返工由 `qaRemediationAllowed` + `qaMaxRemediationPasses=1` + `isCodingRemediationRequested` 守门，且 `createQaRemediationAttempts` 会同时新建 Coding 和 QA。角色 attempt 硬上限是 `RequirementDeliveryEngine.MAX_ROLE_ATTEMPTS = 3`。

历史资料按 `docs/openspec/historical-spec-provenance-audit.md` 使用：QA 证据与生产模式以 `2026-07-28-qa-evidence-reference-and-production-mode-spec.md` 为准；禁止第五角色以 `2026-07-13-rd-task-stage-retry-and-ai-delivery-review-design.md` 为准；attempt 上限来自后续代码，不以 2026-07-25 文档路径为合同。

## Goals / Non-Goals

**Goals:**

- 在编排器里把「Coding 成功 → 宿主 BUILD → 宿主 STATIC → 才派发 QA」做成可恢复、可审计的控制面步骤。
- 验证真值在 PostgreSQL；命令执行在 bootstrap/exec 适配层；engine 只通过 Port 要结果。
- 廉价返工与 QA 浏览器返工分账，共享 Coding attempt 总帽。

**Non-Goals:**

- 不把验证做成 `AgentRole`，不改四角色顺序。
- 不在本 change 实现前端页面（契约冻结，前端另计划）。
- 不重写 Host Oracle 业务断言，不把 HTTP/SQL/BROWSER assertion 并进 BUILD。
- 不解决 Vite `npm run dev` 作为 QA **startCommand** 的历史问题（只保证 BUILD 不用 dev server）。

## Decisions

### D1. 独立 VerificationRun，不新增角色

仿 `AiReviewRun`：`HostVerificationRun` 与 `AgentStageRun` 用 ID 关联，不进入 `AgentRole`。

```
CREATED → PREPARING → BUILDING → STATIC_CHECKING → SUCCEEDED
                 │         │              │
                 └─────────┴──────────────┴→ FAILED_RETRYABLE
                                            → FAILED_NEEDS_HUMAN
                                            → SKIPPED_DOCS_ONLY
                                            → CANCELLED
```

终态不得原地复活。返工创建 `attemptNo+1` 的新 run，`parentRunId` 指向上一轮。

备选：把验证做成第五角色。否决，与重试 spec §3.3 冲突。

### D2. 编排插入点在 Orchestrator，执行走 Port

`RequirementAgentStageOrchestrator` 在 `CODING_AGENT` 成功且即将处理 `QA_AGENT` 之前调用 `HostVerificationPort.verify(...)`。engine 禁止直接起进程或 Docker。

**Durable-path ownership（2026-09-03，`mea-audit-only-writeback` 对齐）：**
本决定的 orchestrator 插入点只覆盖 legacy `executeAgentStages(AgentWorkflowPlan.production())`。生产逐条 command 路径使用 `RequirementDeliveryEngine.boundedRolePlan`，其中 `hostVerifyRemediationEnabled=false`，因此 orchestrator 内循环不会在 durable 派发上跑 BUILD/STATIC。**durable 路径由 `mea-audit-only-writeback` 的 `HOST_VERIFY` command 承接**：`roleContinuation(CODING_AGENT)` → `("REQUIREMENT_DELIVERY","HOST_VERIFY")`，该 command 调用同一 `HostVerificationPort.verify`，不改本 change 的 D1/D3/D4/D6/D7。本 change 在 durable 路径落地前不要 archive 为「生产路径已由 orchestrator 门覆盖」。

bootstrap 适配器：

1. 从 Coding 成功 attempt 取宿主已校验的 `candidate-patch.diff`。
2. 用 `CleanHostVerifierWorkspaceFactory` 重放到干净工作区。
3. 挂同一 task 的 `/work/cache`。
4. 按 profile 解析命令，用 `ProcessBuilder(List<String>)` 或现有受限 runner 执行；禁止 `/bin/sh -c` 拼接用户原文以外的包装。
5. 收集 `verify-evidence/**` 日志，算 SHA-256，写入 Store 与对象存储。

备选：做成持久化 `rd_requirement_stage_commands` 的新 business stage。能更好跨进程恢复，但会扩大派发面。第一期在当前 delivery worker 线程内同步执行（编译本就比 QA 便宜）；进程被杀时 VerificationRun 留在非终态，恢复路径按 `FAILED_RETRYABLE` + 新 run 处理，不复活旧 run。

### D3. 命令解析复用 QA profile，字段拆开

扩展 `QaValidationProfile` / `QaValidationProfileCommand` / `QaExecutionProfile`：

| 字段 | 用途 |
|---|---|
| 现有 `startCommand` | 仅 QA 启动应用 |
| 现有 `regressionCommands` | 仅 QA Agent 业务回归 |
| 新增 `buildCommands` | 宿主 BUILD |
| 新增 `staticCommands` | 宿主 STATIC |

优先级：任务覆盖 > 项目配置 > 仓库 `.rd-bot/qa-profile.json` > 自动探测。空列表表示「本层未声明」，继续向下；显式 `[]` 且来源是任务/项目覆盖时表示「该步跳过」。自动探测规则：

- 有 `package.json` + `scripts.build` → BUILD 含与 lockfile 匹配的安装和 `npm run build`；有 `scripts.test` 则追加 `npm test`。
- Next.js / Vite BUILD 不得含 `dev`。
- 有 `scripts.typecheck` 或 `tsconfig.json` → STATIC 含 `npm run typecheck` 或 `tsc --noEmit`。
- 有 `scripts.lint` → STATIC 追加该脚本。
- 有 `pom.xml` / `mvnw` → BUILD 为 `./mvnw -q test`；STATIC 仅在仓库已声明 checkstyle/spotbugs 插件时添加对应目标。
- 探测失败 → `FAILED_NEEDS_HUMAN` / `REQUIREMENT_AMBIGUITY`，不得空跑当成功。

命令校验沿用 `QaValidationProfileCommand.validateCommand`，并额外拒绝以 `npm run dev`、`next dev`、`vite --host` 作为 BUILD 项。

### D4. 廉价返工只建 Coding

`AgentWorkflowPlan` 增加：

- `hostVerifyRemediationEnabled`（生产 true）
- `hostVerifyMaxRemediationPasses = 2`

编排器维护 `hostVerifyRemediationCount`，类似现有 `qaRemediationCount`。验证因 `PRODUCT_DEFECT` 失败且未超预算时：

- 将失败 Coding attempt 保持终态。
- `createHostVerifyRemediationAttempt(taskId)` 只创建新的 `CODING_AGENT` pending stage。
- 递归 `runInternal`，把验证失败摘要当作「上一轮失败反馈」注入 Coding prompt（截断 4000 字，复用现有反馈槽）。
- **不**调用 `createQaRemediationAttempts`。

`MAX_ROLE_ATTEMPTS=3` 仍在 `ensureRequirementStages` / `createQaRemediationAttempts` / 新方法里同时生效。

失败分类复用 QA 枚举语义，避免第二套词表：

| 现象 | failureCategory | 动作 |
|---|---|---|
| 编译/单测/typecheck/lint 非 0，且补丁已应用 | `PRODUCT_DEFECT` | 廉价打回 Coding |
| 安装源/网络/权限/镜像 | `ENVIRONMENT` 或 `QA_INFRASTRUCTURE` | 人工 |
| 变更集不可判定又必须跑门 | fail-closed 跑门；工具缺失 | `ENVIRONMENT` / 人工 |
| 命令配置非法 | `REQUIREMENT_AMBIGUITY` | 人工 |

### D5. 重试相位

`TaskFailurePhase` 增加 `HOST_VERIFY`。`TaskRetryPointResolver`：若最新 `HostVerificationRun` 为失败终态且其后没有成功 Coding+Verify，恢复点为 `HOST_VERIFY`，`retryFromRole=CODING_AGENT`。禁止从错误字符串猜测。

手工从 Coding 重试保持原语义：新 Coding attempt 成功后必须跑新的 VerificationRun。

### D6. 持久化

`p15_host_verification.sql`：

- `rd_host_verification_runs`：id、task_id、coding_stage_run_id、parent_run_id、attempt_no、status、docs_only、failure_category、error_message、remediation_count、created/started/finished。
- `rd_host_verification_steps`：run_id、step（BUILD/STATIC）、status、commands_json、exit_code、duration_millis、log_artifact_id、error_message。
- 证据对象复用 `rd_qa_evidence_objects` 的形态，或同表加 `artifact_type` 前缀 `VERIFY_*`，`stage_run_id` 存 coding stage 或 verification run 映射列。推荐独立 `rd_host_verification_artifacts`，避免 QA 画廊被编译日志污染；管理 API 分开列出。

生产 Store 走 PostgreSQL；memory 实现仅单测。

### D7. 管理 API（后端合同，前端另做）

只读：

- `GET /admin/rd-tasks/{taskId}/host-verifications`
- `GET /admin/rd-tasks/{taskId}/host-verifications/{runId}`
- `GET /admin/rd-tasks/{taskId}/host-verifications/{runId}/evidence/{artifactId}/content`

写配置：现有 QA profile PUT 增加可选数组 `buildCommands`、`staticCommands`；缺省兼容旧客户端（视为未声明，走下层）。

`execution-overview` 增加当前验证状态字段，或由详情页并行拉 host-verifications。推荐并行拉，避免把 overview 记录膨胀成第二真值。

### D8. 模块边界

```
bootstrap.controller.HostVerificationController
        → engine.HostVerificationQueryPort（只读）
bootstrap.executor.HostVerificationExecutorAdapter
        → exec 工作区重放 + 进程执行
        → engine.HostVerificationStore（写结果）
engine.RequirementAgentStageOrchestrator
        → engine.HostVerificationPort
rag.QaValidationProfile*（命令配置领域）
```

依赖方向保持 `bootstrap → engine/exec/rag`，`engine → rag`。

## Risks / Trade-offs

- [delivery worker 同步跑编译拖长租约] → 编译超时单独墙钟（建议 10 分钟，可配）；心跳沿用现有 job lease；超时记 `ENVIRONMENT` 或 `QA_INFRASTRUCTURE`，不打回 Coding。
- [自动探测命令在陌生仓库误杀] → 探测失败走人工，不空跑成功；项目可显式配置命令。
- [廉价返工 2 次 + QA 返工 1 次顶满 attempt 3] → 文档与 UI 必须显示两本账；总帽以 `MAX_ROLE_ATTEMPTS` 为准。
- [干净工作区无 node_modules] → 挂 task `cache/`，禁止删 cache；第一次仍可能慢。
- [与 Host Oracle 工作区重复创建] → 第一期允许各建各的；不把 assertion 执行并进验证门。

## Migration Plan

1. 先加 DDL 与 Store，默认不改变编排（feature 由 plan 字段关闭可测）。
2. 生产 `AgentWorkflowPlan.production()` 打开宿主验证与 2 次廉价返工。
3. 旧任务无验证记录时，恢复/重试从最新 Coding 成功后再跑验证，不回放历史 QA。
4. 回滚：将 `hostVerifyRemediationEnabled=false` 且编排跳过 Port（仅用于紧急；正式回滚应保留表）。默认路径不得静默跳过验证。

## Open Questions

- 验证日志对象存储桶是复用 `rd-qa-evidence` 还是新建 `rd-verify-evidence`：实现时选独立前缀 `s3://rd-qa-evidence/verify/`，避免新桶运维，API 仍与 QA 画廊分离。
