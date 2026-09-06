## 1. Audited gap section

- [x] 1.1 先写 `AuditedGapSectionTest`：head 的 PENDING `GATE-BUILD` + UNTRUSTED claim + sourceRefs 渲染「已审计缺口（Host）」；ID ≤16、全文 ≤2000；不含传入的 javac/`errorMessage` 子串；head 为 null 返回空串。再实现 `engine/.../audit/AuditedGapSection.java`
- [x] 1.2 先改 `RequirementAgentStageOrchestratorTest.product_defect_then_succeeded_retries_coding_once_then_dispatches_qa`：断言含 `GATE-BUILD` / 「已审计缺口」，**不含** `cannot find symbol Foo` 与「上一轮失败反馈」。再把 Orchestrator 的 `previousFailureFeedbackSection` 与 `hostVerifyFailureFeedbackSection` 改为调用 `AuditedGapSection`
- [x] 1.3 先改 `RequirementDeliveryEngineTest.shouldInjectPreviousFailureFeedbackIntoRetryPrompt`：第二轮 Prompt 不含 `缺少必填字段` 与「上一轮失败反馈」；有 head 缺口时含缺口标题。再改 Engine 同构段
- [x] 1.4 先改 `HostVerifyRemediationPackageBuilderTest`：Prompt 段不含 `errorMessage` 原文，仍含附件路径与 hash。再改 `HostVerifyRemediationPackageBuilder` Prompt

## 2. Compact 交接信任标注

- [x] 2.1 先写/改 Orchestrator 与 Engine 中 compact 交接测试：渲染结果不含「已实测验证，直接沿用」；含 `UNTRUSTED`；compact JSON 无 `errorMessage` 键；已 COMPLETED 的 FACT 标 `VERIFIED(auditRunId=`。再改 compact 渲染与 architect/coding 角色指令

## 3. QA provider-attempt 隔离

- [x] 3.1 去掉 `DockerPiAgentExecutorTest` 两条 `@Disabled`，先跑确认失败（QA 仍挂任务 `repo/`）
- [x] 3.2 `QA_AGENT` 走 `workspaceFactory.createProviderAttempt(command, snapshot.stageRunId())`，共享任务 `cache/`；`/work/repo` 保持可写；再跑上述两测与既有 QA 指纹测

## 4. 聚焦验证与真机

- [x] 4.1 `./mvnw -pl engine -am -Dtest=AuditedGapSectionTest,RequirementAgentStageOrchestratorTest,RequirementDeliveryEngineTest,HostVerifyRemediationPackageBuilderTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] 4.2 `./mvnw -pl exec -am -Dtest=DockerPiAgentExecutorTest#shouldMountQaRepoFromAnIndependentProviderAttemptWorkspace,DockerPiAgentExecutorTest#shouldGiveQaAnIndependentCandidatePatchWorkspaceWhileRetainingTaskCache,DockerPiAgentExecutorTest#shouldComputeWorkspaceFingerprintsForQa -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] 4.3 云端部署后 P2-W1：honest 任务 CODING 中途按 `start-backend.sh` 杀 JVM，新 `PROMPT_SNAPSHOT` 不含旧 `errorMessage`，ENFORCE 可完成（W1d `7501852820702892032` 缺口合同；W1b PR #35 / W1c PR #36 证明 ENFORCE 可完成）
- [x] 4.4 P2-W2：真实 BUILD 失败后的 HOST_VERIFY_FIX Prompt 含 `GATE-BUILD`、不含截断 javac 墙（W2d `7501909358423445504` coding attempt 2 `PROMPT_SNAPSHOT` 含 `# 已审计缺口（Host）` / `GATE-BUILD` / `state_version`，不含 `cannot find symbol` / `error TS` / `npm --prefix client run build exited`；HOST_VERIFY `7501913405029224448` 为 `FAILED_RETRYABLE`/`PRODUCT_DEFECT`）
- [x] 4.5 P2-W3：QA provider-attempt 隔离已在 W2d QA 容器证实（`/provider-attempts/7501909363016208387/` → `/work/repo`，任务级 `cache/`，repo 可写）。协议重试快照本轮未出现：QA attempt 1 直接 SUCCEEDED，项目 `piQaRemediationV2Enabled=false`，未派发 `QA_PROTOCOL_RETRY`
