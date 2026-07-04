package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionAcceptanceDocumentTest {

    private static final Pattern ACCEPTANCE_POINT_HEADING = Pattern.compile("^## (\\d+)\\. .*$");

    @Test
    void shouldRequireAcceptanceCriteriaForEveryNumberedProductionAcceptancePoint() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());
        List<String> missingCriteria = new ArrayList<>();
        String[] lines = markdown.split("\\R");
        int currentPoint = -1;
        boolean currentPointHasCriteria = false;

        for (String line : lines) {
            Matcher matcher = ACCEPTANCE_POINT_HEADING.matcher(line);
            if (matcher.matches()) {
                if (currentPoint >= 1 && !currentPointHasCriteria) {
                    missingCriteria.add(String.valueOf(currentPoint));
                }
                currentPoint = Integer.parseInt(matcher.group(1));
                currentPointHasCriteria = false;
                continue;
            }
            if (currentPoint >= 1 && "验收标准：".equals(line.strip())) {
                currentPointHasCriteria = true;
            }
        }
        if (currentPoint >= 1 && !currentPointHasCriteria) {
            missingCriteria.add(String.valueOf(currentPoint));
        }

        assertEquals(List.of(), missingCriteria, "every numbered acceptance point must include 验收标准");
        assertTrue(markdown.contains("## 15. 生产真实测试结论要求"));
    }

    @Test
    void shouldKeepFeishuAlertEvidenceCountAlignedWithSixAlertClasses() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("阶段失败、等待人工、provider 降级、QA 失败、交付复核失败、复核后 PR 发布失败至少覆盖六类告警。"));
        assertTrue(markdown.contains("- 六条 Feishu 消息链接或截图。"));
        assertTrue(markdown.contains("-Drd.multi-agent.smoke.feishu-alert-evidence-json=<feishu-alert-evidence-json>"));
        assertTrue(markdown.contains("Feishu 告警 JSON sidecar"));
        assertTrue(markdown.contains("feishu-alert-production-acceptance-*.json"));
        assertTrue(markdown.contains("feishuAlertMetadataComplete=true"));
        assertTrue(markdown.contains("缺少 RD-Bot 版本、生产环境标识或执行人时，Feishu 告警专项不能写出 `PASSED_FEISHU_ALERT_SMOKE`"));
        assertTrue(markdown.contains("role、stageRunId、failureCategory、nextAction、artifactUrl"));
        assertTrue(markdown.contains("top-level `messageIds` 必须覆盖每条 delivery 的 `messageId`"));
        assertTrue(markdown.contains("六类必需 delivery 的 `messageId` 必须互不重复"));
        assertTrue(markdown.contains("`failureCategory` 必须等于同一条 delivery 的 `alertType`"));
        assertTrue(markdown.contains("六类必需 delivery 的 `taskId` 必须完全一致"));
        assertTrue(markdown.contains("混合多个 taskId 的 Feishu 告警 sidecar 不能计入 #10"));
        assertTrue(markdown.contains("Feishu 告警 sidecar 的 `feishuAlertTaskId` 必须等于本次总验收主任务 `taskId`"));
        assertTrue(markdown.contains("其他任务的 Feishu 告警 sidecar 不能计入 #10"));
        assertTrue(markdown.contains("feishuAlertTaskId"));
    }

    @Test
    void shouldDocumentProviderFallbackEvidenceRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("providerFallbackEvidenceValidated=true"));
        assertTrue(markdown.contains("failedProvider"));
        assertTrue(markdown.contains("failedStatus"));
        assertTrue(markdown.contains("activeProvider"));
        assertTrue(markdown.contains("failedProvider` 与成功 attempt 的 `activeProvider` 都非空并且按大小写无关比较仍不相同"));
        assertTrue(markdown.contains("同 provider 重试不能计为多 provider 降级"));
        assertTrue(markdown.contains("只有大小写不同的 provider 名也不能计为多 provider 降级"));
        assertTrue(markdown.contains("PROVIDER_FALLBACK"));
        assertTrue(markdown.contains("PROVIDER_FALLBACK` 告警必须来自 `feishuAlertEvidenceValidated=true` 且 `feishuAlertMetadataComplete=true`"));
    }

    @Test
    void shouldDocumentSecretScanNeedlesRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("-Drd.multi-agent.smoke.secret-scan-needles"));
        assertTrue(markdown.contains("`secret-scan-needles` 按 CSV 解析并裁剪空白后，必须至少包含一个非空的真实生产敏感值"));
        assertTrue(markdown.contains("secretNeedleCount"));
        assertTrue(markdown.contains("secretScannedPullRequestMetadata=true"));
        assertTrue(markdown.contains("`executionResultJson.pullRequestPublication.metadataJson`"));
        assertTrue(markdown.contains("未配置任何 secret needle"));
    }

    @Test
    void shouldDocumentRequirementReviewBlockerEvidenceRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("-Drd.multi-agent.smoke.requirement-review-evidence-json=<requirement-review-evidence-json>"));
        assertTrue(markdown.contains("requirementReviewBlockerEvidenceValidated=true"));
        assertTrue(markdown.contains("role=REQUIREMENT_REVIEWER"));
        assertTrue(markdown.contains("taskStatus=FAILED_NEEDS_HUMAN"));
        assertTrue(markdown.contains("executionResultStatus=NEEDS_HUMAN"));
        assertTrue(markdown.contains("downstreamAgentsDispatched=false"));
        assertTrue(markdown.contains("feishuAlertType=STAGE_FAILED_NEEDS_HUMAN"));
        assertTrue(markdown.contains("需求评审阻断 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`"));
        assertTrue(markdown.contains("其他任务的需求评审阻断证据不能计入 #3"));
        assertTrue(markdown.contains("Requirement review artifact URI 必须是绝对 URI，且不能使用 `mock://`"));
        assertTrue(markdown.contains("Requirement review artifact URI 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`"));
        assertTrue(markdown.contains("requirement-review-blocker-production-acceptance-*.json"));
    }

    @Test
    void shouldDocumentDockerCodingEvidenceRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("-Drd.multi-agent.smoke.docker-coding-evidence-json=<docker-coding-evidence-json>"));
        assertTrue(markdown.contains("-Dtest=DockerCodingRealSmokeTest"));
        assertTrue(markdown.contains("-Drd.integration.docker-coding.enabled=true"));
        assertTrue(markdown.contains("-Drd.docker-coding.smoke.task-id=<same-main-task-id>"));
        assertTrue(markdown.contains("dockerCodingEvidenceValidated=true"));
        assertTrue(markdown.contains("repositoryUrl` 必须等于本次 `rd.multi-agent.smoke.repository-url"));
        assertTrue(markdown.contains("dockerCodingRepositoryUrl"));
        assertTrue(markdown.contains("realDockerRun=true"));
        assertTrue(markdown.contains("validationExitCode=0"));
        assertTrue(markdown.contains("testsRun>0"));
        assertTrue(markdown.contains("四类 artifact id 必须互不重复"));
        assertTrue(markdown.contains("四类 artifact URI 必须是绝对 URI，且不能使用 `mock://`"));
        assertTrue(markdown.contains("四类 artifact URI 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`"));
        assertTrue(markdown.contains("来自其他仓库的 Docker 编码证据不能计入 #6"));
        assertTrue(markdown.contains("Docker 编码 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`"));
        assertTrue(markdown.contains("其他任务的 Docker 编码证据不能计入 #6"));
        assertTrue(markdown.contains("repositoryUrl` 缺失或与本次验收仓库不一致"));
        assertTrue(markdown.contains("docker-coding-production-acceptance-*.json"));
    }

    @Test
    void shouldDocumentQaFailureBlockerEvidenceRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("-Drd.multi-agent.smoke.qa-failure-evidence-json=<qa-failure-evidence-json>"));
        assertTrue(markdown.contains("-Dtest=QaFailureBlockerRealSmokeTest"));
        assertTrue(markdown.contains("-Drd.integration.qa-failure.enabled=true"));
        assertTrue(markdown.contains("-Drd.qa-failure.smoke.task-id=<same-main-task-id>"));
        assertTrue(markdown.contains("qaFailureBlockerEvidenceValidated=true"));
        assertTrue(markdown.contains("role=QA_AGENT"));
        assertTrue(markdown.contains("qaStageStatus=FAILED_VALIDATION"));
        assertTrue(markdown.contains("failedAcceptanceCount>0"));
        assertTrue(markdown.contains("prCreated=false"));
        assertTrue(markdown.contains("successReportCreated=false"));
        assertTrue(markdown.contains("blockedBeforePrCreating=true"));
        assertTrue(markdown.contains("feishuAlertType=QA_FAILED"));
        assertTrue(markdown.contains("QA 失败阻断 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`"));
        assertTrue(markdown.contains("其他任务的 QA 失败阻断证据不能计入 #7"));
        assertTrue(markdown.contains("QA report artifact URI 和 validation log artifact URI 必须是绝对 URI，且不能使用 `mock://`"));
        assertTrue(markdown.contains("QA report artifact URI 和 validation log artifact URI 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`"));
        assertTrue(markdown.contains("qa-failure-blocker-production-acceptance-*.json"));
    }

    @Test
    void shouldDocumentDeliveryReviewFailureEvidenceRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("-Drd.multi-agent.smoke.delivery-review-failure-evidence-json=<delivery-review-failure-evidence-json>"));
        assertTrue(markdown.contains("-Dtest=DeliveryReviewFailureRealSmokeTest"));
        assertTrue(markdown.contains("-Drd.integration.delivery-review-failure.enabled=true"));
        assertTrue(markdown.contains("-Drd.delivery-review-failure.smoke.task-id=<same-main-task-id>"));
        assertTrue(markdown.contains("deliveryReviewFailureEvidenceValidated=true"));
        assertTrue(markdown.contains("deliveryReviewApproved=false"));
        assertTrue(markdown.contains("reviewDecision=REJECTED"));
        assertTrue(markdown.contains("pullRequestPublicationAttempted=false"));
        assertTrue(markdown.contains("prCreated=false"));
        assertTrue(markdown.contains("successReportCreated=false"));
        assertTrue(markdown.contains("failureReportCreated=true"));
        assertTrue(markdown.contains("successDeliveryReportExperienceCreated=false"));
        assertTrue(markdown.contains("feishuAlertType=DELIVERY_REVIEW_FAILED"));
        assertTrue(markdown.contains("交付复核失败 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`"));
        assertTrue(markdown.contains("其他任务的交付复核失败证据不能计入 #8"));
        assertTrue(markdown.contains("Delivery review artifact URI 必须是绝对 URI，且不能使用 `mock://`"));
        assertTrue(markdown.contains("Delivery review artifact URI 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`"));
        assertTrue(markdown.contains("delivery-review-failure-production-acceptance-*.json"));
    }

    @Test
    void shouldDocumentObservabilityMetricsEvidenceRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("-Drd.multi-agent.smoke.observability-metrics-evidence-json=<observability-metrics-evidence-json>"));
        assertTrue(markdown.contains("-Dtest=ObservabilityMetricsRealSmokeTest"));
        assertTrue(markdown.contains("-Drd.integration.observability-metrics.enabled=true"));
        assertTrue(markdown.contains("-Drd.observability-metrics.smoke.task-id=<same-main-task-id>"));
        assertTrue(markdown.contains("observabilityMetricsEvidenceValidated=true"));
        assertTrue(markdown.contains("metricsEndpointUrl` 必须等于本次 `rd.multi-agent.smoke.base-url` 派生的 `/actuator/prometheus`"));
        assertTrue(markdown.contains("metricsHttpStatus=200"));
        assertTrue(markdown.contains("contextBuildLatencyMetricPresent=true"));
        assertTrue(markdown.contains("repairSuccessRateMetricPresent=true"));
        assertTrue(markdown.contains("validationPassRateMetricPresent=true"));
        assertTrue(markdown.contains("prCreationRateMetricPresent=true"));
        assertTrue(markdown.contains("humanInterventionRateMetricPresent=true"));
        assertTrue(markdown.contains("retryRateMetricPresent=true"));
        assertTrue(markdown.contains("meanTimeToRepairMetricPresent=true"));
        assertTrue(markdown.contains("topFailureCategoriesMetricPresent=true"));
        assertTrue(markdown.contains("taskBoundAuditTraceLinkCount>=8"));
        assertTrue(markdown.contains("observabilityTaskId` 必须等于 sidecar 的 `taskId`"));
        assertTrue(markdown.contains("审计链必须绑定到同一 `taskId`"));
        assertTrue(markdown.contains("不能把其他任务的阶段事件、上下文、产物、经验或 PR trace 拼接计入 #14"));
        assertTrue(markdown.contains("observability-metrics-production-acceptance-*.json"));
    }

    @Test
    void shouldDocumentFeishuAlertArtifactUrlsMustBeProductionUris() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("每条告警包含 `taskId`、`role`、`stageRunId`、`failureCategory`、`nextAction` 和 `artifactUrl`"));
        assertTrue(markdown.contains("artifactUrl` 必须是绝对 URI，且不能使用 `mock://`"));
        assertTrue(markdown.contains("artifactUrl` 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`"));
        assertTrue(markdown.contains("相对路径或 mock artifactUrl 不能计入 #10"));
    }

    @Test
    void shouldDocumentFinalAcceptanceRequiresAllPriorProductionPointsPassed() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("#15 只有在 #1-#14 全部为 `PASSED` 时才允许标记为 `PASSED`"));
        assertTrue(markdown.contains("任一 #1-#14 验收点为 `FAILED` 或 `NOT_RUN` 时，#15 必须为 `FAILED` 或 `NOT_RUN`"));
        assertTrue(markdown.contains("Feishu、Skill 等专项 smoke 报告不能单独把 #15 标记为 `PASSED`"));
        assertTrue(markdown.contains("专项 smoke 失败报告只能把自身覆盖的验收点标记为 `FAILED`，未覆盖验收点必须保持 `NOT_RUN`"));
        assertTrue(markdown.contains("`FAILED_MINIMUM_SMOKE` 不能把未被完整证据证明的 #1-#14 自动标记为 `FAILED`"));
        assertTrue(markdown.contains("`FAILED_MINIMUM_SMOKE` 必须保留已经由独立真实证据证明的 `PASSED` 验收点"));
        assertTrue(markdown.contains("`PASSED_MINIMUM_SMOKE` 不等于完整 15 项生产验收通过"));
        assertTrue(markdown.contains("`PASSED_FULL_PRODUCTION_ACCEPTANCE`：#1-#15 全部为 `PASSED`"));
        assertTrue(markdown.contains("ProductionAcceptanceEvidenceLedgerSnapshotTest"));
        assertTrue(markdown.contains("production-acceptance-evidence-ledger-*.json"));
        assertTrue(markdown.contains("证据台账只能汇总既有 sidecar，不得把本机 Maven 回归计为生产验收通过"));
        assertTrue(markdown.contains("证据台账必须把最新总 smoke 的 `missingRequirements` 展开到每个 `NOT_RUN` 验收点"));
        assertTrue(markdown.contains("证据台账必须输出 `nextActions`"));
        assertTrue(markdown.contains("PROVIDER_AUTHENTICATION_FAILED"));
        assertTrue(markdown.contains("PROVIDER_QUOTA_OR_RATE_LIMIT"));
        assertTrue(markdown.contains("ProductionAcceptanceFinalGateSnapshotTest"));
        assertTrue(markdown.contains("rd.integration.final-acceptance-gate.enabled=true"));
        assertTrue(markdown.contains("最终完成声明前必须运行最终验收门禁"));
    }

    @Test
    void shouldDocumentWorkflowRecoveryEvidenceRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("-Drd.multi-agent.smoke.recovery-evidence-json=<workflow-recovery-evidence-json>"));
        assertTrue(markdown.contains("-Dtest=WorkflowRecoveryRealSmokeTest"));
        assertTrue(markdown.contains("-Drd.integration.workflow-recovery.enabled=true"));
        assertTrue(markdown.contains("-Drd.workflow.recovery.smoke.task-id=<same-main-task-id>"));
        assertTrue(markdown.contains("workflowRecoveryEvidenceValidated=true"));
        assertTrue(markdown.contains("duplicateSuccessfulStageCount=0"));
        assertTrue(markdown.contains("retryAttemptCount>0"));
        assertTrue(markdown.contains("retainedRetryArtifactCount>0"));
        assertTrue(markdown.contains("关键状态的首次出现顺序也必须满足该顺序"));
        assertTrue(markdown.contains("startupLogEvidenceUri` 和 `databaseSnapshotEvidenceUri` 必须是绝对 URI，且不能使用 `mock://`"));
        assertTrue(markdown.contains("startupLogEvidenceUri` 和 `databaseSnapshotEvidenceUri` 只能使用 `http://`、`https://`、`s3://` 或 `rd-artifact://`"));
        assertTrue(markdown.contains("恢复演练 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`"));
        assertTrue(markdown.contains("其他任务的恢复证据不能计入 #9"));
        assertTrue(markdown.contains("workflow-recovery-production-acceptance-*.json"));
    }

    @Test
    void shouldDocumentPullRequestMetadataRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("`pullRequestPublication.pullRequestUrl` 与任务最终 `pullRequestUrl` 一致"));
        assertTrue(markdown.contains("PR body 至少必须包含 `RD-Bot Delivery Review` 和 `RD-Bot QA Evidence` 段"));
        assertTrue(markdown.contains("PR metadata 至少包含 `taskId`、`taskType`、`targetBranch`、`workBranch`"));
        assertTrue(markdown.contains("`prBodyIncludesDeliveryReview`、`prBodyIncludesQaEvidence`"));
        assertTrue(markdown.contains("PR URL 必须使用 `http://` 或 `https://` scheme"));
        assertTrue(markdown.contains("host 等于本次 `rd.multi-agent.smoke.repository-url` 的 host"));
        assertTrue(markdown.contains("path 必须等于 `/repo-owner/repo-name/pull/{pullRequestNumber}`"));
        assertTrue(markdown.contains("缺少真实 PR 编号或 path 不是精确 PR URL 形态的证据不能计入 #8"));
        assertTrue(markdown.contains("GitHub PR 远端反查 sidecar"));
        assertTrue(markdown.contains("GitHub PR 创建专项 sidecar 的 `pullRequestUrl` 必须使用 `http://` 或 `https://` scheme"));
        assertTrue(markdown.contains("GitHub PR 创建专项 sidecar 的 `pullRequestUrl` host 必须为 `github.com`"));
        assertTrue(markdown.contains("GitHub PR 创建专项 sidecar 的 `pullRequestUrl` path 必须等于 `/repo-owner/repo-name/pull/{pullRequestNumber}`"));
        assertTrue(markdown.contains("`pullRequestUrl` 必须使用 `http://` 或 `https://` scheme"));
        assertTrue(markdown.contains("pullRequestBodyContainsExactTaskId=true"));
        assertTrue(markdown.contains("PR body 中的 `taskId` 必须以完整 token 出现，不能只靠前缀或子串匹配"));
        assertTrue(markdown.contains("多 Agent 总验收读取该 sidecar 时必须要求 `pullRequestBodyContainsExactTaskId=true`"));
        assertTrue(markdown.contains("PR body 反查 sidecar 的 `taskId` 必须等于本次总验收主任务 `taskId`，且该 `taskId` 必须以完整 token 出现在远端 PR body 中"));
        assertTrue(markdown.contains("GitHub PR 远端反查 sidecar 的 `pullRequestUrl` host 必须为 `github.com`"));
        assertTrue(markdown.contains("PR body 反查 sidecar 的 `pullRequestUrl` path 必须等于 `/repo-owner/repo-name/pull/{pullRequestNumber}`"));
        assertTrue(markdown.contains("GitHub PR 远端反查 sidecar 的 `workBranch` 必须精确等于 `requirement/{taskId}`"));
        assertTrue(markdown.contains("只包含 taskId 的其他分支名不能计入 #8/#13/#14"));
        assertTrue(markdown.contains("且必须等于本次成功路径最终 `pullRequestUrl`"));
        assertTrue(markdown.contains("`pullRequestBodyContainsArtifactLink=true` 必须来自 PR body 中带 artifact、log、evidence 或 report 语义的证据行"));
        assertTrue(markdown.contains("普通参考链接不能冒充产物链接"));
        assertTrue(markdown.contains("同 taskId 但不同 PR URL、不同 host、不同 path、不同 PR number、不同工作分支，或 PR body 只包含 taskId 前缀/子串的远端反查 sidecar 不能计入 #8/#13/#14"));
        assertTrue(markdown.contains("远端 PR body 缺少测试证据或带产物/日志/证据语义的生产产物链接"));
    }

    @Test
    void shouldDocumentRoleContextDistinctCountRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("roleContextDistinctCount>=4"));
        assertTrue(markdown.contains("最小生产 smoke 必须记录 `roleContextDistinctCount`"));
        assertTrue(markdown.contains("四个上下文 JSON 的 distinct count 必须为 4"));
        assertTrue(markdown.contains("只有 2-3 个 distinct 上下文不能计入 #2 通过"));
        assertTrue(markdown.contains("报告缺少 `roleContextDistinctCount`"));
    }

    @Test
    void shouldDocumentExperienceFollowUpTaskMustBeDifferentFromMainTask() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("经验类型必须覆盖 `REQUIREMENT_REVIEW`、`TECHNICAL_DESIGN`、`CODE_CHANGE`、`QA_REPORT` 和 `DELIVERY_REPORT`"));
        assertTrue(markdown.contains("experienceHashCount"));
        assertTrue(markdown.contains("experienceRedactedCount"));
        assertTrue(markdown.contains("experienceCompleteTypeCount"));
        assertTrue(markdown.contains("`experienceCompleteTypeCount` 必须逐类覆盖五类必需经验类型"));
        assertTrue(markdown.contains("后续相似需求的角色上下文必须出现 `rd-experience://{experienceId}` 经验证据"));
        assertTrue(markdown.contains("`followUpTaskId` 必须非空且不同于本次总验收主任务 `taskId`"));
        assertTrue(markdown.contains("不能复用主任务上下文自证经验可检索"));
        assertTrue(markdown.contains("`followUpTaskId` 为空或等于本次总验收主任务 `taskId`"));
    }

    @Test
    void shouldDocumentDetailedSolutionAndQaEvidenceCountsRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("solutionAffectedFileCount>0"));
        assertTrue(markdown.contains("solutionAcceptanceMappingCount>0"));
        assertTrue(markdown.contains("solutionTestPlanStepCount>0"));
        assertTrue(markdown.contains("qaPassedAcceptanceResultCount=qaAcceptanceResultCount"));
        assertTrue(markdown.contains("qaValidationCommandCount>=qaAcceptanceResultCount"));
        assertTrue(markdown.contains("qaValidationLogArtifactCount>=qaAcceptanceResultCount"));
        assertTrue(markdown.contains("方案只有 implementationSteps 但没有 affectedFiles、acceptanceMapping 或 testPlan，不能计入 #4 通过"));
        assertTrue(markdown.contains("QA 只有 acceptanceResults 数量但缺少真实命令、PASSED 状态或日志产物引用，不能计入 #7 通过"));
    }

    @Test
    void shouldDocumentAgentStagesMustNotReturnPullRequestUrlBeforeDeliveryReview() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("任一 Agent 阶段返回非空 `pullRequestUrl` 必须视为策略违例"));
        assertTrue(markdown.contains("`AGENT_PR_POLICY_VIOLATION`"));
        assertTrue(markdown.contains("PR 发布只能由 `RequirementPullRequestPublisherPort` 在复核通过后执行"));
        assertTrue(markdown.contains("`RequirementDeliveryReviewer` 必须拒绝任何 `multiAgentStages` 中包含阶段级 `pullRequestUrl` 的聚合结果"));
        assertTrue(markdown.contains("`stagePullRequestUrlRejected=true`"));
    }

    @Test
    void shouldDocumentSkillPolicyEvidenceRequiredForProductionAcceptance() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("-Drd.multi-agent.smoke.skill-policy-evidence-json=<skill-policy-evidence-json>"));
        assertTrue(markdown.contains("skillPolicyEvidenceValidated=true"));
        assertTrue(markdown.contains("unauthorizedRejected=true"));
        assertTrue(markdown.contains("highRiskWaitingApproval=true"));
        assertTrue(markdown.contains("allowedRole、rejectedRole、highRiskRole"));
        assertTrue(markdown.contains("非空且互不相同的 allowedRole、rejectedRole、highRiskRole"));
        assertTrue(markdown.contains("installPath` 必须是绝对路径并以 `skillId/skillVersion` 结尾"));
        assertTrue(markdown.contains("`sourceChecksum` 必须是 `sha256:` 加 64 位十六进制"));
        assertTrue(markdown.contains("`skillPolicyTaskId` 必须等于多 Agent 总验收主任务 `taskId`"));
        assertTrue(markdown.contains("短 checksum 或非 sha256 格式不能计入 #11"));
        assertTrue(markdown.contains("安装路径不是绝对路径，或与 skillId、skillVersion 不一致的 sidecar 不能计入 #11"));
        assertTrue(markdown.contains("其他任务的 Skill policy sidecar 不能计入 #11"));
        assertTrue(markdown.contains("复用同一个角色，无法证明三类策略边界"));
        assertTrue(markdown.contains("installerCallCount=1"));
        assertTrue(markdown.contains("skill-production-acceptance-*.json"));
    }

    @Test
    void shouldKeepExperienceTypeDocumentationAlignedWithImplementation() throws Exception {
        String technicalDesign = Files.readString(technicalDesignDocument());
        String productionAcceptance = Files.readString(productionAcceptanceDocument());

        for (String experienceType : List.of(
                "REQUIREMENT_REVIEW",
                "TECHNICAL_DESIGN",
                "CODE_CHANGE",
                "QA_REPORT",
                "DELIVERY_REPORT"
        )) {
            assertTrue(technicalDesign.contains("`" + experienceType + "`"));
            assertTrue(productionAcceptance.contains(experienceType));
        }
        assertTrue(!technicalDesign.contains("`LESSON_LEARNED`"));
        assertTrue(productionAcceptance.contains("experienceHashCount"));
        assertTrue(productionAcceptance.contains("experienceRedactedCount"));
        assertTrue(productionAcceptance.contains("experienceCompleteTypeCount"));
        assertTrue(productionAcceptance.contains("逐类覆盖五类必需经验类型"));
    }

    @Test
    void shouldDocumentRequiredProductionReportMetadataProperties() throws Exception {
        String markdown = Files.readString(productionAcceptanceDocument());

        assertTrue(markdown.contains("-Drd.multi-agent.smoke.rd-bot-version=<deployed-version>"));
        assertTrue(markdown.contains("-Drd.multi-agent.smoke.environment-id=<prod-or-prod-like-env-id>"));
        assertTrue(markdown.contains("-Drd.multi-agent.smoke.executed-by=<operator-or-ci-job>"));
        assertTrue(markdown.contains("-Drd.multi-agent.smoke.provider-preflight-evidence-json=<provider-preflight-evidence-json>"));
        assertTrue(markdown.contains("-Drd.feishu.alert.smoke.rd-bot-version=<deployed-version>"));
        assertTrue(markdown.contains("-Drd.feishu.alert.smoke.environment-id=<prod-or-prod-like-env-id>"));
        assertTrue(markdown.contains("-Drd.feishu.alert.smoke.executed-by=<operator-or-ci-job>"));
        assertTrue(markdown.contains("`rd-bot-version`、`environment-id`、`executed-by`"));
    }

    @Test
    void shouldKeepRealSmokeSourceCommandAlignedWithFullProductionSidecars() throws Exception {
        String source = Files.readString(realSmokeTestSource());

        for (String property : List.of(
                "-Drd.multi-agent.smoke.feishu-alert-evidence-json",
                "-Drd.multi-agent.smoke.provider-preflight-evidence-json",
                "-Drd.multi-agent.smoke.recovery-evidence-json",
                "-Drd.multi-agent.smoke.skill-policy-evidence-json",
                "-Drd.multi-agent.smoke.requirement-review-evidence-json",
                "-Drd.multi-agent.smoke.docker-coding-evidence-json",
                "-Drd.multi-agent.smoke.qa-failure-evidence-json",
                "-Drd.multi-agent.smoke.delivery-review-failure-evidence-json",
                "-Drd.multi-agent.smoke.github-pr-remote-evidence-json",
                "-Drd.multi-agent.smoke.observability-metrics-evidence-json"
        )) {
            assertTrue(source.contains(property), property);
        }
    }

    @Test
    void shouldKeepReviewBatchHandoffAlignedWithAllProductionSidecars() throws Exception {
        String markdown = Files.readString(reviewBatchDocument());

        assertTrue(markdown.contains("production smoke reports remain `SKIPPED`"));
        assertTrue(markdown.contains("Feishu alert"));
        assertTrue(markdown.contains("workflow recovery"));
        assertTrue(markdown.contains("Skill policy"));
        assertTrue(markdown.contains("requirement review blocker"));
        assertTrue(markdown.contains("Docker coding"));
        assertTrue(markdown.contains("QA failure blocker"));
        assertTrue(markdown.contains("delivery review failure"));
        assertTrue(markdown.contains("GitHub PR remote"));
        assertTrue(markdown.contains("observability metrics"));
    }

    private static Path productionAcceptanceDocument() {
        return repositoryRoot().resolve("docs/superpowers/plans/2026-07-01-multi-agent-rag-orchestration-production-acceptance.md");
    }

    private static Path technicalDesignDocument() {
        return repositoryRoot().resolve("docs/superpowers/plans/2026-07-01-multi-agent-rag-orchestration-technical-design.md");
    }

    private static Path realSmokeTestSource() {
        return repositoryRoot().resolve("bootstrap/src/test/java/com/wish/rd/bootstrap/MultiAgentRequirementDeliveryRealSmokeTest.java");
    }

    private static Path reviewBatchDocument() {
        return repositoryRoot().resolve("docs/superpowers/plans/2026-07-03-multi-agent-rag-orchestration-review-batches.md");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(".git"))) {
            current = current.getParent();
        }
        return current == null ? Path.of("").toAbsolutePath() : current;
    }
}
