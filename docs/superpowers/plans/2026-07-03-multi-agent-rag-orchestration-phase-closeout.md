# RD-Bot 多 Agent RAG 编排阶段性收口报告

- 收口时间：2026-07-04 10:26 Asia/Shanghai
- 当前结论：阶段性工程改造已形成可 review 的工作树和验收文档；完整生产验收尚未完成。
- 不能标记为完成的原因：缺少真实生产或生产等价环境参数，最近的生产 smoke 报告均为 `SKIPPED`，没有真实访问 RD-Bot HTTP、PostgreSQL、Docker/provider、GitHub PR 或 Feishu 告警链路。
- 执行决策：选择方案 A，先把当前代码作为阶段成果提交 review；本阶段停止继续补本地边界测试，后续只有拿到真实生产等价环境参数后再继续跑生产验收。

## 1. 已完成的阶段性改造

### 文档

- 技术设计文档：`docs/superpowers/plans/2026-07-01-multi-agent-rag-orchestration-technical-design.md`
- 生产验收文档：`docs/superpowers/plans/2026-07-01-multi-agent-rag-orchestration-production-acceptance.md`
- 验收文档覆盖 15 个验收点，并区分多 Agent 主链路、Feishu 告警、GitHub PR 远端反查、Skill policy、workflow recovery、requirement review blocker、Docker coding、QA blocker、delivery review blocker、observability metrics 等专项 sidecar。

### 引擎层

- 新增多角色 Agent 编排基础模型：`engine/src/main/java/com/wish/rd/engine/agent/`
- 主要角色包括需求评审、方案设计、编码、QA。
- `RequirementDeliveryEngine` 已从单一交付流程扩展为多阶段编排入口，负责阶段计划、上下文分发、阶段产物回收、复核、PR 发布和经验沉淀。
- `RequirementDeliveryReviewer`、`RequirementPullRequestPublisherPort`、`RequirementPullRequestPublication` 等接口把交付复核和代码平台发布从核心编排中抽象出来。

### RAG 上下文

- 新增角色上下文包模型：`rag/src/main/java/com/wish/rd/rag/context/`
- `RoleContextBuilder` 面向不同 Agent 角色输出不同的证据组合、验收关注点、风险提示和上下文预算。
- 上下文不再是“一份证据发给所有 Agent”，而是按角色构造、落库、绑定到阶段运行。

### 状态机和持久化

- 新增阶段状态、阶段运行、阶段事件、阶段产物和经验条目的 engine 端口。
- 新增 PostgreSQL SQL：`bootstrap/src/main/resources/sql/postgres/p1_multi_agent_orchestration.sql`
- SQL 覆盖：
  - `rd_role_context_packages`
  - `rd_agent_stage_runs`
  - `rd_agent_stage_artifacts`
  - `rd_agent_stage_events`
  - `rd_agent_skill_installations`
  - `rd_experience_entries`
- bootstrap 下新增 PostgreSQL store/mapper/entity，用于角色上下文、阶段运行、阶段产物和经验沉淀的持久化适配。

### 交付稳定性和复核

- 增加 provider attempts 证据、失败分类、阶段 artifact、QA 结果协议、交付复核结果和 PR 发布证据。
- 增加 GitHub code platform 和 GitHub PR remote evidence 的生产 smoke/sidecar 校验。
- PR 远端反查已加固：要求远端 PR URL、host/path、PR number、`workBranch=requirement/{taskId}`、PR body 中完整 taskId token、产物 evidence/log/report/artifact 行和 secret scan 证据一致。

### 告警

- 新增 `AgentWorkflowAlertSinkPort` 和 Feishu IM 告警适配。
- 增加六类工作流告警的生产 smoke 文档和 sidecar 校验，包括失败分类、下一步动作、artifact URL、stageRunId、role、taskId 等字段。

### Skill 扩展

- 新增 `skill` 模块的 Skill 描述、安装命令、安装引擎、策略门禁、风险等级和 registry/installer port。
- bootstrap 下新增本地文件系统 Skill 安装器，用于真实文件系统安装 smoke。
- 生产验收要求 Skill 必须有 id、version、source、checksum、allowed roles、risk level；高风险 Skill 必须等待审批或被拒绝。

### 经验沉淀

- 新增 `WorkflowExperienceStore`、`WorkflowExperienceEntry`、`WorkflowExperienceType`。
- 经验来源绑定阶段产物，支持产品文档、技术方案、QA 日志、失败经验、交付报告等类型沉淀。
- 验收文档要求后续任务的 RAG 能检索到 `rd-experience://` 历史经验 URI。

### 生产 smoke 同一任务绑定

- 多 Agent 主链路新增 `rd.multi-agent.smoke.task-id`，可在已存在真实任务上做复核，不强制创建新任务。
- Feishu 告警 sidecar 新增 `rd.feishu.alert.smoke.task-id`，可以把告警验收绑定到同一个真实任务。
- Skill policy sidecar 新增 `rd.skill.smoke.task-id`，可以把 Skill 安装、策略拒绝和高风险审批证据绑定到同一个真实任务。
- Workflow recovery 新增 `WorkflowRecoveryRealSmokeTest`、`WorkflowRecoveryProductionAcceptanceProfile` 和 `WorkflowRecoveryProductionAcceptanceReport`，显式启用后会访问真实 RD-Bot HTTP 和 PostgreSQL，校验重启后阶段运行、阶段事件、重复成功阶段、交付复核顺序，并生成 `workflow-recovery-production-acceptance-*.json`。
- Requirement review blocker 新增 `RequirementReviewBlockerRealSmokeTest`、`RequirementReviewBlockerProductionAcceptanceProfile` 和 `RequirementReviewBlockerProductionAcceptanceReport`，显式启用后会访问真实 RD-Bot HTTP 和 PostgreSQL，校验 `REQUIREMENT_REVIEWER` 阶段阻断、主任务 `FAILED_NEEDS_HUMAN`、最终 `NEEDS_HUMAN`、后续三角色未派发、真实 Feishu 告警和可追溯 review artifact URI，并生成 `requirement-review-blocker-production-acceptance-*.json`。
- QA failure blocker 新增 `QaFailureBlockerRealSmokeTest`、`QaFailureBlockerProductionAcceptanceProfile` 和 `QaFailureBlockerProductionAcceptanceReport`，显式启用后会访问真实 RD-Bot HTTP 和 PostgreSQL，校验 `QA_AGENT` 阶段 `FAILED_VALIDATION`、主任务 `FAILED_NEEDS_HUMAN`、最终阻断状态、失败验收项、真实 QA report/log artifact URI、未创建 PR/成功报告、真实 Feishu QA 失败告警，并生成 `qa-failure-blocker-production-acceptance-*.json`。
- Docker coding 新增 `DockerCodingRealSmokeTest`、`DockerCodingProductionAcceptanceProfile` 和 `DockerCodingProductionAcceptanceReport`，显式启用后会访问真实 RD-Bot HTTP 和 PostgreSQL，校验 `CODING_AGENT` 阶段 `SUCCEEDED`、真实 Docker metadata、四类编码产物 id、生产 artifact URI、结构化 result、真实测试命令和 secret scan，并生成 `docker-coding-production-acceptance-*.json`。
- Delivery review failure 新增 `DeliveryReviewFailureRealSmokeTest`、`DeliveryReviewFailureProductionAcceptanceProfile` 和 `DeliveryReviewFailureProductionAcceptanceReport`，显式启用后会访问真实 RD-Bot HTTP 和 PostgreSQL，校验主任务 `REJECTED`、复核决策 `REJECTED`、阶段级 `pullRequestUrl` 越权被拦截、未创建 PR/成功报告、失败交付报告落库、真实 Feishu 交付复核失败告警和可追溯 review artifact URI，并生成 `delivery-review-failure-production-acceptance-*.json`。
- Observability metrics 新增 `ObservabilityMetricsRealSmokeTest`、`ObservabilityMetricsProductionAcceptanceProfile` 和 `ObservabilityMetricsProductionAcceptanceReport`，显式启用后会访问真实 RD-Bot HTTP `/actuator/prometheus`、真实 PostgreSQL 和 GitHub PR 远端反查 sidecar，校验八类指标、阶段指标数量、同 taskId 审计链和远端 PR trace，并生成 `observability-metrics-production-acceptance-*.json`。
- 运行手册已补充同一 `RD_BOT_ACCEPTANCE_TASK_ID` 下的主链路、Feishu、Skill、Workflow recovery、Requirement review blocker、Docker coding、QA failure blocker、Delivery review failure 和 Observability metrics sidecar 命令，避免生产验收时拆成多条互不关联的任务链路。

## 2. 当前已执行验证

### 本地完整回归

- 命令：`./mvnw -q test`
- 执行时间：2026-07-04 10:26 Asia/Shanghai
- 结果：退出码 `0`
- 说明：输出中存在负向测试故意触发的异常栈，例如权限错误、provider boom、非法 webhook body；Maven 最终结果通过。

### Workflow Recovery 聚焦验证

- 命令：`./mvnw -pl bootstrap -am -Dtest=ProductionAcceptanceDocumentTest,WorkflowRecoveryProductionAcceptanceReportTest,WorkflowRecoveryRealSmokePreconditionsTest,WorkflowRecoveryEvidenceFileTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：35 tests，0 failures，0 errors，0 skipped
- 说明：该验证证明 workflow recovery sidecar 的 profile/report/JSON 聚合契约和验收文档命令已对齐；没有真实环境参数时不会执行生产 smoke。

### Requirement Review Blocker 聚焦验证

- 命令：`./mvnw -pl bootstrap -am -Dtest=RequirementReviewBlockerProductionAcceptanceReportTest,RequirementReviewBlockerRealSmokePreconditionsTest,RequirementReviewBlockerEvidenceFileTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：13 tests，0 failures，0 errors，0 skipped
- 说明：该验证证明 requirement review blocker sidecar 的 profile/report/JSON 聚合契约可编译并通过；没有真实环境参数时不会执行生产 smoke。

### Docker Coding 和验收文档聚焦验证

- 命令：`./mvnw -pl bootstrap -am -Dtest=DockerCodingProductionAcceptanceReportTest,DockerCodingRealSmokePreconditionsTest,ProductionAcceptanceDocumentTest,DockerCodingEvidenceFileTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：35 tests，0 failures，0 errors，0 skipped
- 说明：该验证证明 Docker coding sidecar 的 profile/report/JSON 聚合契约和验收文档命令已对齐；没有真实环境参数时不会执行生产 smoke。

### Observability Metrics 和验收文档聚焦验证

- 命令：`./mvnw -pl bootstrap -am -Dtest=ObservabilityMetricsProductionAcceptanceReportTest,ObservabilityMetricsRealSmokePreconditionsTest,ProductionAcceptanceDocumentTest,ObservabilityMetricsEvidenceFileTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：34 tests，0 failures，0 errors，0 skipped
- 说明：该验证证明 observability metrics sidecar 的 profile/report/JSON 聚合契约和验收文档命令已对齐；没有真实环境参数时不会执行生产 smoke。

### QA Failure Blocker 聚焦验证

- 命令：`./mvnw -pl bootstrap -am -Dtest=QaFailureBlockerProductionAcceptanceReportTest,QaFailureBlockerRealSmokePreconditionsTest,QaFailureBlockerEvidenceFileTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：14 tests，0 failures，0 errors，0 skipped
- 说明：该验证证明 QA failure blocker sidecar 的 profile/report/JSON 聚合契约可编译并通过；没有真实环境参数时不会执行生产 smoke。

### Delivery Review Failure 和验收文档聚焦验证

- 命令：`./mvnw -pl bootstrap -am -Dtest=ProductionAcceptanceDocumentTest,DeliveryReviewFailureProductionAcceptanceReportTest,DeliveryReviewFailureRealSmokePreconditionsTest,DeliveryReviewFailureEvidenceFileTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：34 tests，0 failures，0 errors，0 skipped
- 说明：该验证证明 delivery review failure sidecar 的 profile/report/JSON 聚合契约和验收文档命令已对齐；没有真实环境参数时不会执行生产 smoke。

### Surefire 失败计数扫描

- 命令：`rg -n "Failures: [1-9]|Errors: [1-9]" bootstrap/target/surefire-reports engine/target/surefire-reports rag/target/surefire-reports exec/target/surefire-reports skill/target/surefire-reports`
- 结果：无命中
- 说明：`rg` 无命中退出码为 `1`，这里表示没有发现失败计数。

### Diff Whitespace 检查

- 命令：`git diff --check`
- 结果：退出码 `0`
- 说明：当前已跟踪文件 diff 没有 whitespace error。

### 当前生产 smoke 报告

这些报告证明“门禁能识别缺少真实生产参数并拒绝记为通过”，不证明生产验收已通过。

- 多 Agent 主链路：`qa-runs/multi-agent-production-acceptance/multi-agent-production-acceptance-20260703-103702.md`
  - 结论：`SKIPPED`
  - 缺失：RD-Bot 版本、环境标识、执行人、base URL、PostgreSQL、仓库、provider secrets、GitHub 模式/凭据、secret scan needles 等。
- Feishu 告警：`qa-runs/multi-agent-production-acceptance/feishu-alert-production-acceptance-20260703-104002.md`
  - 结论：`SKIPPED`
  - 缺失：Feishu app id、app secret、chat id、生产元数据、secret scan needles。
- GitHub PR 远端反查：`qa-runs/multi-agent-production-acceptance/github-pr-remote-evidence-production-acceptance-20260703-103144.md`
  - 结论：`SKIPPED`
  - 缺失：GitHub PAT 或等价凭据、repo owner/name、PR number、work branch、taskId、生产元数据、secret scan needles。

## 3. 尚未完成的生产验收

生产验收仍未完成，原因不是当前本地单测失败，而是缺少真实外部系统和凭据。按验收文档，至少需要补齐以下信息后才能继续真实跑：

### 多 Agent 主链路

- `rd.multi-agent.smoke.production-evidence=true`
- `rd.multi-agent.smoke.rd-bot-version`
- `rd.multi-agent.smoke.environment-id`
- `rd.multi-agent.smoke.executed-by`
- `rd.multi-agent.smoke.base-url`
- `rd.multi-agent.smoke.postgres-url`
- `rd.multi-agent.smoke.postgres-user`
- `rd.multi-agent.smoke.postgres-password`
- `rd.multi-agent.smoke.repository-url`
- `rd.multi-agent.smoke.repo-owner`
- `rd.multi-agent.smoke.repo-name`
- `rd.multi-agent.smoke.expected-provider-count`
- `rd.multi-agent.smoke.provider-secret-env-names`，至少两个非空 provider secret env
- `rd.multi-agent.smoke.github-code-platform-mode=real`
- `rd.multi-agent.smoke.github-auth-mode`
- `rd.multi-agent.smoke.github-credential-env-names`
- `rd.multi-agent.smoke.secret-scan-needles`

### 专项 sidecar

多 Agent 总报告要把 #3、#6、#7、#8、#9、#10、#11、#13、#14 等验收点记为通过，还需要这些真实专项 JSON：

- `rd.multi-agent.smoke.feishu-alert-evidence-json`
- `rd.multi-agent.smoke.recovery-evidence-json`
- `rd.multi-agent.smoke.skill-policy-evidence-json`
- `rd.multi-agent.smoke.requirement-review-evidence-json`
- `rd.multi-agent.smoke.docker-coding-evidence-json`
- `rd.multi-agent.smoke.qa-failure-evidence-json`
- `rd.multi-agent.smoke.delivery-review-failure-evidence-json`
- `rd.multi-agent.smoke.github-pr-remote-evidence-json`
- `rd.multi-agent.smoke.observability-metrics-evidence-json`

### Feishu 告警

- `rd.integration.feishu-alert.enabled=true`
- `rd.feishu.alert.smoke.production-evidence=true`
- `rd.feishu.alert.smoke.rd-bot-version`
- `rd.feishu.alert.smoke.environment-id`
- `rd.feishu.alert.smoke.executed-by`
- `rd.feishu.alert.smoke.app-id`
- `rd.feishu.alert.smoke.app-secret`
- `rd.feishu.alert.smoke.chat-id`
- `rd.feishu.alert.smoke.secret-scan-needles`

### GitHub PR 远端反查

- `rd.integration.github-pr-evidence.enabled=true`
- `rd.github.pr-evidence.production-evidence=true`
- `rd.github.pr-evidence.rd-bot-version`
- `rd.github.pr-evidence.environment-id`
- `rd.github.pr-evidence.executed-by`
- `rd.github.pr-evidence.task-id`
- `rd.github.pr-evidence.repo-owner`
- `rd.github.pr-evidence.repo-name`
- `rd.github.pr-evidence.base-branch`
- `rd.github.pr-evidence.work-branch`
- `rd.github.pr-evidence.pull-number`
- `rd.github.pr-evidence.secret-scan-needles`
- `GITHUB_PAT` 或等价 GitHub App / gh CLI 凭据

### Skill policy

- `rd.integration.skill-policy.enabled=true`
- `rd.skill.smoke.production-evidence=true`
- `rd.skill.smoke.rd-bot-version`
- `rd.skill.smoke.environment-id`
- `rd.skill.smoke.executed-by`
- `rd.skill.smoke.skill-id`
- `rd.skill.smoke.skill-version`
- `rd.skill.smoke.skill-source-uri=file://...`
- `rd.skill.smoke.skill-checksum=sha256:<64-hex>`
- `rd.skill.smoke.install-root`
- `rd.skill.smoke.allowed-role`
- `rd.skill.smoke.rejected-role`
- `rd.skill.smoke.high-risk-role`

### Docker coding

- `rd.integration.docker-coding.enabled=true`
- `rd.docker-coding.smoke.production-evidence=true`
- `rd.docker-coding.smoke.rd-bot-version`
- `rd.docker-coding.smoke.environment-id`
- `rd.docker-coding.smoke.executed-by`
- `rd.docker-coding.smoke.base-url`
- `rd.docker-coding.smoke.postgres-url`
- `rd.docker-coding.smoke.postgres-user`
- `rd.docker-coding.smoke.postgres-password`
- `rd.docker-coding.smoke.repository-url`
- `rd.docker-coding.smoke.task-id`
- `rd.docker-coding.smoke.patch-artifact-uri`
- `rd.docker-coding.smoke.result-artifact-uri`
- `rd.docker-coding.smoke.test-log-artifact-uri`
- `rd.docker-coding.smoke.docker-metadata-artifact-uri`
- `rd.docker-coding.smoke.secret-scan-needles`

### Requirement review blocker

- `rd.integration.requirement-review-blocker.enabled=true`
- `rd.requirement-review.smoke.production-evidence=true`
- `rd.requirement-review.smoke.rd-bot-version`
- `rd.requirement-review.smoke.environment-id`
- `rd.requirement-review.smoke.executed-by`
- `rd.requirement-review.smoke.base-url`
- `rd.requirement-review.smoke.postgres-url`
- `rd.requirement-review.smoke.postgres-user`
- `rd.requirement-review.smoke.postgres-password`
- `rd.requirement-review.smoke.task-id`
- `rd.requirement-review.smoke.review-artifact-uri`
- `rd.requirement-review.smoke.feishu-alert-message-id`
- `rd.requirement-review.smoke.secret-scan-needles`

### QA failure blocker

- `rd.integration.qa-failure.enabled=true`
- `rd.qa-failure.smoke.production-evidence=true`
- `rd.qa-failure.smoke.rd-bot-version`
- `rd.qa-failure.smoke.environment-id`
- `rd.qa-failure.smoke.executed-by`
- `rd.qa-failure.smoke.base-url`
- `rd.qa-failure.smoke.postgres-url`
- `rd.qa-failure.smoke.postgres-user`
- `rd.qa-failure.smoke.postgres-password`
- `rd.qa-failure.smoke.task-id`
- `rd.qa-failure.smoke.qa-report-artifact-uri`
- `rd.qa-failure.smoke.validation-log-artifact-uris`
- `rd.qa-failure.smoke.feishu-alert-message-id`
- `rd.qa-failure.smoke.secret-scan-needles`

### Delivery review failure

- `rd.integration.delivery-review-failure.enabled=true`
- `rd.delivery-review-failure.smoke.production-evidence=true`
- `rd.delivery-review-failure.smoke.rd-bot-version`
- `rd.delivery-review-failure.smoke.environment-id`
- `rd.delivery-review-failure.smoke.executed-by`
- `rd.delivery-review-failure.smoke.base-url`
- `rd.delivery-review-failure.smoke.postgres-url`
- `rd.delivery-review-failure.smoke.postgres-user`
- `rd.delivery-review-failure.smoke.postgres-password`
- `rd.delivery-review-failure.smoke.task-id`
- `rd.delivery-review-failure.smoke.review-artifact-uri`
- `rd.delivery-review-failure.smoke.feishu-alert-message-id`
- `rd.delivery-review-failure.smoke.secret-scan-needles`

### Observability metrics

- `rd.integration.observability-metrics.enabled=true`
- `rd.observability-metrics.smoke.production-evidence=true`
- `rd.observability-metrics.smoke.rd-bot-version`
- `rd.observability-metrics.smoke.environment-id`
- `rd.observability-metrics.smoke.executed-by`
- `rd.observability-metrics.smoke.base-url`
- `rd.observability-metrics.smoke.postgres-url`
- `rd.observability-metrics.smoke.postgres-user`
- `rd.observability-metrics.smoke.postgres-password`
- `rd.observability-metrics.smoke.task-id`
- `rd.observability-metrics.smoke.github-pr-remote-evidence-json`
- `rd.observability-metrics.smoke.secret-scan-needles`

## 4. 执行选择

本轮选择方案 A：先提交阶段成果。原因是当前缺少真实生产等价环境和凭据，继续补本地边界测试不能替代生产验收，反而会扩大 review 范围。生产验收继续入口已经收敛到运行手册和真实 smoke sidecar，等参数齐备后再执行方案 B。

### 方案 A：先提交阶段成果

适用场景：现在没有完整真实生产环境或凭据。

建议动作：

- 将当前工作树按功能边界拆分 review：
  - docs：技术设计和生产验收文档。
  - engine/rag/skill：多 Agent 编排、角色上下文和 Skill 模型。
  - bootstrap persistence/adapters：PostgreSQL、Feishu、GitHub、Docker/执行适配。
  - production acceptance tests：真实 smoke 门禁和 sidecar 校验。
- 明确 PR 描述：本阶段完成工程能力和真实验收入口，但生产验收状态仍为 `SKIPPED`，需要真实参数补跑。

### 方案 B：继续真实生产验收

适用场景：可以提供生产等价环境和凭据。

建议动作：

- 先跑 Feishu 告警 smoke，生成 Feishu sidecar。
- 跑 Skill policy smoke，生成 Skill sidecar。
- 跑 GitHub PR 远端反查 smoke，生成 GitHub PR sidecar。
- 跑 workflow recovery、requirement review blocker、Docker coding、QA blocker、delivery review blocker、observability metrics 等专项 smoke。
- 最后运行 `MultiAgentRequirementDeliveryRealSmokeTest` 汇总 15 个验收点。

## 5. 当前风险

- 工作树改动很大，尚未拆分提交，review 成本高。
- 当前 `docs/superpowers/plans/*.md` 已被 `.gitignore` 反向规则显式放行；其他 `docs/` 路径若要进入 PR 仍需单独确认。
- 真实生产验收依赖外部系统；没有真实参数时继续补本地边界测试的收益已经低于阶段收口。
- 当前 Java 层已经承担编排和证据门禁；不要再把模型、向量库、Docker、GitHub、Feishu 的具体实现写进核心 engine/rag 领域层，应继续通过 port/adapter 接入。
