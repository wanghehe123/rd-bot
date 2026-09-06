## Why

阶段 1 已把完成权收口到 `AuditedTaskState`，但新 attempt 的角色 Prompt 仍注入上一轮 `errorMessage`（≤4000 字）并把 compact 交接的 `environmentNotes`/`facts[]` 写成「已实测验证，直接沿用」。JVM 中断恢复或 HOST_VERIFY_FIX 时，Executor 会复读崩溃文案与 javac 墙，而不是 Host 已审计缺口。阶段 2 要把每个角色 episode 变成只携带有界审计缺口的 fresh 请求。

## What Changes

- 将 `previousFailureFeedbackSection` / `hostVerifyFailureFeedbackSection`（Orchestrator 与 `RequirementDeliveryEngine` 同构段）替换为 `AuditedGapSection`：只含 `AuditedTaskState` head 的缺口 ID、证据 URI、state version/hash；≤16 个 ID、≤2000 字；**禁止**把原始 `errorMessage` 写入 Prompt / `PROMPT_SNAPSHOT`。
- `HOST_VERIFY_FIX` 的 `HostVerifyRemediationPackageBuilder` Prompt 段不得再拼接 javac/`errorMessage` 原文；hash-bound 附件 JSON 可保留有界 sanitized 字段。
- Compact 交接与角色指令删除「已实测验证，直接沿用」；`environmentNotes`/`facts[]` 默认 `UNTRUSTED`，仅已被 `AuditRun` 晋升的 fact 标 `VERIFIED(auditRunId=…)`。compact JSON 不再向下传递 `errorMessage`。
- Pi `QA_AGENT` 使用 `RepairWorkspaceFactory.createProviderAttempt`：独立 `provider-attempts/<stageRunId>/` 的 `repo/`/`input/`/`output/`，共享任务级 `/work/cache`；启用并接线 `DockerPiAgentExecutorTest` 两条原 `@Disabled` 用例。

## Capabilities

### New Capabilities

- `requirement/fresh-executor-episode`：需求交付角色 attempt 的 Prompt 只能携带 Host 已审计缺口与信任标注，QA episode 工作区与 Coding 隔离。

### Modified Capabilities

- 无。`requirement/audit-only-writeback` 的完成/写回合同不变；本 change 只改 episode Prompt 与 QA 工作区隔离，不改 `COMPLETED` 门、`AuditRun` schema 或 `gate-mode`。

## Impact

- **engine**：新增 `AuditedGapSection`；`RequirementAgentStageOrchestrator` 与 `RequirementDeliveryEngine` 的失败反馈段、compact 交接、角色 facts 指令；`HostVerifyRemediationPackageBuilder` Prompt 段。
- **exec**：`DockerPiAgentExecutor` 对 `QA_AGENT` 改走 `createProviderAttempt`。
- **测试**：`AuditedGapSectionTest`、`RequirementAgentStageOrchestratorTest`、`RequirementDeliveryEngineTest`、`HostVerifyRemediationPackageBuilderTest`、`DockerPiAgentExecutorTest`（取消 `@Disabled`）。
- **真机**：waimai ENFORCE P2-W1（kill JVM 后续跑 Prompt 不含旧 `errorMessage`）、P2-W2（HOST_VERIFY_FIX 含 `GATE-BUILD` 不含 javac 墙）、P2-W3（QA 协议重试不含上一轮 stderr 墙）。
- **非目标**：动态 Manager、契约版本化、`RoleContextBuilder` JSON `trust` 字段（阶段 6）、`maxAgentTurns` 账本（阶段 5）、把 QA 产品补救 JSON 的来源改成 `AuditRun`（hash-bound 包可保留）。

## 证据与来源

- 计划（PLAN_OR_DECISION）：`docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md` 阶段 2；`docs/superpowers/plans/2026-09-04-mea-next-phases-waimai.md` Task 10。
- 当前代码（2026-09-05）：`RequirementAgentStageOrchestrator#previousFailureFeedbackSection`、`#hostVerifyFailureFeedbackSection`、compact 交接「已实测验证」；`RequirementDeliveryEngine#previousFailureFeedbackSection`；`HostVerifyRemediationPackageBuilder` Prompt 含 `Failure: ` + `errorMessage`；`DockerPiAgentExecutor` `workspaceFactory.create(command)` 对 QA 与 Coding 共用任务 `repo/`。
- 当前测试：`RequirementAgentStageOrchestratorTest.product_defect_then_succeeded_retries_coding_once_then_dispatches_qa` 断言 Prompt 含 `cannot find symbol Foo`；`RequirementDeliveryEngineTest.shouldInjectPreviousFailureFeedbackIntoRetryPrompt` 断言含 `缺少必填字段`；`DockerPiAgentExecutorTest` 两条 `@Disabled` QA isolation。
- 历史资料：`docs/openspec/historical-spec-provenance-audit.md` 未收录本 Prompt 合同；`docs/superpowers/specs/2026-07-28-pi-qa-protocol-and-workspace-hygiene-spec.md` 将 QA `provider-attempts/` 标为延后——本 change 把它从延后升为阶段 2 退出条件。
- 本轮命令：只读检索上述符号；`openspec new change mea-fresh-executor-episode`。
