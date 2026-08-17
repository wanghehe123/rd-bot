## Why

需求交付在 `CODING_AGENT` 成功后直接派发 `QA_AGENT`。编译、单测和静态检查要么靠 Coding 自报 `testStatus`，要么混在 20 分钟 Playwright 容器里。缺文件、typecheck 失败会先烧掉浏览器预算；现有 QA 返工还会同时新建 Coding 和 QA attempt。现在要把廉价的构建/静态门做成宿主真值，并在代码类失败时只打回 Coding（最多 2 次）。

## What Changes

- 在 `CODING_AGENT` 已 `SUCCEEDED` 之后、`QA_AGENT` 派发之前，增加独立的宿主验证状态机 `HostVerificationRun`（BUILD 然后 STATIC）。这不是第五个 `AgentRole`。
- BUILD / STATIC 由宿主在干净工作区重放 Coding 的 candidate patch 后执行白名单命令；通过退出码和日志判定，不采信 Coding 的 `testStatus`。
- 代码/测试/静态失败最多自动新建 **2** 次 `CODING_AGENT` attempt，**不预创建 QA attempt**；环境、鉴权、基础设施、需求歧义和 flaky 仍进人工。
- `qaMaxRemediationPasses=1` 与 `MAX_ROLE_ATTEMPTS=3` 保持不变。廉价门和 QA 浏览器返工分账，但都受角色 attempt 总帽约束。
- QA profile 增加 `buildCommands` / `staticCommands`；`regressionCommands` 仍只服务 QA Agent 的业务回归。
- docs-only 变更集跳过 BUILD、STATIC 和浏览器，语义与现有 QA spec 一致。
- 管理 API 增加验证 run / 步骤 / 证据读取，供任务详情展示。前端改动不在本 change 的后端实施范围内，契约写在计划的前端交接文档。

**非目标：** 不新增 Agent 角色；不把编译塞进 QA 容器当第一阶段；不放宽 QA 证据引用协议；不把环境失败打回 Coding；不默认打开 OpenViking；不改 Pi 一次恢复上限。

## Capabilities

### New Capabilities

- `requirement/host-verification-pipeline`: Coding 成功后的宿主 BUILD/STATIC 门、命令解析、证据、廉价返工与失败分类。

### Modified Capabilities

- （无。当前 `openspec/specs/` 只有 `knowledge/openviking-projection-admin`，本 change 不改它的需求。）

## Impact

- **engine：** `RequirementAgentStageOrchestrator`、`AgentWorkflowPlan`、`TaskFailurePhase` / retry resolver、新 `HostVerificationEngine` 与 Store 端口。
- **rag：** `QaValidationProfile` / Command / Service 增加构建与静态命令字段。
- **exec：** 命令探测与白名单执行（复用 `QaRepositoryProfileDetector`、`QaDocsOnlyChangeClassifier`、`CleanHostVerifierWorkspaceFactory` 的工作区重放）。
- **bootstrap：** PostgreSQL DDL（`p15`）、Store 适配、管理 API、delivery worker 调用宿主验证端口。
- **frontend（另一 agent）：** 任务详情验证时间线、证据列表、项目/任务 QA profile 表单新字段、Vite proxy 合同。
- **历史资料（不可当主 spec，仅作输入）：**
  - `docs/superpowers/specs/2026-07-28-qa-evidence-reference-and-production-mode-spec.md`（IMPLEMENTED_CLAIM）
  - `docs/superpowers/specs/2026-07-13-rd-task-stage-retry-and-ai-delivery-review-design.md`（IMPLEMENTED_CLAIM，禁止第五角色）
  - `docs/superpowers/specs/2026-07-13-qa-playwright-evidence-execution-spec.md`（EVIDENCE_OR_SUPERSEDED）
  - `docs/superpowers/specs/2026-07-25-gate-retry-qa-cache-remediation-design.md`（EVIDENCE_OR_SUPERSEDED，attempt 上限）
  - `docs/openspec/historical-spec-provenance-audit.md`
- **当前代码锚点：** `RequirementAgentStageOrchestrator`、`AgentWorkflowPlan`、`isCodingRemediationRequested`、`QaValidationProfile*`、`QaRepositoryProfileDetector`、`CleanHostVerifierWorkspaceFactory`、`TaskFailurePhase`、`QaEvidenceController`。
