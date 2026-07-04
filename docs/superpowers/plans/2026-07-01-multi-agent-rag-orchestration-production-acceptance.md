# RD-Bot 多 Agent RAG 编排生产验收文档

日期：2026-07-01

本文定义生产真实验收标准。所有验收点必须运行在真实生产或生产等价环境中，包含真实 PostgreSQL、真实 Docker 执行、真实模型 provider、真实 GitHub 仓库/PR、真实 Feishu 通知通道和真实 RD-Bot 服务实例。Mock、内存桩、本地假服务器或只看单元测试均不满足本文验收。

## 2026-07-05 最新证据基线

截至 2026-07-05 01:05，本机生产等价验收已经形成完整收口基线：

- Provider 前置探活已通过：`qa-runs/multi-agent-production-acceptance/provider-preflight-production-acceptance-20260704-160250.md/json`，`long-cat` 与 `minimax` 均完成真实 HTTP 调用并返回成功，报告只保留 provider 名、协议、adapter、HTTP 状态和 response fingerprint，不记录 secret 原值。
- 最小多 Agent 生产 smoke 已通过：`qa-runs/multi-agent-production-acceptance/multi-agent-production-acceptance-20260704-103658.md/json`，主任务 `7479113030836555776` 完成四角色链路，真实创建 GitHub PR `https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045/pull/15`，并记录 `timelineEvents=14`、`stageEvents=24`、`roleContextDistinctCount=4`、`qaReportEvidenceValidated=true`、`auditChainEvidenceValidated=true`。
- 经验复用已由 follow-up 真实需求证明：follow-up taskId 为 `7479118671961526272`，其角色上下文检索到 `retrievedExperienceEvidenceCount=20` 条历史经验。
- 专项 sidecar 已补齐并通过：需求评审阻断 `requirement-review-blocker-production-acceptance-20260704-122535.md/json`、Docker coding `docker-coding-production-acceptance-20260704-123318.md/json`、QA 失败阻断 `qa-failure-blocker-production-acceptance-20260704-163746.md/json`、交付复核失败 `delivery-review-failure-production-acceptance-20260704-124029.md/json`、工作流恢复 `workflow-recovery-production-acceptance-20260704-122834.md/json`、指标审计 `observability-metrics-production-acceptance-20260704-170114.md/json`。
- GitHub PR 远端反查已通过：`github-pr-remote-evidence-production-acceptance-20260704-165255.md/json` 证明 PR #15 的远端 body 包含交付复核、QA 证据、完整 `taskId` 和 `rd-artifact://` 产物链接，且 secret needle 扫描未发现泄漏。
- 最新台账 `qa-runs/multi-agent-production-acceptance/production-acceptance-evidence-ledger-20260704-170527.md/json` 显示 `PASSED=15`、`FAILED=0`、`NOT_RUN=0`，结论为 `PASSED_PRODUCTION_ACCEPTANCE_LEDGER`。
- `ProductionAcceptanceFinalGateSnapshotTest` 已接受最新台账；最终门禁只在 #1-#15 全部 `PASSED` 时通过，不能用最小 smoke 或本机单测替代生产验收。

后续验收文档和测试报告必须以这组证据为起点更新，不得继续引用 2026-07-04 18:52 的 `PASSED=8`、`NOT_RUN=7` 阶段作为当前阻断状态；早期 provider 401/429 与阶段缺口证据只保留为历史失败诊断。

## 0. 验收前置条件

验收环境必须具备：

- RD-Bot 后端生产配置启动，连接真实 PostgreSQL。
- Docker 可运行并能拉起配置的执行镜像。
- 至少两个真实模型 provider 配置，且凭据由环境变量注入。
- 一个 allowlist 内的真实 GitHub 测试仓库，允许创建测试 PR。
- 一个真实 Feishu 群或机器人 webhook，用于接收告警。
- 一个真实需求任务，包含需求文档、验收标准、目标仓库和分支。
- 所有测试过程产生的 taskId、stageRunId、PR URL、Feishu 消息链接、日志文件和数据库查询结果要归档到验收报告。

最小生产真实 smoke 入口：

先运行 provider 前置探活。该入口不创建任务、不执行 Docker 编码，只验证真实 provider env、协议、adapter 接管和真实 HTTP 响应。它失败时必须先修 provider，不应继续跑 30 分钟级多 Agent 总 smoke。

```bash
./mvnw -pl bootstrap -am -Dtest=ProviderPreflightRealSmokeTest \
  -Drd.integration.provider-preflight.enabled=true \
  -Drd.provider.preflight.smoke.production-evidence=true \
  -Drd.provider.preflight.smoke.rd-bot-version=<deployed-version> \
  -Drd.provider.preflight.smoke.environment-id=<prod-or-prod-like-env-id> \
  -Drd.provider.preflight.smoke.executed-by=<operator-or-ci-job> \
  -Drd.provider.preflight.smoke.expected-provider-count=2 \
  -Drd.provider.preflight.smoke.providers=long-cat,minimax \
  -Drd.provider.preflight.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`ProviderPreflightRealSmokeTest` 默认使用 `application.yaml` 中的 provider 语义：`long-cat` 为 `anthropic-compatible`，由 Docker Claude Code adapter 接管；`minimax` 默认是 `openai-chat-completions`，由 OpenAI Chat Completions adapter 接管。如果生产环境要让 MiniMax 进入 Docker Claude Code provider 链，必须通过环境变量显式设置 `MINIMAX_PROTOCOL=anthropic-compatible` 和 `MINIMAX_BASE_URL=https://api.minimaxi.com/anthropic`，并由 preflight 记录实际协议、adapter 和 base URL。本轮已通过的 provider preflight 使用的就是该覆盖形态。报告只记录 provider 名、协议、adapter、base URL、model、凭据 env 名、HTTP 状态和 response fingerprint，不记录 secret 原值。`SKIPPED`、`FAILED`、`PASSED` 都必须写出 Markdown 和 JSON sidecar；JSON 中 `providerPreflightEvidenceValidated=false` 或缺失 `successfulProviderCount>=expectedProviderCount` 时，不能继续执行下面的完整多 Agent smoke。只有该 preflight 对至少 `expected-provider-count` 个真实 provider 返回成功时，才允许继续执行下面的完整多 Agent smoke。

```bash
./mvnw -pl bootstrap -am -Dtest=MultiAgentRequirementDeliveryRealSmokeTest \
  -Drd.integration.multi-agent.enabled=true \
  -Drd.multi-agent.smoke.production-evidence=true \
  -Drd.multi-agent.smoke.rd-bot-version=<deployed-version> \
  -Drd.multi-agent.smoke.environment-id=<prod-or-prod-like-env-id> \
  -Drd.multi-agent.smoke.executed-by=<operator-or-ci-job> \
  -Drd.multi-agent.smoke.base-url=https://<real-rd-bot-host> \
  -Drd.multi-agent.smoke.postgres-url=jdbc:postgresql://<real-postgres-host>:5432/rd_bot \
  -Drd.multi-agent.smoke.postgres-user=<real-user> \
  -Drd.multi-agent.smoke.postgres-password="$RD_BOT_POSTGRES_PASSWORD" \
  -Drd.multi-agent.smoke.repository-url=https://github.com/<owner>/<repo>.git \
  -Drd.multi-agent.smoke.repo-owner=<owner> \
  -Drd.multi-agent.smoke.repo-name=<repo> \
  -Drd.multi-agent.smoke.task-id=<existing-main-task-id-for-final-aggregation> \
  -Drd.multi-agent.smoke.expected-provider-count=2 \
  -Drd.multi-agent.smoke.provider-secret-env-names=LONGCAT_API_KEY,MINIMAX_API_KEY \
  -Drd.multi-agent.smoke.github-code-platform-mode=real \
  -Drd.multi-agent.smoke.github-auth-mode=PAT_LOCAL_SMOKE \
  -Drd.multi-agent.smoke.github-credential-env-names=GITHUB_PAT \
  -Drd.multi-agent.smoke.request-timeout-seconds=900 \
  -Drd.multi-agent.smoke.completion-timeout-seconds=1800 \
  -Drd.multi-agent.smoke.poll-interval-seconds=5 \
  -Drd.multi-agent.smoke.provider-preflight-evidence-json=<provider-preflight-evidence-json> \
  -Drd.multi-agent.smoke.feishu-alert-evidence-json=<feishu-alert-evidence-json> \
  -Drd.multi-agent.smoke.recovery-evidence-json=<workflow-recovery-evidence-json> \
  -Drd.multi-agent.smoke.skill-policy-evidence-json=<skill-policy-evidence-json> \
  -Drd.multi-agent.smoke.requirement-review-evidence-json=<requirement-review-evidence-json> \
  -Drd.multi-agent.smoke.docker-coding-evidence-json=<docker-coding-evidence-json> \
  -Drd.multi-agent.smoke.qa-failure-evidence-json=<qa-failure-evidence-json> \
  -Drd.multi-agent.smoke.delivery-review-failure-evidence-json=<delivery-review-failure-evidence-json> \
  -Drd.multi-agent.smoke.github-pr-remote-evidence-json=<github-pr-remote-evidence-json> \
  -Drd.multi-agent.smoke.observability-metrics-evidence-json=<observability-metrics-evidence-json> \
  -Drd.multi-agent.smoke.report-dir=qa-runs/multi-agent-production-acceptance \
  -Drd.multi-agent.smoke.secret-scan-needles="$RD_BOT_POSTGRES_PASSWORD,$GITHUB_PAT" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该入口不是 mock：启用后会调用真实 RD-Bot HTTP 服务、查询真实 PostgreSQL，并校验四个阶段成功、`rd_agent_stage_events` 存在阶段事件、四个角色上下文落库且 `roleContextDistinctCount>=4`、每个阶段 `providerAttemptsJson` 至少记录一次真实 provider attempt、每个阶段都绑定 prompt/result artifact 且对应 `content_preview` 非空、`SOLUTION_ARCHITECT` 的 result artifact 可解析为带影响文件、实现步骤、验收映射和测试计划的 `solution-plan`，且 `CODING_AGENT` 的 prompt artifact 预览包含 `SOLUTION_ARCHITECT` 上游结果、`QA_AGENT` 的 result artifact 可解析为带真实命令和日志引用的 `qa-report`、经验条目入库且五类必需经验类型都绑定 `source_artifact_id`、`experienceHashCount` 与 `experienceRedactedCount` 至少覆盖五类必需经验类型、follow-up 真实需求的角色上下文能查询到 `rd-experience://` 历史经验证据、PR URL 存在、`executionResultJson.deliveryReview.approved=true`、`executionResultJson.pullRequestPublication.success=true`、PR 发布 metadata 能证明 `deliveryReviewApproved=true`、`targetBranch` 等于本次配置的 base branch、`workBranch=requirement/{taskId}`、`qaAcceptanceResultCount>0` 且 `prBodyEvidenceIncluded=true`、配置的敏感值不出现在被检查产物中。`rd.multi-agent.smoke.task-id` 是可选参数；为空时 smoke 会创建新的真实需求任务，非空时 smoke 不再创建新任务，而是通过真实 HTTP 和 PostgreSQL 校验该已有 taskId，这用于把 sidecar 和最终汇总绑定到同一验收对象。`rd-bot-version`、`environment-id`、`executed-by` 必须来自真实部署版本、生产等价环境标识和实际验收执行人或 CI job，并写入报告用于追溯。`base-url` 必须是带 host 的 `http://` 或 `https://` 地址，`postgres-url` 必须是 `jdbc:postgresql://...`，`repository-url` 必须是带 host 的 http(s) 仓库 URL，且路径匹配 `repo-owner/repo-name(.git)`。`provider-secret-env-names` 必须列出至少 `expected-provider-count` 个互不重复的真实 provider secret 环境变量名，smoke 会在发起 HTTP 前确认这些环境变量在当前执行环境中非空，但报告只记录 env 名称不记录 secret 值。`rd.multi-agent.smoke.provider-preflight-evidence-json` 必须指向同一版本、同一环境、同一执行人的 `ProviderPreflightRealSmokeTest` JSON sidecar；只有该 JSON 中 `providerPreflightEvidenceValidated=true`、`successfulProviderCount>=expected-provider-count`、provider 名称互不重复，且每个成功探活都记录有效协议、adapter、base URL、apiKeyEnv、model 与 2xx HTTP 状态时，完整多 Agent smoke 才会继续，否则会 fail fast 并写出 SKIPPED 报告。完整多 Agent smoke 的每个阶段不要求强制尝试所有 provider；健康的第一 provider 一次成功时，阶段 `providerAttemptCount=1` 是有效执行证据，多 provider 可用性由 provider preflight 证明，真正降级能力由 #5 的受控失败后成功 attempt 和 Feishu `PROVIDER_FALLBACK` sidecar 证明。`secret-scan-needles` 按 CSV 解析并裁剪空白后，必须至少包含一个非空的真实生产敏感值，报告只记录 `secretNeedleCount`，不记录 secret 原值；未配置任何有效 secret needle 时，#13 不能标记为 `PASSED`。GitHub PR 链路必须显式声明 `github-code-platform-mode=real`、`github-auth-mode=GITHUB_APP|PAT_LOCAL_SMOKE|GH_CLI_LOCAL_SMOKE`，并通过 `github-credential-env-names` 提供对应真实凭据环境变量；`GITHUB_APP` 模式至少需要 3 个互不重复的凭据 env，PAT 模式至少需要 1 个凭据 env，`GH_CLI_LOCAL_SMOKE` 可以使用本机已登录 `gh` 会话而不要求额外 token env，但报告必须记录 `githubAuthMode=GH_CLI_LOCAL_SMOKE` 且不记录 token 值。`rd.multi-agent.smoke.feishu-alert-evidence-json` 可指向同一版本、同一环境、同一执行人的 Feishu 告警 JSON sidecar；只有该 JSON 证明六类告警都真实返回 messageId，并且每条 delivery 都满足 `feishuAlertMetadataComplete=true` 所要求的 `taskId`、`role`、`stageRunId`、`failureCategory`、`nextAction`、`artifactUrl`，且六类必需 delivery 的 `taskId` 完全一致、汇总出的 `feishuAlertTaskId` 等于本次总验收主任务 `taskId` 时，多 Agent 总报告才允许把 #10 记为 `PASSED`。`rd.multi-agent.smoke.recovery-evidence-json` 可指向同一版本、同一环境、同一执行人的工作流恢复 JSON sidecar；只有该 JSON 证明 `workflowRecoveryEvidenceValidated=true`、重启前后阶段/事件计数只增不减、`duplicateSuccessfulStageCount=0`、`retryAttemptCount>0`、`retainedRetryArtifactCount>0`、`deliveryReviewApprovedBeforePrCreating=true` 且主任务时间线按顺序包含 `EXECUTING -> VALIDATING -> PR_CREATING -> COMMITTED -> REPORTING -> COMPLETED` 时，多 Agent 总报告才允许把 #9 记为 `PASSED`。`rd.multi-agent.smoke.skill-policy-evidence-json` 可指向同一版本、同一环境、同一执行人的 Skill policy JSON sidecar；只有该 JSON 证明 `skillPolicyEvidenceValidated=true`、`installed=true`、`unauthorizedRejected=true`、`highRiskWaitingApproval=true`、`metadataValidated=true` 且 `installerCallCount=1` 时，多 Agent 总报告才允许把 #11 记为 `PASSED`。`rd.multi-agent.smoke.requirement-review-evidence-json` 可指向同一版本、同一环境、同一执行人的需求评审阻断 JSON sidecar；只有该 JSON 证明 `requirementReviewBlockerEvidenceValidated=true`、`taskStatus=FAILED_NEEDS_HUMAN`、`executionResultStatus=NEEDS_HUMAN`、评审决策属于阻断类、`downstreamAgentsDispatched=false` 且 Feishu 告警真实送达时，多 Agent 总报告才允许把 #3 记为 `PASSED`。`rd.multi-agent.smoke.docker-coding-evidence-json` 可指向同一版本、同一环境、同一执行人的 Docker 编码 JSON sidecar；只有该 JSON 证明 `dockerCodingEvidenceValidated=true`、`realDockerRun=true`、`patchNonEmpty=true`、`resultJsonValidated=true`、`changedFileCount>0`、`validationExitCode=0`、`testsRun>0` 且 `testsFailed=0` 时，多 Agent 总报告才允许把 #6 记为 `PASSED`。`rd.multi-agent.smoke.qa-failure-evidence-json` 可指向同一版本、同一环境、同一执行人的 QA 失败阻断 JSON sidecar；只有该 JSON 证明 `qaFailureBlockerEvidenceValidated=true`、`taskStatus=FAILED_NEEDS_HUMAN`、`qaStageStatus=FAILED_VALIDATION`、`failedAcceptanceCount>0`、`prCreated=false`、`successReportCreated=false`、`blockedBeforePrCreating=true` 且 Feishu QA 失败告警真实送达时，多 Agent 总报告才允许把 #7 记为 `PASSED`。`rd.multi-agent.smoke.delivery-review-failure-evidence-json` 可指向同一版本、同一环境、同一执行人的交付复核失败 JSON sidecar；只有该 JSON 证明 `deliveryReviewFailureEvidenceValidated=true`、`taskStatus=REJECTED`、`deliveryReviewApproved=false`、`reviewDecision=REJECTED`、`pullRequestPublicationAttempted=false`、`prCreated=false`、`successReportCreated=false`、`failureReportCreated=true`、`successDeliveryReportExperienceCreated=false`、`blockedBeforePrCreating=true` 且 Feishu `DELIVERY_REVIEW_FAILED` 告警真实送达时，多 Agent 总报告才允许把 #8 记为 `PASSED`。`rd.multi-agent.smoke.github-pr-remote-evidence-json` 可指向同一版本、同一环境、同一执行人的 GitHub PR 远端反查 JSON sidecar；只有该 JSON 证明 `githubPrRemoteEvidenceValidated=true`、`remotePrTraceValidated=true`、远端 PR body 包含交付复核、QA 证据、`taskId` 和产物链接，且 `secretScanEvidenceValidated=true`、`secretLeakFound=false`、`secretScannedValueCount>0` 时，多 Agent 总报告才允许把 #13 记为 `PASSED`，并可辅助 #8/#14 的远端 PR 子证据。`rd.multi-agent.smoke.observability-metrics-evidence-json` 可指向同一版本、同一环境、同一执行人的指标和审计 JSON sidecar；只有该 JSON 证明 `observabilityMetricsEvidenceValidated=true`、`metricsHttpStatus=200`、`contextBuildLatencyMetricPresent=true`、`repairSuccessRateMetricPresent=true`、`validationPassRateMetricPresent=true`、`prCreationRateMetricPresent=true`、`humanInterventionRateMetricPresent=true`、`retryRateMetricPresent=true`、`meanTimeToRepairMetricPresent=true`、`topFailureCategoriesMetricPresent=true`、`stageMetricCount>=4`、`auditTraceQuerySucceeded=true`、`remotePrTraceValidated=true` 且 `auditTraceLinkCount>=8` 时，多 Agent 总报告才允许把 #14 记为 `PASSED`。`request-timeout-seconds` 控制单次真实 HTTP 请求超时，`completion-timeout-seconds` 控制等待任务到达 `COMPLETED` 的总时长，`poll-interval-seconds` 控制轮询间隔，默认分别为 900、1800、5 秒。日常 `./mvnw test` 不显式打开该 smoke 时可以由 JUnit 条件跳过；一旦传入 `-Drd.integration.multi-agent.enabled=true`，缺少任一真实生产参数必须让 Maven 失败，并写出缺失项报告，不能记为验收通过。

Workflow recovery sidecar 必须绑定当前主任务：`recoveryTaskId` 必须等于本次总验收报告的 `taskId`；同版本、同环境、同执行人但来自其他任务的恢复演练证据不能计入 #9。

Skill policy sidecar 必须绑定当前主任务：`skillPolicyTaskId` 必须等于本次总验收报告的 `taskId`；同版本、同环境、同执行人但来自其他任务的 Skill 安装策略证据不能计入 #11。

交付复核失败 sidecar 还必须记录 `stagePullRequestUrlRejected=true`，且 `rejectionReason` 必须能定位 `pullRequestUrl` 越权拒绝；缺少该字段或只证明普通复核失败时，不能计入 #8 的“复核后才发布 PR”验收。Delivery review artifact URI 必须是绝对 URI，且不能使用 `mock://`；Delivery review artifact URI 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`；只有 `reviewArtifactId` 而没有可追溯产物 URI 的证据不能计入 #8。

专项阻断 sidecar 的 Feishu 告警类型必须与演练类型一致：需求评审阻断必须记录 `feishuAlertType=STAGE_FAILED_NEEDS_HUMAN`，QA 失败阻断必须记录 `feishuAlertType=QA_FAILED`，交付复核失败必须记录 `feishuAlertType=DELIVERY_REVIEW_FAILED`。只提供非空 messageId 但告警类型不匹配时，不能计入对应验收点。

专项成功 sidecar 也必须绑定当前验收对象：Docker coding sidecar 必须记录 `repositoryUrl`，且该值等于本次 `rd.multi-agent.smoke.repository-url`；同环境、同执行人但来自其他仓库的 Docker 编码证据不能计入 #6。

每次显式启用 smoke 时都会写入一份 Markdown 证据报告和同名 JSON sidecar，默认目录为
`qa-runs/multi-agent-production-acceptance`，可通过
`rd.multi-agent.smoke.report-dir` 覆盖。通过或失败报告会记录 RD-Bot 版本、生产环境标识、执行人、provider secret env 数量、
GitHub code platform mode、GitHub auth mode、GitHub credential env 数量、
provider attempt count、阶段状态、prompt/result artifact id、artifact content preview 计数、方案结果协议校验状态、方案 implementationSteps 数量、方案 affectedFiles 数量、方案 acceptanceMapping 数量、方案 testPlan 数量、编码 prompt 是否引用方案、QA 结果协议校验状态、QA acceptanceResults 数量、QA PASSED 验收结果数量、QA 验证命令数量、QA 日志产物引用数量、PR URL、上下文包数量、上下文 distinct 数量、经验类型、经验来源产物绑定数量、经验 hash 数量、经验脱敏数量、follow-up taskId、检索到的历史经验证据数量、复核结果、Feishu 六类告警专项证据字段、workflow recovery 专项证据字段、Skill policy 专项证据字段、requirement review blocker 专项证据字段、Docker coding 专项证据字段、QA failure blocker 专项证据字段、delivery review failure 专项证据字段、GitHub PR 远端 body 反查专项证据字段、observability metrics 专项证据字段，以及由 timeline、stage events、role contexts、stage artifacts、experience links 和 PR trace 派生出的最小审计链证据；
报告不得记录任何 secret 原值。报告结论分为：

- `SKIPPED`：真实生产参数缺失，未运行任何真实链路；在显式启用 smoke 的命令中该报告必须伴随 Maven 失败，15 项验收均不能记为通过。
- `PASSED_MINIMUM_SMOKE`：最小真实生产 smoke 通过，只证明报告中列出的真实证据；未覆盖的专项演练仍需单独执行。`PASSED_MINIMUM_SMOKE` 不等于完整 15 项生产验收通过，#15 只有在 #1-#14 全部为 `PASSED` 时才允许标记为 `PASSED`。
- `PASSED_FULL_PRODUCTION_ACCEPTANCE`：#1-#15 全部为 `PASSED`，报告可作为完整生产真实测试结论归档。
- `FAILED_MINIMUM_SMOKE`：最小真实生产 smoke 失败，报告会记录已收集证据和脱敏后的失败信息。`FAILED_MINIMUM_SMOKE` 不能把未被完整证据证明的 #1-#14 自动标记为 `FAILED`，这些验收点必须保持 `NOT_RUN`；`FAILED_MINIMUM_SMOKE` 必须保留已经由独立真实证据证明的 `PASSED` 验收点，#15 标记为 `FAILED`。

独立 GitHub PR 真实 smoke 入口：

```bash
GITHUB_PAT="$(gh auth token)" ./mvnw -pl bootstrap -am \
  -Dtest=GitHubCodePlatformRealSmokeTest \
  -Drd.integration.github.enabled=true \
  -Drd.github.smoke.production-evidence=true \
  -Drd.github.smoke.rd-bot-version=<deployed-version> \
  -Drd.github.smoke.environment-id=<prod-or-prod-like-env-id> \
  -Drd.github.smoke.executed-by=<operator-or-ci-job> \
  -Drd.github.smoke.repo-owner=<owner> \
  -Drd.github.smoke.repo-name=<repo> \
  -Drd.github.smoke.base-branch=main \
  -Drd.github.smoke.work-branch=<existing-work-branch> \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该入口只验证 `GitHubCodePlatformAdapter` 的真实 PR 创建能力，不替代多 Agent 端到端验收。必须显式传入 `rd.github.smoke.production-evidence=true`、`rd-bot-version`、`environment-id` 和 `executed-by`，且 `work-branch` 必须是已存在的工作分支并不同于 `base-branch`；否则不能证明真实 PR 创建链路。通过时会在 `qa-runs/multi-agent-production-acceptance`（可用 `rd.github.smoke.report-dir` 覆盖）写出 `github-code-platform-production-acceptance-*.md` 和同名 JSON sidecar，记录 RD-Bot 版本、生产环境标识、执行人、owner/repo、base/work 分支、PR URL、PR number、`githubPrEvidenceValidated=true`、`pullRequestPublicationSucceeded=true`、`deliveryReviewGateExercised=false`，且不记录 GitHub token。GitHub PR 创建专项 sidecar 的 `pullRequestUrl` 必须使用 `http://` 或 `https://` scheme，GitHub PR 创建专项 sidecar 的 `pullRequestUrl` host 必须为 `github.com`，GitHub PR 创建专项 sidecar 的 `pullRequestUrl` path 必须等于 `/repo-owner/repo-name/pull/{pullRequestNumber}`；非 HTTP(S)、非 GitHub host、跨仓库、缺少真实 PR 编号或 path 不是精确 PR URL 形态的专项报告不能作为 #8 子证据。该专项报告只能作为 #8 的 PR 创建子证据；由于没有执行多 Agent 交付复核、状态机和 PR body 反查，报告矩阵必须保持 #8 为 `NOT_RUN`，不能单独计入完整验收通过。日常 `./mvnw test` 未显式打开时可以由 JUnit 条件跳过；一旦传入 `-Drd.integration.github.enabled=true`，缺少 `production-evidence=true`、`rd-bot-version`、`environment-id`、`executed-by`、`repo-owner`、`repo-name`、`work-branch` 或 `GITHUB_PAT` / `rd.github.code-platform.pat-token`，或 `work-branch` 等于 `base-branch`，都必须让 Maven 失败并写出 `SKIPPED` 报告，不能用 assumption skip 记为通过。

独立 GitHub PR 远端 body 反查真实 smoke 入口：

```bash
GITHUB_PAT="$(gh auth token)" ./mvnw -pl bootstrap -am \
  -Dtest=GitHubPullRequestRemoteEvidenceRealSmokeTest \
  -Drd.integration.github-pr-evidence.enabled=true \
  -Drd.github.pr-evidence.production-evidence=true \
  -Drd.github.pr-evidence.rd-bot-version=<deployed-version> \
  -Drd.github.pr-evidence.environment-id=<prod-or-prod-like-env-id> \
  -Drd.github.pr-evidence.executed-by=<operator-or-ci-job> \
  -Drd.github.pr-evidence.task-id=<task-id> \
  -Drd.github.pr-evidence.repo-owner=<owner> \
  -Drd.github.pr-evidence.repo-name=<repo> \
  -Drd.github.pr-evidence.base-branch=main \
  -Drd.github.pr-evidence.work-branch=requirement/<task-id> \
  -Drd.github.pr-evidence.pull-number=<pull-number> \
  -Drd.github.pr-evidence.secret-scan-needles=<real-secret-needles> \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该入口会对真实 GitHub REST `GET /repos/{owner}/{repo}/pulls/{pull-number}` 发起请求，反查远端 PR body，不读取本地构造的 metadata 替代远端证据。通过时写出 `github-pr-remote-evidence-production-acceptance-*.md` 和同名 JSON sidecar，JSON 必须记录 `githubPrRemoteEvidenceValidated=true`、`remotePrTraceValidated=true`、`pullRequestBodyIncludesDeliveryReview=true`、`pullRequestBodyIncludesQaEvidence=true`、`pullRequestBodyContainsTaskId=true`、`pullRequestBodyContainsExactTaskId=true`、`pullRequestBodyContainsArtifactLink=true`、`secretScanEvidenceValidated=true`、`secretLeakFound=false`、`secretScannedValueCount>0`、RD-Bot 版本、环境标识、执行人、taskId、owner/repo、base/work 分支、PR URL 和 PR number；报告不得记录 PR body 全文、GitHub token 或 secret needle 原值。PR body 中的 `taskId` 必须以完整 token 出现，不能只靠前缀或子串匹配；多 Agent 总验收读取该 sidecar 时必须要求 `pullRequestBodyContainsExactTaskId=true`。`pullRequestBodyContainsArtifactLink=true` 必须来自 PR body 中带 artifact、log、evidence 或 report 语义的证据行，且该行内 URI 必须是 `http://`、`https://`、`s3://` 或 `rd-artifact://` 的生产产物 URI；普通参考链接不能冒充产物链接。GitHub PR 远端反查 sidecar 的 `pullRequestUrl` 必须等于本次成功路径最终 `pullRequestUrl`；GitHub PR 远端反查 sidecar 的 `pullRequestUrl` host 必须为 `github.com`，PR body 反查 sidecar 的 `pullRequestUrl` path 必须等于 `/repo-owner/repo-name/pull/{pullRequestNumber}`。GitHub PR 远端反查 sidecar 的 `workBranch` 必须精确等于 `requirement/{taskId}`；只包含 taskId 的其他分支名不能计入 #8/#13/#14。同 taskId 但不同 PR URL、不同 host、不同 path、不同 PR number、不同工作分支，或 PR body 只包含 taskId 前缀/子串的远端反查 sidecar 不能计入 #8/#13/#14。该专项只能作为 #8 的远端 PR body 子证据、#13 的远端 PR body 脱敏子证据和 #14 的远端 PR 可反查 taskId 子证据；完整 #8/#13/#14 仍必须结合多 Agent 总验收、数据库审计链、Feishu 专项和其他产物扫描。显式开启后缺少 `production-evidence=true`、`rd-bot-version`、`environment-id`、`executed-by`、`task-id`、owner/repo、work branch、pull number、非空 secret needles 或 GitHub token 时必须 Maven 失败并写出 `SKIPPED` 报告。

独立 Skill 策略真实 smoke 入口：

```bash
./mvnw -pl bootstrap -am -Dtest=SkillPolicyRealSmokeTest \
  -Drd.integration.skill-policy.enabled=true \
  -Drd.skill.smoke.production-evidence=true \
  -Drd.skill.smoke.rd-bot-version=<deployed-version> \
  -Drd.skill.smoke.environment-id=<prod-or-prod-like-env-id> \
  -Drd.skill.smoke.executed-by=<operator-or-ci-job> \
  -Drd.skill.smoke.skill-id=<skill-id> \
  -Drd.skill.smoke.skill-version=<skill-version> \
  -Drd.skill.smoke.skill-source-uri=file:///opt/rd-bot/skills-src/<skill-id> \
  -Drd.skill.smoke.skill-checksum=sha256:<checksum> \
  -Drd.skill.smoke.install-root=/opt/rd-bot/skills-installed \
  -Drd.skill.smoke.allowed-role=QA_AGENT \
  -Drd.skill.smoke.rejected-role=REQUIREMENT_REVIEWER \
  -Drd.skill.smoke.high-risk-role=CODING_AGENT \
  -Drd.skill.smoke.task-id=<main-multi-agent-task-id> \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该入口只验证 #11 的 Skill 策略和真实文件系统安装链路，不替代多 Agent 端到端验收。显式开启后会读取真实 `file://` Skill 源、校验 `skill-checksum`、把低风险 Skill 真实复制到 `install-root/{skillId}/{version}`，再验证未授权角色被拒绝、高风险 Skill 进入等待审批且真实安装器只被调用一次。`skill-source-uri` 必须是 `file://` 绝对路径，`skill-checksum` 必须是 `sha256:` 加 64 位十六进制，`install-root` 必须是绝对路径；任一弱配置都应在 smoke 执行前被前置门禁拒绝。`allowed-role`、`rejected-role` 和 `high-risk-role` 必须由验收命令显式传入，不能依赖默认值证明角色边界。通过时会生成 `skill-production-acceptance-*.md` Markdown 报告和同名 `skill-production-acceptance-*.json` 结构化 sidecar；JSON 中记录 RD-Bot 版本、生产环境标识、执行人、`skillPolicyEvidenceValidated=true`、`installed=true`、`unauthorizedRejected=true`、`highRiskWaitingApproval=true`、`metadataValidated=true` 和 `installerCallCount=1`；其中 `installPath` 必须是绝对路径并以 `skillId/skillVersion` 结尾，不能用相对路径或其他 Skill 的安装路径冒充当前 Skill，`sourceChecksum` 必须是 `sha256:` 加 64 位十六进制。`rd.skill.smoke.task-id` 是可选参数；为空时 Skill policy smoke 会生成专项 taskId，非空时 JSON sidecar 的 `skillPolicyTaskId` 必须等于传入的主任务 ID。多 Agent 总验收应把该 JSON 路径传入 `rd.multi-agent.smoke.skill-policy-evidence-json`，且只有版本、环境、执行人一致、`skillPolicyTaskId` 等于多 Agent 总验收主任务 `taskId`、角色边界互不相同、安装路径为绝对路径且与 Skill 身份一致、checksum 为完整 sha256 时才计入总报告 #11。缺少任一真实参数必须让 Maven 失败，并写出 `skill-production-acceptance-*.md` 缺参报告；报告只记录 checksum、安装路径和策略结果，不记录 secret 原值。

## 1. 需求任务可进入多 Agent 工作流

验收标准：

- 在生产 RD-Bot 中创建真实需求任务后，主任务状态从 `CREATED` 推进到 `CONTEXT_BUILDING`。
- 数据库中生成同一 taskId 下的四个阶段运行计划：`REQUIREMENT_REVIEWER`、`SOLUTION_ARCHITECT`、`CODING_AGENT`、`QA_AGENT`。
- 每个阶段都有独立 `stageRunId`、`role`、`status`、`attempt_no` 和幂等键。
- 阶段状态推进时必须写入 `rd_agent_stage_events`，事件至少包含 `stage_run_id`、`task_id`、`role`、`status`、`entered_at` 和 `duration_ms`。

真实验证步骤：

- 通过真实 API 或生产入口创建需求任务。
- 查询生产 PostgreSQL 阶段运行表。
- 查询生产 PostgreSQL 阶段事件表。
- 查询任务时间线 API 或数据库事件表。

证据产物：

- 创建任务请求和响应。
- PostgreSQL 查询结果。
- 阶段事件表查询结果。
- 任务时间线截图或 JSON。

失败判定：

- 只创建了主任务但没有阶段运行计划。
- 阶段角色缺失或四个角色共用同一个执行记录。
- 阶段状态变化没有事件记录。
- 任务状态未进入 `CONTEXT_BUILDING`。

## 2. 角色上下文包真实落库且内容不同

验收标准：

- 四个角色都生成独立 `RoleContextPackage`。
- 每个上下文包包含证据来源、hash、证据 ID、上下文预算和 packageVersion。
- 最小生产 smoke 必须记录 `roleContextDistinctCount`，且 `REQUIREMENT_REVIEWER`、`SOLUTION_ARCHITECT`、`CODING_AGENT`、`QA_AGENT` 四个上下文 JSON 的 distinct count 必须为 4；只有 2-3 个 distinct 上下文不能计入 #2 通过。
- 上下文包内容已脱敏，不包含明文密钥、token、生产数据库密码。

真实验证步骤：

- 在生产 PostgreSQL 查询该 taskId 的角色上下文包。
- 对比四个角色的 evidence 列表和上下文预算。
- 用脱敏检查命令扫描上下文 JSON。

证据产物：

- 四个上下文包 JSON。
- hash/证据来源查询结果。
- 脱敏扫描结果。

失败判定：

- 任一角色缺少上下文包。
- 四个角色上下文不是全部唯一，或报告缺少 `roleContextDistinctCount`。
- 上下文包没有证据来源或 hash。
- 出现明文密钥。

## 3. 需求评审 Agent 能阻断不可交付需求

验收标准：

- 对真实缺信息需求，规则策略门可以先放行，必须由 `REQUIREMENT_REVIEWER` 输出真实 `requirement-review.json` 并作出阻断，而不是依赖本地 mock 或人工直接改库。
- `requirement-review.json` 必须明确列出缺失信息、风险、是否可做和人工动作；当 `status`、`decision` 或 `feasibility` 为 `NEED_INFO`、`NEEDS_HUMAN`、`UNSAFE`、`REJECTED`、`FAILED` 或 `BLOCKED` 时，控制面必须把评审阶段标记为 `FAILED_NEEDS_HUMAN`。
- 主任务必须进入 `FAILED_NEEDS_HUMAN`，最终 `executionResultJson.status` 必须为 `NEEDS_HUMAN`，且 `errorMessage` 必须包含评审 Agent 给出的人工补充原因。
- `SOLUTION_ARCHITECT`、`CODING_AGENT` 和 `QA_AGENT` 阶段必须保持 `PENDING`，不得进入 `DISPATCHING`、`RUNNING` 或后续状态。
- Feishu 收到真实等待人工或缺信息告警，告警 metadata 中必须包含 `role=REQUIREMENT_REVIEWER`、`status=FAILED_NEEDS_HUMAN` 和错误分类。

真实验证步骤：

- 创建一条真实需求任务，仓库、目标分支和至少一条验收标准齐全，确保规则策略门不会因基础信息缺失或高风险词直接阻断；需求正文故意缺少关键业务边界、验收命令或失败处理要求。
- 运行工作流。
- 运行 `RequirementReviewBlockerRealSmokeTest`，必须显式传入 `-Drd.integration.requirement-review-blocker.enabled=true`、`-Drd.requirement-review.smoke.production-evidence=true`、`-Drd.requirement-review.smoke.task-id=<same-main-task-id>`、真实 RD-Bot HTTP URL、真实 PostgreSQL 连接、真实 `reviewArtifactUri`、真实 Feishu 告警 `messageId` 和真实 secret scan needles。
- 查询 `REQUIREMENT_REVIEWER` 阶段输出、主任务状态、最终 `executionResultJson`、阶段运行表和阶段事件表。
- 检查 Feishu 群消息。
- 确认生产 PostgreSQL 中后续三个角色没有被派发，且没有对应 prompt/result artifact。

证据产物：

- `requirement-review.json`。
- `requirement-review-blocker-production-acceptance-*.json`，必须记录 `requirementReviewBlockerEvidenceValidated=true`、`role=REQUIREMENT_REVIEWER`、`taskStatus=FAILED_NEEDS_HUMAN`、`executionResultStatus=NEEDS_HUMAN`、阻断决策、`reviewArtifactId`、`reviewArtifactUri`、缺失信息、`downstreamAgentsDispatched=false`、后续三个 pending role、`feishuAlertType=STAGE_FAILED_NEEDS_HUMAN`、Feishu `messageId`、RD-Bot 版本、环境标识、执行人和 `taskId`。需求评审阻断 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`；其他任务的需求评审阻断证据不能计入 #3。Requirement review artifact URI 必须是绝对 URI，且不能使用 `mock://`；Requirement review artifact URI 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`；只有 `reviewArtifactId` 而没有可追溯产物 URI 的证据不能计入 #3。
- 多 Agent 总报告中 `requirementReviewBlockerEvidenceValidated=true`、`requirementReviewBlockerTaskId`、`requirementReviewBlockerStageRunId`、`requirementReviewBlockerDecision`、`requirementReviewBlockerReviewArtifactUri`、`requirementReviewBlockerMissingInformation`、`requirementReviewBlockerPendingDownstreamRoleCount` 和 `requirementReviewBlockerFeishuAlertDelivered=true`。
- 任务状态事件。
- 四个阶段运行记录，其中只有 `REQUIREMENT_REVIEWER` 进入终态，后续三阶段仍为 `PENDING`。
- 最终 `executionResultJson`，包含 `status=NEEDS_HUMAN` 和 `REQUIREMENT_REVIEWER` 阶段结果。
- Feishu 消息链接或截图。

失败判定：

- 缺信息需求仍进入 `SOLUTION_ARCHITECT`、`CODING_AGENT` 或 `QA_AGENT`。
- 评审 JSON 显示 `NEED_INFO` / `REJECTED` / `UNSAFE`，但主任务没有进入 `FAILED_NEEDS_HUMAN`。
- 最终结果没有写入 `status=NEEDS_HUMAN`。
- 告警没有发送到真实 Feishu。
- 输出无法解释缺少哪些信息。
- 需求评审阻断 sidecar 的 `taskId` 与本次总验收主任务 `taskId` 不一致。
- Requirement review artifact URI 缺失、不是绝对 URI、使用 `mock://`，或使用非 `http://`、`https://`、`s3://`、`rd-artifact://` 的 scheme。

## 4. 方案 Agent 产出可执行开发方案

验收标准：

- `SOLUTION_ARCHITECT` 输出 `solution-plan.json`。
- 方案必须包含改造文件、接口影响、数据结构影响、测试计划、风险和验收标准映射。
- `CODING_AGENT` 的输入必须引用 `SOLUTION_ARCHITECT` 上游方案结果。

真实验证步骤：

- 运行一个可交付真实需求。
- 查询 `SOLUTION_ARCHITECT` 阶段产物。
- 查询 `CODING_AGENT` 阶段 prompt 快照。
- 运行 `MultiAgentRequirementDeliveryRealSmokeTest`，确认报告中 `solutionPlanEvidenceValidated=true`、`solutionImplementationStepCount>0`、`solutionAffectedFileCount>0`、`solutionAcceptanceMappingCount>0`、`solutionTestPlanStepCount>0`、`codingPromptReferencesSolutionPlan=true`。

证据产物：

- `solution-plan.json`。
- `CODING_AGENT` prompt 快照。
- 数据库产物关联查询结果。

失败判定：

- 方案只有泛泛步骤，没有文件/接口/测试映射。
- 方案只有 implementationSteps 但没有 affectedFiles、acceptanceMapping 或 testPlan，不能计入 #4 通过。
- 编码阶段没有引用方案上游结果。
- 方案 schema 校验失败仍进入编码。

## 5. 多 provider 降级重试真实生效

验收标准：

- 生产配置中至少两个真实 provider 生效。
- 每个 provider 必须声明可被当前执行器支持的协议；`DockerClaudeCodeExecutor` 只接受 `anthropic-compatible`、`anthropic-claude-code`、`claude-code` 或 `anthropic` provider。`openai-chat-completions` provider 不能被计入 Docker Claude Code provider 降级链；它只能在已接入独立 OpenAI-compatible 执行适配器、对应角色确实由该 adapter 接管、并在报告中记录 adapter 名称时计入该角色的 provider 证据。
- 当第一 provider 在受控演练中不可用或被熔断时，阶段自动切换到第二 provider。
- 阶段运行记录包含 providerAttemptsJson、失败原因、最终 provider、熔断状态。
- 最小多 Agent smoke 必须对每个阶段计算 `providerAttemptCount`，并确认该值大于 0；该断言证明每个阶段真实调用了 provider，但不把“每阶段尝试全部 provider”误当成健康链路的要求。
- 完整 #5 降级验收必须单独证明受控失败后的跨 provider fallback：至少一个阶段的 `providerAttemptsJson` 同时包含非成功 attempt 和后续成功 attempt，且二者 provider 不同。
- 多 Agent 生产报告只有在真实阶段的 `providerAttemptsJson` 出现至少一次非成功 provider attempt，随后出现成功 attempt，且失败 attempt 的 `failedProvider` 与成功 attempt 的 `activeProvider` 都非空并且按大小写无关比较仍不相同时，才允许把 #5 记为降级证据；同 provider 重试不能计为多 provider 降级，只有大小写不同的 provider 名也不能计为多 provider 降级。
- 多 Agent 生产报告只有在有效降级证据存在，且 Feishu 六告警 sidecar 包含 `PROVIDER_FALLBACK` 时，才允许把 #5 标记为 `PASSED`。
- `PROVIDER_FALLBACK` 告警必须来自 `feishuAlertEvidenceValidated=true` 且 `feishuAlertMetadataComplete=true` 的 Feishu 告警 sidecar，不能只靠本地 alert type 字段。
- 通过报告必须记录 `providerFallbackEvidenceValidated=true`，并在 `providerFallbackSummary` 或 Stage Evidence 中展示 `failedProvider`、`failedStatus` 和 `activeProvider`。
- Feishu 收到 provider 降级告警。

真实验证步骤：

- 在生产等价环境中配置两个真实 provider。
- 先运行 `ProviderPreflightRealSmokeTest`，证明两个 provider 的凭据 env 已注入、协议被当前 adapter 接管、真实 HTTP 探活成功；若其中一个 provider 因协议不匹配、凭据缺失、quota 或 HTTP/schema 失败被拒绝，必须先修 provider 或替换 provider，不能继续执行完整 #5 验收。
- 用配置反查或启动日志证明两个 provider 均被当前执行器 adapter 接管；若其中一个 provider 因协议不匹配被排除，必须先补 adapter 或替换 provider，不能继续执行完整 #5 验收。
- 对第一 provider 执行受控不可用演练，例如临时禁用该 provider 的凭据环境变量并重启测试实例，或使用运维批准的 provider 熔断演练开关。
- 运行一个真实需求阶段。
- 查询阶段运行记录和 Docker metadata。
- 检查 Feishu 降级告警。

证据产物：

- provider 配置脱敏截图。
- provider protocol/adapter 反查结果。
- `provider-preflight-production-acceptance-*.md/json`，其中 `providerPreflightEvidenceValidated=true`、`successfulProviderCount>=expectedProviderCount`。
- providerAttemptsJson。
- 多 Agent 生产报告中的 `providerFallbackEvidenceValidated=true`、`failedProvider`、`failedStatus`、`activeProvider`。
- Feishu 降级消息。
- 最终阶段成功产物。

失败判定：

- 第一 provider 失败后任务直接失败，没有尝试第二 provider。
- providerAttemptsJson 为空。
- `failedProvider`、`failedStatus` 或 `activeProvider` 为空，或 `failedProvider` 与 `activeProvider` 按大小写无关比较相同。
- 降级未告警。
- `openai-chat-completions` provider 被直接映射成 `ANTHROPIC_BASE_URL` 后进入 Docker Claude Code 链路。
- 使用 mock provider 或假 endpoint 代替真实 provider 演练。

## 6. 编码 Agent 在 Docker 中真实改代码并运行测试

验收标准：

- `CODING_AGENT` 在 Docker 沙箱中拉起真实仓库工作区。
- 产物包含 `result.json`、`patch.diff`、真实测试日志和 Docker metadata。
- `result.json` 通过结构化校验。
- 测试命令在容器或执行工作区真实运行，不是 Agent 口头声明。

真实验证步骤：

- 运行可交付真实需求。
- 查询编码阶段产物。
- 对该真实任务运行 Docker coding 专项 smoke，并把 JSON sidecar 传给总验收：

```bash
./mvnw -pl bootstrap -am -Dtest=DockerCodingRealSmokeTest \
  -Drd.integration.docker-coding.enabled=true \
  -Drd.docker-coding.smoke.production-evidence=true \
  -Drd.docker-coding.smoke.rd-bot-version=<real-rd-bot-version> \
  -Drd.docker-coding.smoke.environment-id=<real-production-like-env-id> \
  -Drd.docker-coding.smoke.executed-by=<real-operator-or-ci-job> \
  -Drd.docker-coding.smoke.base-url=<real-rd-bot-http-url> \
  -Drd.docker-coding.smoke.postgres-url=<real-jdbc-postgresql-url> \
  -Drd.docker-coding.smoke.postgres-user=<real-postgres-user> \
  -Drd.docker-coding.smoke.postgres-password=<real-postgres-password> \
  -Drd.docker-coding.smoke.repository-url=<real-repository-url> \
  -Drd.docker-coding.smoke.task-id=<same-main-task-id> \
  -Drd.docker-coding.smoke.patch-artifact-uri=<real-patch-artifact-uri> \
  -Drd.docker-coding.smoke.result-artifact-uri=<real-result-json-artifact-uri> \
  -Drd.docker-coding.smoke.test-log-artifact-uri=<real-test-log-artifact-uri> \
  -Drd.docker-coding.smoke.docker-metadata-artifact-uri=<real-docker-metadata-artifact-uri> \
  -Drd.docker-coding.smoke.secret-scan-needles=<real-secret-values-for-scan> \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- 下载或打开 `patch.diff` 和测试日志。
- 在验收仓库核对改动确实存在于 PR 分支。

证据产物：

- Docker run metadata。
- `docker-coding-production-acceptance-*.json`，必须记录 `dockerCodingEvidenceValidated=true`、`repositoryUrl`、`realDockerRun=true`、`patchNonEmpty=true`、`resultJsonValidated=true`、`changedFileCount>0`、`validationCommand`、`validationExitCode=0`、`testsRun>0`、`testsFailed=0`、Docker image、containerId、完整 40 位 SHA-1 或 64 位 SHA-256 Git commit hash、`taskId`、四类 artifact id 和四类 artifact URI；其中 `repositoryUrl` 必须等于本次 `rd.multi-agent.smoke.repository-url`，Docker 编码 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`，四类 artifact id 必须互不重复，四类 artifact URI 必须是绝对 URI，且不能使用 `mock://`，四类 artifact URI 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`，不能用同一个产物 id 或同一个产物 URI 同时冒充 patch、result、测试日志和 Docker metadata。
- 多 Agent 总报告中 `dockerCodingEvidenceValidated=true`、`dockerCodingTaskId`、`dockerCodingStageRunId`、`dockerCodingRepositoryUrl`、`dockerCodingImage`、`dockerCodingPatchArtifactId`、`dockerCodingValidationCommand`、`dockerCodingTestsRun` 和 `dockerCodingRealDockerRun=true`。
- `patch.diff`。
- `test.log`。
- PR 分支 commit hash。

失败判定：

- 没有真实 diff。
- 测试日志只是自然语言总结。
- `docker-coding-production-acceptance-*.json` 的 `repositoryUrl` 缺失或与本次验收仓库不一致。
- Docker 编码 sidecar 的 `taskId` 与本次总验收主任务 `taskId` 不一致，或其他任务的 Docker 编码证据不能计入 #6。
- 四类 Docker 编码 artifact id 复用同一个产物。
- 四类 Docker 编码 artifact URI 缺失、不是绝对 URI、使用 `mock://` 或复用同一个产物 URI。
- Docker metadata 缺失。
- 产物 schema 校验失败仍进入 QA。

## 7. QA Agent 逐条验收并阻断失败交付

验收标准：

- `QA_AGENT` 输出 `qa-report.json`。
- 每一条验收标准都必须有真实验证命令、执行结果、日志引用和 pass/fail 判定。
- 任一验收标准失败时，主任务不得进入 `PR_CREATING`、`COMMITTED`、`REPORTING` 或 `COMPLETED`，不得写入成功交付报告，不得调用复核后 PR 发布端口创建物理 PR。
- QA 失败触发真实 Feishu 告警。

真实验证步骤：

- 准备一个会导致部分验收失败的真实测试需求或测试分支。
- 运行到 QA 阶段。
- 运行 `QaFailureBlockerRealSmokeTest`，命令必须包含 `-Dtest=QaFailureBlockerRealSmokeTest`，并显式传入 `-Drd.integration.qa-failure.enabled=true`、`-Drd.qa-failure.smoke.production-evidence=true`、`-Drd.qa-failure.smoke.task-id=<same-main-task-id>`、真实 RD-Bot HTTP URL、真实 PostgreSQL 连接、真实 `qaReportArtifactUri`、真实 `validationLogArtifactUris`、真实 Feishu 告警 `messageId` 和真实 secret scan needles。
- 查询 QA 报告和主任务状态。
- 检查 Feishu 告警。

证据产物：

- `qa-report.json`。
- `qa-failure-blocker-production-acceptance-*.json`，必须记录 `qaFailureBlockerEvidenceValidated=true`、`role=QA_AGENT`、`taskStatus=FAILED_NEEDS_HUMAN`、`qaStageStatus=FAILED_VALIDATION`、阻断型 `executionResultStatus`、`qaReportArtifactId`、`qaReportArtifactUri`、`validationLogArtifactUris`、`failedAcceptanceCount>0`、`acceptanceResultCount`、`validationCommandCount`、`validationLogArtifactCount`、`prCreated=false`、`successReportCreated=false`、`blockedBeforePrCreating=true`、`feishuAlertType=QA_FAILED`、Feishu `messageId`、RD-Bot 版本、环境标识、执行人和 `taskId`。QA 失败阻断 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`；QA report artifact URI 和 validation log artifact URI 必须是绝对 URI，且不能使用 `mock://`；QA report artifact URI 和 validation log artifact URI 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`；`validationLogArtifactUris` 数量必须覆盖 `acceptanceResultCount` 且不能复用同一个日志 URI；其他任务的 QA 失败阻断证据不能计入 #7。
- 多 Agent 总报告中成功路径必须记录 `qaReportEvidenceValidated=true`、`qaAcceptanceResultCount>0`、`qaPassedAcceptanceResultCount=qaAcceptanceResultCount`、`qaValidationCommandCount>=qaAcceptanceResultCount`、`qaValidationLogArtifactCount>=qaAcceptanceResultCount`；失败路径必须记录 `qaFailureBlockerEvidenceValidated=true`、`qaFailureBlockerTaskId`、`qaFailureBlockerStageRunId`、`qaFailureBlockerQaStageStatus`、`qaFailureBlockerFailedAcceptanceCount`、`qaFailureBlockerPrCreated=false`、`qaFailureBlockerSuccessReportCreated=false`、`qaFailureBlockerBlockedBeforePrCreating=true` 和 `qaFailureBlockerFeishuAlertDelivered=true`。
- QA 测试日志。
- 主任务状态事件。
- Feishu QA 失败消息。

失败判定：

- QA 报告没有逐条对应验收标准。
- QA 只有 acceptanceResults 数量但缺少真实命令、PASSED 状态或日志产物引用，不能计入 #7 通过。
- QA 失败后仍进入 `PR_CREATING`、`COMMITTED`、`REPORTING` 或 `COMPLETED`，或写入成功交付报告。
- QA 失败后仍创建物理 PR。
- QA 只复述编码 Agent 的测试结果，没有独立执行。
- QA 失败阻断 sidecar 的 `taskId` 与本次总验收主任务 `taskId` 不一致。
- QA report artifact URI 或 validation log artifact URI 缺失、不是绝对 URI、使用 `mock://`、使用非 `http://`、`https://`、`s3://`、`rd-artifact://` 的 scheme，或 validation log URI 数量不足以覆盖每条验收结果。

## 8. 交付复核通过后才提交为已交付

验收标准：

- `RequirementDeliveryReviewer` 在真实生产链路中复核 `multiAgentStatus=SUCCESS`、四个角色阶段都成功、`CODING_AGENT` 提供 `prBody`、`changedFiles` 或 `testSummary` 等可发布交付候选证据、`QA_AGENT` 输出 `status=PASSED` 且 `acceptanceResults` 每项都有验收标准、真实命令、`PASSED` 状态和日志产物引用。
- `REQUIREMENT_REVIEWER`、`SOLUTION_ARCHITECT`、`CODING_AGENT`、`QA_AGENT` 的 `RequirementExecutionRequest.pullRequestRequired` 必须全部为 `false`，物理 PR 不得由任一 Agent 阶段直接创建。
- 任一 Agent 阶段返回非空 `pullRequestUrl` 必须视为策略违例，当前阶段进入 `FAILED_NEEDS_HUMAN` 并记录 `AGENT_PR_POLICY_VIOLATION`，后续 Agent、交付复核和 PR 发布均不得继续。PR 发布只能由 `RequirementPullRequestPublisherPort` 在复核通过后执行。
- `RequirementDeliveryReviewer` 必须拒绝任何 `multiAgentStages` 中包含阶段级 `pullRequestUrl` 的聚合结果，防止历史数据、旁路调用或后续重构绕过引擎阶段的越权 PR 拦截。
- 复核通过结果必须写入任务最终 `executionResultJson.deliveryReview`，其中 `approved=true` 且 `reviewer=DELIVERY_REVIEWER`。
- 复核通过后必须通过 `RequirementPullRequestPublisherPort` 调用 code platform port 创建真实 PR，最终 `executionResultJson.pullRequestPublication.success=true`，且 `pullRequestPublication.pullRequestUrl` 与任务最终 `pullRequestUrl` 一致。
- 复核通过后，主任务才允许进入 `PR_CREATING`，真实 PR 创建成功后进入 `COMMITTED`，生成交付报告并沉淀经验后进入 `REPORTING -> COMPLETED`。
- 复核失败时，主任务进入 `REJECTED`，发送真实 `DELIVERY_REVIEW_FAILED` Feishu 告警，并只写入 `failure=true` 的失败交付报告。
- PR body 至少必须包含 `RD-Bot Delivery Review` 和 `RD-Bot QA Evidence` 段；PR metadata 至少包含 `taskId`、`taskType`、`targetBranch`、`workBranch`、`deliveryReviewApproved`、`qaAcceptanceResultCount`、`prBodyIncludesDeliveryReview`、`prBodyIncludesQaEvidence` 和 `prBodyEvidenceIncluded`。#8 完整验收必须接入远端 PR body 反查专项证据，证明远端 PR body 实际包含交付复核、QA 证据、`taskId` 和产物链接。
- `pullRequestEvidenceValidated=true` 必须同时证明 PR URL 必须使用 `http://` 或 `https://` scheme，host 等于本次 `rd.multi-agent.smoke.repository-url` 的 host，且 path 必须等于 `/repo-owner/repo-name/pull/{pullRequestNumber}`；非 HTTP(S)、跨 host、跨仓库、缺少真实 PR 编号或 path 不是精确 PR URL 形态的证据不能计入 #8 或完整生产验收通过。

真实验证步骤：

- 运行一条可完整交付的真实需求，查询主任务状态、阶段运行表、经验条目表和真实 GitHub PR。
- 运行一条真实受控复核失败演练，例如让编码阶段缺少 `prBody`/`changedFiles`/`testSummary`，或让 QA 阶段产物缺失；演练必须使用真实 RD-Bot 服务、真实数据库和真实通知通道，不能替换为 mock。
- 对该复核失败演练运行交付复核失败专项 smoke，并把 JSON sidecar 传给总验收：

```bash
./mvnw -pl bootstrap -am -Dtest=DeliveryReviewFailureRealSmokeTest \
  -Drd.integration.delivery-review-failure.enabled=true \
  -Drd.delivery-review-failure.smoke.production-evidence=true \
  -Drd.delivery-review-failure.smoke.rd-bot-version=<real-rd-bot-version> \
  -Drd.delivery-review-failure.smoke.environment-id=<real-production-like-env-id> \
  -Drd.delivery-review-failure.smoke.executed-by=<real-operator-or-ci-job> \
  -Drd.delivery-review-failure.smoke.base-url=<real-rd-bot-http-url> \
  -Drd.delivery-review-failure.smoke.postgres-url=<real-jdbc-postgresql-url> \
  -Drd.delivery-review-failure.smoke.postgres-user=<real-postgres-user> \
  -Drd.delivery-review-failure.smoke.postgres-password=<real-postgres-password> \
  -Drd.delivery-review-failure.smoke.task-id=<same-main-task-id> \
  -Drd.delivery-review-failure.smoke.review-artifact-uri=<real-delivery-review-artifact-uri> \
  -Drd.delivery-review-failure.smoke.feishu-alert-message-id=<real-feishu-message-id> \
  -Drd.delivery-review-failure.smoke.secret-scan-needles=<real-secret-values-for-scan> \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- 检查 Feishu 群中的交付复核失败告警。
- 对照 `executionResultJson.pullRequestPublication.metadataJson` 检查 PR 发布请求已携带 delivery review、目标分支、工作分支和 QA 证据，并通过 GitHub PR 远端反查专项验证 PR body。

证据产物：

- Delivery review JSON 或 `RequirementDeliveryReviewResult` JSON。
- `delivery-review-failure-production-acceptance-*.json`，必须记录 `deliveryReviewFailureEvidenceValidated=true`、`taskStatus=REJECTED`、`deliveryReviewApproved=false`、`reviewDecision=REJECTED`、`reviewer=DELIVERY_REVIEWER`、`reviewArtifactId`、`reviewArtifactUri`、`rejectionReason`、`pullRequestPublicationAttempted=false`、`prCreated=false`、`successReportCreated=false`、`failureReportCreated=true`、`successDeliveryReportExperienceCreated=false`、`blockedBeforePrCreating=true`、`stagePullRequestUrlRejected=true`、`feishuAlertType=DELIVERY_REVIEW_FAILED`、Feishu `messageId`、RD-Bot 版本、环境标识、执行人和 `taskId`。交付复核失败 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`；其他任务的交付复核失败证据不能计入 #8。Delivery review artifact URI 必须是绝对 URI，且不能使用 `mock://`；Delivery review artifact URI 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`；`rejectionReason` 必须能定位 `pullRequestUrl` 越权拒绝，不能用普通证据缺失拒绝冒充该验收。
- 多 Agent 总报告中 `deliveryReviewFailureEvidenceValidated=true`、`deliveryReviewFailureTaskId`、`deliveryReviewFailureTaskStatus`、`deliveryReviewFailureApproved=false`、`deliveryReviewFailureDecision`、`deliveryReviewFailureReviewArtifactUri`、`deliveryReviewFailurePullRequestPublicationAttempted=false`、`deliveryReviewFailurePrCreated=false`、`deliveryReviewFailureSuccessReportCreated=false`、`deliveryReviewFailureFailureReportCreated=true`、`deliveryReviewFailureStagePullRequestUrlRejected=true`、`deliveryReviewFailureFeishuAlertDelivered=true`，以及 `githubPrRemoteEvidenceValidated=true`、`githubPrRemoteBodyIncludesDeliveryReview=true`、`githubPrRemoteBodyIncludesQaEvidence=true`、`githubPrRemoteBodyContainsTaskId=true` 和 `githubPrRemoteBodyContainsArtifactLink=true`。
- PR URL。
- PR body。
- `github-pr-remote-evidence-production-acceptance-*.json`，必须记录 `githubPrRemoteEvidenceValidated=true`、`remotePrTraceValidated=true`、`taskId`、`pullRequestUrl`、`pullRequestNumber`、`baseBranch`、`workBranch`、`pullRequestBodyIncludesDeliveryReview=true`、`pullRequestBodyIncludesQaEvidence=true`、`pullRequestBodyContainsTaskId=true`、`pullRequestBodyContainsExactTaskId=true`、`pullRequestBodyContainsArtifactLink=true`、`secretScanEvidenceValidated=true`、`secretLeakFound=false`、`secretScannedValueCount>0`、RD-Bot 版本、环境标识和执行人；PR body 反查 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`，且该 `taskId` 必须以完整 token 出现在远端 PR body 中，不能只靠前缀或子串匹配；`pullRequestBodyContainsArtifactLink=true` 必须来自带 artifact、log、evidence 或 report 语义的证据行，普通 `reference: https://...` 链接不能计入；`pullRequestUrl` 必须使用 `http://` 或 `https://` scheme，GitHub PR 远端反查 sidecar 的 `pullRequestUrl` host 必须为 `github.com`，PR body 反查 sidecar 的 `pullRequestUrl` path 必须等于 `/repo-owner/repo-name/pull/{pullRequestNumber}`，且必须等于本次成功路径最终 `pullRequestUrl`。
- GitHub PR metadata。
- `pullRequestPublication.metadataJson` 中的 `targetBranch`、`workBranch`、`qaAcceptanceResultCount` 和 `prBodyEvidenceIncluded`，以及 PR URL 中的 owner/repo 与本次 `rd.multi-agent.smoke.repo-owner`、`rd.multi-agent.smoke.repo-name` 的匹配结果。
- 成功和失败两条任务的 PostgreSQL 查询结果。
- Feishu `DELIVERY_REVIEW_FAILED` 消息链接或截图。

失败判定：

- 复核失败后任务仍进入 `PR_CREATING`、`COMMITTED`、`REPORTING` 或 `COMPLETED`。
- Delivery review artifact URI 缺失、不是绝对 URI、使用 `mock://`，或使用非 `http://`、`https://`、`s3://`、`rd-artifact://` 的 scheme。
- 缺少同版本、同环境、同执行人的 GitHub PR 远端反查 sidecar，或远端 PR body 未包含交付复核、QA 证据、完整 token 形态的 `taskId` 或产物链接。
- 同 taskId 但不同 PR URL 或 PR number 的远端反查 sidecar 不能计入 #8/#13/#14。
- 任一 Agent 阶段返回非空 `pullRequestUrl` 后任务仍继续进入后续 Agent、交付复核、`PR_CREATING` 或 `COMPLETED`。
- 复核失败后写入成功 `DELIVERY_REPORT` 经验。
- 复核失败没有真实 Feishu 告警。
- PR 发布 metadata 缺少复核、目标分支、工作分支、QA 条数或 PR body 证据标记。
- 远端 PR body 缺少测试证据或带产物/日志/证据语义的生产产物链接。
- PR 创建在非 allowlist 仓库。
- 未经过复核就创建物理 PR。
- 最终 `pullRequestPublication.success` 缺失或不为 `true`。
- 交付复核失败 sidecar 的 `taskId` 与本次总验收主任务 `taskId` 不一致。

## 9. 状态机可恢复且不会重复派发

验收标准：

- 在任一阶段运行完成后重启 RD-Bot，工作流能从最后持久化阶段继续。
- 同一 taskId、role、idempotencyKey 不会重复创建成功阶段。
- 重试会产生新的 attempt_no，并保留旧产物。
- 重启前后 `rd_agent_stage_events` 仍保留已发生的状态事件，恢复过程不能覆盖历史事件。
- 恢复演练 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`；其他任务的恢复证据不能计入 #9。
- 完整成功链路的主任务时间线必须按顺序包含 `EXECUTING -> VALIDATING -> PR_CREATING -> COMMITTED -> REPORTING -> COMPLETED`，关键状态的首次出现顺序也必须满足该顺序，不能先出现 `PR_CREATING` 后再补一段正常时间线来伪造恢复顺序，且 `PR_CREATING` 只能出现在 `deliveryReview.approved=true` 之后。

真实验证步骤：

- 运行真实任务到 `SOLUTION_ARCHITECT` 完成后停止服务。
- 重启生产等价实例。
- 继续任务。
- 查询阶段运行表和事件表。
- 核对重启前后的 `rd_agent_stage_events` 总数只增不减。
- 运行 workflow recovery 真实 smoke 生成可被总报告聚合的 sidecar：

```bash
./mvnw -pl bootstrap -am -Dtest=WorkflowRecoveryRealSmokeTest \
  -Drd.integration.workflow-recovery.enabled=true \
  -Drd.workflow.recovery.smoke.production-evidence=true \
  -Drd.workflow.recovery.smoke.rd-bot-version=<deployed-version> \
  -Drd.workflow.recovery.smoke.environment-id=<prod-or-prod-like-env-id> \
  -Drd.workflow.recovery.smoke.executed-by=<operator-or-ci-job> \
  -Drd.workflow.recovery.smoke.base-url=https://<real-rd-bot-host> \
  -Drd.workflow.recovery.smoke.postgres-url=jdbc:postgresql://<real-postgres-host>:5432/rd_bot \
  -Drd.workflow.recovery.smoke.postgres-user=<real-user> \
  -Drd.workflow.recovery.smoke.postgres-password="$RD_BOT_POSTGRES_PASSWORD" \
  -Drd.workflow.recovery.smoke.task-id=<same-main-task-id> \
  -Drd.workflow.recovery.smoke.stage-run-count-before-restart=<stage-run-count-before-restart> \
  -Drd.workflow.recovery.smoke.stage-event-count-before-restart=<stage-event-count-before-restart> \
  -Drd.workflow.recovery.smoke.retry-attempt-count=<retry-attempt-count> \
  -Drd.workflow.recovery.smoke.retained-retry-artifact-count=<retained-retry-artifact-count> \
  -Drd.workflow.recovery.smoke.startup-log-evidence-uri=<startup-log-artifact-uri> \
  -Drd.workflow.recovery.smoke.database-snapshot-evidence-uri=<database-snapshot-artifact-uri> \
  -Drd.workflow.recovery.smoke.secret-scan-needles="$RD_BOT_POSTGRES_PASSWORD,$GITHUB_PAT" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

证据产物：

- 重启前后数据库快照。
- 服务启动日志。
- 阶段运行记录。
- 阶段事件记录。
- `workflow-recovery-production-acceptance-*.json` 结构化 sidecar，字段至少包含 `workflowRecoveryEvidenceValidated=true`、`rdBotVersion`、`environmentId`、`executedBy`、`taskId`、`stageRunCountBeforeRestart`、`stageRunCountAfterRestart`、`stageEventCountBeforeRestart`、`stageEventCountAfterRestart`、`duplicateSuccessfulStageCount=0`、`retryAttemptCount>0`、`retainedRetryArtifactCount>0`、`deliveryReviewApprovedBeforePrCreating=true`、`timelineStatuses`、`startupLogEvidenceUri` 和 `databaseSnapshotEvidenceUri`；其中 `taskId` 必须等于本次总验收主任务 `taskId`，`startupLogEvidenceUri` 和 `databaseSnapshotEvidenceUri` 必须是绝对 URI，且不能使用 `mock://`，`startupLogEvidenceUri` 和 `databaseSnapshotEvidenceUri` 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`。
- 多 Agent 总报告中的 `workflowRecoveryEvidenceValidated=true`、`recoveryTaskId`、`recoveryStageRunCountBeforeRestart`、`recoveryStageRunCountAfterRestart`、`recoveryStageEventCountBeforeRestart`、`recoveryStageEventCountAfterRestart`、`duplicateSuccessfulStageCount`、`retryAttemptCount` 和 `retainedRetryArtifactCount`。

失败判定：

- 重启后从头重复执行已成功阶段。
- 旧阶段产物被覆盖。
- 阶段事件被覆盖或丢失。
- 幂等键未生效。
- 恢复演练 JSON 与本次总验收的 `rd-bot-version`、`environment-id`、`executed-by` 不一致。
- 恢复演练 sidecar 的 `taskId` 与本次总验收主任务 `taskId` 不一致。
- sidecar 缺少服务启动日志、数据库快照 URI、重试 attempt 证据或旧产物保留证据。

## 10. 错误通知真实送达 Feishu

验收标准：

- 阶段失败、等待人工、provider 降级、QA 失败、交付复核失败、复核后 PR 发布失败至少覆盖六类告警。
- 每条告警包含 `taskId`、`role`、`stageRunId`、`failureCategory`、`nextAction` 和 `artifactUrl`。
- Feishu 告警 JSON sidecar 的每条 delivery 必须保留字段清单：role、stageRunId、failureCategory、nextAction、artifactUrl；`artifactUrl` 必须是绝对 URI，且不能使用 `mock://`，相对路径或 mock artifactUrl 不能计入 #10。
- 六类必需 delivery 的 `taskId` 必须完全一致；混合多个 taskId 的 Feishu 告警 sidecar 不能计入 #10。
- Feishu 告警 sidecar 的 `feishuAlertTaskId` 必须等于本次总验收主任务 `taskId`；其他任务的 Feishu 告警 sidecar 不能计入 #10。
- 六类必需 delivery 的 `messageId` 必须互不重复；重复 messageId 不能计入 #10。
- Feishu 发送失败时，系统记录告警发送失败事件。
- 多 Agent 生产报告只有在 `feishuAlertEvidenceValidated=true`、`feishuAlertMetadataComplete=true`、`feishuAlertMessageCount>=6`、`feishuAlertTaskId` 等于本次总验收主任务 `taskId`，且 `feishuAlertTypes` 同时包含 `STAGE_FAILED_RETRYABLE`、`STAGE_FAILED_NEEDS_HUMAN`、`PROVIDER_FALLBACK`、`QA_FAILED`、`DELIVERY_REVIEW_FAILED`、`PR_PUBLICATION_FAILED` 时，才允许把 #10 标记为 `PASSED`。
- `PROVIDER_FALLBACK` 告警必须由真实阶段的 provider attempts 派生，metadata 至少包含 `failedProvider`、`failedStatus`、`activeProvider` 和 `role`。

真实验证步骤：

- 通过真实演练触发六类告警。
- 检查 Feishu 群消息。
- 查询告警事件表。

最小 Feishu 告警真实 smoke 入口：

```bash
./mvnw -pl bootstrap -am -Dtest=FeishuAlertRealSmokeTest \
  -Drd.integration.feishu-alert.enabled=true \
  -Drd.feishu.alert.smoke.production-evidence=true \
  -Drd.feishu.alert.smoke.rd-bot-version=<deployed-version> \
  -Drd.feishu.alert.smoke.environment-id=<prod-or-prod-like-env-id> \
  -Drd.feishu.alert.smoke.executed-by=<operator-or-ci-job> \
  -Drd.feishu.alert.smoke.app-id="$FEISHU_APP_ID" \
  -Drd.feishu.alert.smoke.app-secret="$FEISHU_APP_SECRET" \
  -Drd.feishu.alert.smoke.chat-id="$FEISHU_ALERT_CHAT_ID" \
  -Drd.feishu.alert.smoke.task-id=<main-multi-agent-task-id> \
  -Drd.feishu.alert.smoke.secret-scan-needles="$FEISHU_APP_SECRET" \
  -Drd.feishu.alert.smoke.report-dir=qa-runs/multi-agent-production-acceptance \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该入口会逐条发送 `STAGE_FAILED_RETRYABLE`、`STAGE_FAILED_NEEDS_HUMAN`、`PROVIDER_FALLBACK`、`QA_FAILED`、`DELIVERY_REVIEW_FAILED`、`PR_PUBLICATION_FAILED` 六类真实 Feishu IM 告警，并要求每条真实 Feishu OpenAPI 调用返回非空且互不重复的 `messageId`。`rd.feishu.alert.smoke.task-id` 是可选参数；为空时 Feishu alert smoke 会生成专项 taskId，非空时 JSON sidecar 的 `feishuAlertTaskId` 必须等于传入的主任务 ID。Feishu 告警专项 smoke 的 `base-url` 必须是 `https://open.feishu.cn`；`mock://`、本地服务或非 Feishu host 不能作为真实送达 Feishu 的验收依据。通过时会生成 `Feishu 告警生产验收报告` Markdown 和同名 `feishu-alert-production-acceptance-*.json` 结构化 sidecar；JSON 中记录 RD-Bot 版本、生产环境标识、执行人、`feishuAlertEvidenceValidated=true`、`feishuAlertMetadataComplete=true`、`feishuAlertMessageCount=6`、六类 `alertTypes`、六个 `messageIds`，以及每条 delivery 的 `taskId`、`role`、`stageRunId`、`failureCategory`、`nextAction`、`artifactUrl`。缺少 RD-Bot 版本、生产环境标识或执行人时，Feishu 告警专项不能写出 `PASSED_FEISHU_ALERT_SMOKE`。top-level `messageIds` 必须覆盖每条 delivery 的 `messageId`，且六类必需 delivery 的 `messageId` 必须互不重复，不能只用数量相同的无关或重复 messageId 伪造可追溯性；`failureCategory` 必须等于同一条 delivery 的 `alertType`，不能用错误失败分类冒充对应告警；每条 delivery 的 `artifactUrl` 必须是绝对 URI 且不能使用 `mock://`，相对 `qa-runs/...` 路径不能写出 `PASSED_FEISHU_ALERT_SMOKE`；六类必需 delivery 的 `taskId` 必须完全一致，不能用同环境内多个任务的旧告警拼出验收通过。多 Agent 总验收应把该 JSON 路径传入 `rd.multi-agent.smoke.feishu-alert-evidence-json`，且只有版本、环境、执行人一致、每条 delivery 元数据完整并且 `feishuAlertTaskId` 等于本次总验收主任务 `taskId` 时才计入总报告 #10；日常 `./mvnw test` 不显式打开该 smoke 时可以由 JUnit 条件跳过；一旦传入 `-Drd.integration.feishu-alert.enabled=true`，缺少真实参数必须让 Maven 失败，并写出 `SKIPPED` 报告，不能记为通过。

证据产物：

- 六条 Feishu 消息链接或截图。
- 多 Agent 生产报告中的 `feishuAlertEvidenceValidated`、`feishuAlertMetadataComplete`、`feishuAlertMessageCount`、`feishuAlertTaskId` 和 `feishuAlertTypes`。
- `FeishuAlertRealSmokeTest` 生成的 messageId、Markdown 报告和包含 `taskId`、`role`、`stageRunId`、`failureCategory`、`nextAction`、`artifactUrl` 的 Feishu 告警 JSON sidecar；`artifactUrl` 必须是绝对 URI，且不能使用 `mock://`，`artifactUrl` 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`。
- 告警事件数据库查询结果。
- 服务日志中的告警 traceId。

失败判定：

- 告警只写日志，没有发送真实 Feishu。
- 告警缺少人工动作或产物链接。
- 六类必需告警的 `taskId` 不一致。
- Feishu 告警 sidecar 的 `feishuAlertTaskId` 与本次总验收主任务 `taskId` 不一致。
- 六类必需告警复用同一个 `messageId`。
- Feishu 失败被吞掉没有事件记录。

## 11. Skill 安装和使用受策略控制

验收标准：

- 生产配置中的 Skill 必须有 id、version、source、checksum、allowed roles 和 risk level。
- Agent 只能使用该角色允许的 Skill。
- 新增高风险 Skill 时进入 `WAITING_APPROVAL` 或被策略拒绝。
- 缺少 version/source/checksum/allowed roles/risk level 的 Skill 必须在安装前被拒绝，且不得调用真实安装器。
- 阶段产物记录实际使用的 Skill 版本快照。
- `SkillPolicyRealSmokeTest` 显式开启时必须记录 `installed=true`、`unauthorizedRejected=true`、`highRiskWaitingApproval=true`、`metadataValidated=true` 和 `installerCallCount=1`。
- Skill policy sidecar 的 `skillPolicyTaskId` 必须等于多 Agent 总验收主任务 `taskId`，其他任务的 Skill policy sidecar 不能计入 #11。
- `SkillPolicyRealSmokeTest` 的 `allowed-role`、`rejected-role` 和 `high-risk-role` 必须由验收命令显式传入，缺少任一项必须失败并写入缺参报告。
- Skill policy sidecar 必须记录非空且互不相同的 allowedRole、rejectedRole、highRiskRole，用来证明允许、拒绝和高风险等待审批三类角色边界。
- Skill policy sidecar 的 `installPath` 必须是绝对路径并以 `skillId/skillVersion` 结尾，证明真实安装产物属于当前验收的 Skill 身份；相对路径不能计入 #11。
- Skill policy sidecar 的 `sourceChecksum` 必须是 `sha256:` 加 64 位十六进制，证明安装源可追溯到完整内容 hash。
- 多 Agent 生产报告只有在 `skillPolicyEvidenceValidated=true`、`installed=true`、`unauthorizedRejected=true`、`highRiskWaitingApproval=true`、`metadataValidated=true` 且 `installerCallCount=1` 时，才允许把 #11 标记为 `PASSED`。

真实验证步骤：

- 在生产等价环境注册一个低风险 QA Skill 和一个高风险代码修改 Skill。
- 运行 QA 阶段，确认只安装/使用 QA Skill。
- 尝试让需求评审阶段使用高风险代码修改 Skill。
- 查询策略结果、真实安装记录和阶段产物，确认 `REJECTED` / `WAITING_APPROVAL` 决策没有对应安装动作。
- 运行 `SkillPolicyRealSmokeTest`，确认低风险 Skill 被真实复制到配置的 `install-root`，未授权角色和高风险 Skill 均没有触发额外安装。

证据产物：

- Skill registry 配置。
- Skill 安装记录。
- 策略决策 JSON。
- 阶段使用 Skill 快照。
- `SkillPolicyRealSmokeTest` 生成的 Markdown 报告、真实安装路径和 `skill-production-acceptance-*.json` 结构化 sidecar；安装路径必须是绝对路径，并和 JSON 中的 skillId、skillVersion 对齐。
- 多 Agent 生产报告中的 `skillPolicyEvidenceValidated=true`、`skillPolicyTaskId`、`skillPolicyStageRunId`、`skillPolicySkillId`、`skillPolicyInstalled`、`skillPolicyUnauthorizedRejected`、`skillPolicyHighRiskWaitingApproval` 和 `skillPolicyInstallerCallCount`；其中 `skillPolicyTaskId` 必须等于主报告 `taskId`。

失败判定：

- Agent 可使用未授权 Skill。
- Skill 没有版本、checksum 或 risk level。
- 高风险 Skill 未审批直接安装。
- 缺少生产元数据的 Skill 仍触发安装器。
- allowedRole、rejectedRole、highRiskRole 复用同一个角色，无法证明三类策略边界。
- 安装路径不是绝对路径，或与 skillId、skillVersion 不一致的 sidecar 不能计入 #11。
- 短 checksum 或非 sha256 格式不能计入 #11。
- 其他任务的 Skill policy sidecar 不能计入 #11。
- 显式开启 Skill smoke 时缺少真实参数却被记为通过。

## 12. 经验自动沉淀且可被后续 RAG 检索

验收标准：

- 完整交付后，产品文档、技术方案、QA 日志、交付报告和经验条目进入数据库。
- 最小生产 smoke 的经验类型必须覆盖 `REQUIREMENT_REVIEW`、`TECHNICAL_DESIGN`、`CODE_CHANGE`、`QA_REPORT` 和 `DELIVERY_REPORT`，并写入总报告的 `experienceTypes`。
- 经验条目关联 taskId、stageRunId、sourceArtifactId、hash 和脱敏状态。
- 最小生产 smoke 必须查询生产 `rd_experience_entries` 表并写入 `experienceHashCount`、`experienceRedactedCount` 与 `experienceCompleteTypeCount`；`experienceCompleteTypeCount` 必须逐类覆盖五类必需经验类型，且每一类都同时具备 `sourceArtifactId`、hash 和 `redacted=true`，缺少任一项的经验不能计入 #12 通过。
- 当前切片通过 `WorkflowExperienceStore.searchReusable` 检索可复用、非失败、已脱敏经验；后续相似需求的角色上下文必须出现 `rd-experience://{experienceId}` 经验证据。
- 完整知识库摄取可作为后续增强，但最小生产 smoke 不能只停留在“经验入库”，必须创建 follow-up 真实需求并查询其角色上下文。
- follow-up 真实需求的 `followUpTaskId` 必须非空且不同于本次总验收主任务 `taskId`，不能复用主任务上下文自证经验可检索。
- 未通过复核的产物不能作为成功经验入库。

真实验证步骤：

- 完成一条真实需求交付。
- 查询经验表和资产表。
- 创建相似 follow-up 需求并查看角色上下文证据来源。
- 查询 `rd_role_context_packages.evidence_json`，确认至少一条 evidence 的 `sourceUri` 以 `rd-experience://` 开头。

证据产物：

- 经验表查询结果。
- follow-up 任务 ID，且必须不同于本次总验收主任务 `taskId`。
- 后续需求上下文包中引用的 `rd-experience://` 经验证据。
- 多 Agent 生产报告中的 `experienceCompleteTypeCount>=5`，证明五类必需经验都可追溯到原始产物、hash 和脱敏状态。

失败判定：

- 经验没有关联原始产物。
- 只有 `experienceHashCount`、`experienceRedactedCount` 或 `experienceSourceLinkCount` 总数达标，但 `experienceCompleteTypeCount<5`，不能计入 #12 通过。
- 未脱敏内容入库。
- 后续角色上下文无法检索到已沉淀经验。
- `followUpTaskId` 为空或等于本次总验收主任务 `taskId`。
- 失败产物被标记为成功经验。

## 13. 密钥和敏感信息不进入产物

验收标准：

- prompt 快照、Docker metadata、result.json、QA 报告、PR 发布 metadata、PR body、Feishu 消息、经验条目均不包含明文密钥。
- provider 环境变量只记录变量名，不记录变量值。
- `secret-scan-needles` 按 CSV 解析并裁剪空白后，必须至少包含一个非空真实生产敏感值；报告只记录 `secretNeedleCount`，不记录 secret 原值。
- 多 Agent 真实 smoke 必须扫描最终任务详情、角色上下文包、provider attempts、`executionResultJson.pullRequestPublication.metadataJson` 和所有 `rd_agent_stage_artifacts.content_preview`；报告必须记录 `secretScanEvidenceValidated=true`、`secretScannedValueCount`、`secretScannedArtifactPreviewCount` 和 `secretScannedPullRequestMetadata=true`。
- 多 Agent 总报告必须同时接入 `rd.multi-agent.smoke.github-pr-remote-evidence-json`，并证明远端 PR body 的 `secretScanEvidenceValidated=true`、`secretLeakFound=false` 和 `secretScannedValueCount>0`，否则 #13 保持 `NOT_RUN`。
- 远端 PR body 与 Feishu 消息仍需在对应 GitHub/Feishu 专项验收中反查，不能只用本地 metadata 替代。

真实验证步骤：

- 对一条真实工作流的最终任务详情、角色上下文包、provider attempts、PR 发布 metadata 和全部阶段产物 preview 运行脱敏扫描。
- 在 GitHub/Feishu 专项验收中反查远端 PR body 和 Feishu 消息。
- 查询 provider metadata。

证据产物：

- 脱敏扫描报告。
- `secretScannedValueCount`、`secretScannedArtifactPreviewCount`、`secretScannedPullRequestMetadata=true`。
- PR body 专项反查记录：`github-pr-remote-evidence-production-acceptance-*.json`，必须证明 `secretScanEvidenceValidated=true`、`secretLeakFound=false` 且 `secretScannedValueCount>0`，同时不得记录 PR body 全文或 secret needle 原值。
- Feishu 消息专项反查记录。
- provider metadata。

失败判定：

- 任何产物出现明文 key、token、password。
- metadata 记录真实凭据值。
- `secretScannedPullRequestMetadata=false` 或未能解析并扫描 `executionResultJson.pullRequestPublication.metadataJson`。
- 未配置任何 secret needle，导致空扫描被当成通过。
- 脱敏扫描未运行。

## 14. 指标和审计可观测

验收标准：

- 每个阶段记录 latency、provider、attempt、状态、错误分类、产物数量。
- 工作流记录 context build latency、validation pass rate、PR creation result、human intervention reason。
- 最小生产 smoke 通过时，报告必须记录 `auditChainEvidenceValidated=true`、`auditStageEventCount`、`auditRoleContextPackageCount`、`auditArtifactLinkCount`、`auditExperienceLinkCount` 和 `auditPullRequestTraceValidated=true`。
- 最小审计链必须能从 taskId 追踪到主任务 timeline、`rd_agent_stage_events`、四角色上下文包、阶段 prompt/result artifacts、经验条目 `source_artifact_id` 和 PR 发布 metadata。
- 完整生产专项验收还必须能从远端 PR 反查 taskId，再反查上下文、阶段产物、告警、经验条目和指标统计；#14 完整通过必须同时接入 GitHub PR 远端反查 sidecar。

真实验证步骤：

- 完成一条真实需求。
- 运行 `MultiAgentRequirementDeliveryRealSmokeTest`，检查报告中最小审计链字段。
- 运行 `ObservabilityMetricsRealSmokeTest`，必须显式传入 `-Drd.integration.observability-metrics.enabled=true`、`-Drd.observability-metrics.smoke.production-evidence=true`、`-Drd.observability-metrics.smoke.task-id=<same-main-task-id>`、真实 RD-Bot HTTP URL、真实 PostgreSQL 连接、GitHub PR 远端反查 sidecar 和真实 secret scan needles。
- 查询指标接口或数据库统计。
- 从远端 PR URL 反查完整审计链。

```bash
./mvnw -pl bootstrap -am -Dtest=ObservabilityMetricsRealSmokeTest \
  -Drd.integration.observability-metrics.enabled=true \
  -Drd.observability-metrics.smoke.production-evidence=true \
  -Drd.observability-metrics.smoke.rd-bot-version=<deployed-version> \
  -Drd.observability-metrics.smoke.environment-id=<prod-or-prod-like-env-id> \
  -Drd.observability-metrics.smoke.executed-by=<operator-or-ci-job> \
  -Drd.observability-metrics.smoke.base-url=https://<real-rd-bot-host> \
  -Drd.observability-metrics.smoke.postgres-url=jdbc:postgresql://<real-postgres-host>:5432/rd_bot \
  -Drd.observability-metrics.smoke.postgres-user=<real-user> \
  -Drd.observability-metrics.smoke.postgres-password="$RD_BOT_POSTGRES_PASSWORD" \
  -Drd.observability-metrics.smoke.task-id=<same-main-task-id> \
  -Drd.observability-metrics.smoke.github-pr-remote-evidence-json=<github-pr-remote-evidence-json> \
  -Drd.observability-metrics.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

证据产物：

- 最小 smoke 报告中的审计链字段。
- `observability-metrics-production-acceptance-*.json`，必须记录 `observabilityMetricsEvidenceValidated=true`、`observabilityTaskId`、`taskId`、`metricsEndpointUrl`、`metricsHttpStatus=200`、`contextBuildLatencyMetricPresent=true`、`repairSuccessRateMetricPresent=true`、`validationPassRateMetricPresent=true`、`prCreationRateMetricPresent=true`、`humanInterventionRateMetricPresent=true`、`retryRateMetricPresent=true`、`meanTimeToRepairMetricPresent=true`、`topFailureCategoriesMetricPresent=true`、`stageMetricCount>=4`、`auditTraceQuerySucceeded=true`、`remotePrTraceValidated=true`、`auditTraceLinkCount>=8`、`taskBoundAuditTraceLinkCount>=8`、RD-Bot 版本、环境标识和执行人；`metricsEndpointUrl` 必须等于本次 `rd.multi-agent.smoke.base-url` 派生的 `/actuator/prometheus`，`observabilityTaskId` 必须等于 sidecar 的 `taskId`，不得使用其他环境或外部服务的 200 响应替代本次生产指标证据；审计链必须绑定到同一 `taskId`，不能把其他任务的阶段事件、上下文、产物、经验或 PR trace 拼接计入 #14。
- `github-pr-remote-evidence-production-acceptance-*.json` 是远端 PR 反查 taskId 的必要专项子证据；只有它的 `taskId`、RD-Bot 版本、环境标识和执行人与本次总验收一致，且证明远端 PR body 包含 `taskId` 和产物链接时，#14 才允许标记为 `PASSED`。
- 多 Agent 总报告中 `observabilityMetricsEvidenceValidated=true`、`observabilityMetricsTaskId`、`observabilityMetricsEndpointUrl`、`observabilityMetricsHttpStatus=200`、八类指标 present 字段、`observabilityStageMetricCount`、`observabilityAuditTraceQuerySucceeded=true`、`observabilityRemotePrTraceValidated=true`、`observabilityAuditTraceLinkCount`、`observabilityTaskBoundAuditTraceLinkCount` 和 `githubPrRemoteEvidenceValidated=true`。
- 指标查询结果。
- 远端 PR 反查审计链结果。
- 任务报告。

失败判定：

- 最小 smoke 报告没有 `auditChainEvidenceValidated=true`。
- taskId 无法追踪到阶段事件、角色上下文、阶段产物、经验来源和 PR 发布 metadata。
- 无法从远端 PR 反查 taskId。
- 阶段缺少耗时或错误分类。
- 产物和告警无法串起来。

## 15. 生产真实测试结论要求

验收标准：

- 最终验收报告必须给出 1-15 每个验收点的 `PASSED` / `FAILED` / `NOT_RUN` 结论，不能把未运行项写成通过。
- `PASSED` 项必须绑定真实生产证据，包括 taskId、stageRunId、PR URL、Feishu messageId 或数据库查询结果。
- `NOT_RUN` 项必须列出缺少的真实生产条件和下一步补验命令。
- #15 只有在 #1-#14 全部为 `PASSED` 时才允许标记为 `PASSED`。
- 任一 #1-#14 验收点为 `FAILED` 或 `NOT_RUN` 时，#15 必须为 `FAILED` 或 `NOT_RUN`，并列出未通过或未运行的验收点编号。
- Feishu、Skill 等专项 smoke 报告不能单独把 #15 标记为 `PASSED`；它们只能作为对应专项验收点的 sidecar 证据输入多 Agent 总报告。
- 专项 smoke 失败报告只能把自身覆盖的验收点标记为 `FAILED`，未覆盖验收点必须保持 `NOT_RUN`，避免把未执行的生产验收错误归因为失败。
- 报告必须包含 RD-Bot 版本、生产环境标识、验收时间和执行人。
- 报告不得包含明文密钥、token、数据库密码或模型 provider 凭据。
- 允许使用 `ProductionAcceptanceEvidenceLedgerSnapshotTest` 生成 `production-acceptance-evidence-ledger-*.json` 作为阶段收口台账；证据台账只能汇总既有 sidecar，不得把本机 Maven 回归计为生产验收通过。
- 证据台账必须把最新总 smoke 的 `missingRequirements` 展开到每个 `NOT_RUN` 验收点，便于直接判断下一次是否值得进入长链路。
- 证据台账必须输出 `nextActions`；当 provider preflight 出现 `PROVIDER_AUTHENTICATION_FAILED` 时，下一步动作必须指向重新注入有效 provider secret，当出现 `PROVIDER_QUOTA_OR_RATE_LIMIT` 时，下一步动作必须指向恢复 provider quota 或 Token Plan 额度。
- 最终完成声明前必须运行最终验收门禁 `ProductionAcceptanceFinalGateSnapshotTest`，并显式开启 `rd.integration.final-acceptance-gate.enabled=true`；该门禁只接受最新 `production-acceptance-evidence-ledger-*.json` 为 `PASSED_PRODUCTION_ACCEPTANCE_LEDGER`、15 项全 `PASSED`、`failedCount=0` 且 `notRunCount=0`。

真实验证步骤：

- 汇总最小生产 smoke 报告和专项演练报告。
- 运行证据台账快照，检查 `production-acceptance-evidence-ledger-*.json` 中 15 个验收点数量、结论、`missingRequirements` 展开结果和 `nextActions`。
- 开启 `rd.integration.final-acceptance-gate.enabled=true` 运行最终验收门禁；若门禁失败，必须按 `nextActions` 修复外部条件后重新生成台账，不能宣称完整交付完成。
- 逐项核对 1-15 的结论和证据链接。
- 对最终报告执行敏感信息扫描。

证据产物：

- 生产环境标识和 RD-Bot 版本。
- taskId、stageRunId、PR URL、Feishu 消息链接。
- 每个验收点的通过/失败结论。
- 每个失败项的错误日志、数据库记录和下一步修复建议。
- 未运行项必须说明缺少的真实生产条件，不能写成通过。

失败判定：

- 任一验收点缺少结论或证据。
- 未运行项被标记为通过。
- 报告中出现明文密钥或 provider 凭据。
- 报告无法追溯到真实生产 taskId、PR URL 或 Feishu 消息。
