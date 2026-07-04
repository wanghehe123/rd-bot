package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes auditable Markdown evidence for the property-gated multi-agent production smoke test.
 */
final class MultiAgentProductionAcceptanceReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final List<String> EXPECTED_ROLES = List.of(
            "REQUIREMENT_REVIEWER",
            "SOLUTION_ARCHITECT",
            "CODING_AGENT",
            "QA_AGENT"
    );
    private static final List<AcceptancePoint> ACCEPTANCE_POINTS = List.of(
            new AcceptancePoint(1, "需求任务可进入多 Agent 工作流"),
            new AcceptancePoint(2, "角色上下文包真实落库且内容不同"),
            new AcceptancePoint(3, "需求评审 Agent 能阻断不可交付需求"),
            new AcceptancePoint(4, "方案 Agent 产出可执行开发方案"),
            new AcceptancePoint(5, "多 provider 降级重试真实生效"),
            new AcceptancePoint(6, "编码 Agent 在 Docker 中真实改代码并运行测试"),
            new AcceptancePoint(7, "QA Agent 逐条验收并阻断失败交付"),
            new AcceptancePoint(8, "交付复核通过后才提交为已交付"),
            new AcceptancePoint(9, "状态机可恢复且不会重复派发"),
            new AcceptancePoint(10, "错误通知真实送达 Feishu"),
            new AcceptancePoint(11, "Skill 安装和使用受策略控制"),
            new AcceptancePoint(12, "经验自动沉淀且可被后续 RAG 检索"),
            new AcceptancePoint(13, "密钥和敏感信息不进入产物"),
            new AcceptancePoint(14, "指标和审计可观测"),
            new AcceptancePoint(15, "生产真实测试结论要求")
    );

    private final Path reportRoot;
    private final Clock clock;

    MultiAgentProductionAcceptanceReport(Path reportRoot, Clock clock) {
        this.reportRoot = reportRoot;
        this.clock = clock;
    }

    static MultiAgentProductionAcceptanceReport fromSystemProperties() {
        String reportDir = System.getProperty(
                "rd.multi-agent.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        );
        return new MultiAgentProductionAcceptanceReport(resolveReportRoot(reportDir), Clock.systemUTC());
    }

    static Path resolveReportRoot(String reportDir) {
        Path configured = Path.of(reportDir == null || reportDir.isBlank()
                ? "qa-runs/multi-agent-production-acceptance"
                : reportDir.strip());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return repositoryRoot().resolve(configured).normalize();
    }

    Path writeSkipped(List<String> missingRequirements) throws IOException {
        List<String> safeMissing = missingRequirements == null ? List.of() : List.copyOf(missingRequirements);
        StringBuilder markdown = header("SKIPPED");
        markdown.append("\n## 未运行原因\n\n")
                .append("真实生产验收参数不完整，本次没有执行 RD-Bot HTTP、PostgreSQL、Docker、provider、GitHub 或 Feishu 链路。\n")
                .append("缺少的条件只记录属性名，不记录任何 secret 值。\n\n");
        for (String missing : safeMissing) {
            markdown.append("- ").append(oneLine(missing)).append('\n');
        }
        appendSkippedRetryCommand(markdown);
        markdown.append("\n## 验收点矩阵\n\n");
        Map<Integer, MatrixStatus> matrix = notRunMatrix("缺少真实生产前置条件");
        appendMatrix(markdown, matrix);
        Path reportPath = write(markdown);
        writeSkippedJson(reportPath, safeMissing, matrix);
        return reportPath;
    }

    Path writePassedMinimumSmoke(MinimumSmokeEvidence evidence) throws IOException {
        requirePassedSmokeEvidence(evidence);
        Map<Integer, MatrixStatus> matrix = minimumSmokeMatrix(evidence);
        StringBuilder markdown = header(passedSmokeConclusion(matrix));
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        appendMatrix(markdown, matrix);
        Path reportPath = write(markdown);
        writeEvidenceJson(reportPath, passedSmokeConclusion(matrix), evidence, matrix, null);
        return reportPath;
    }

    Path writeFailedMinimumSmoke(MinimumSmokeEvidence evidence, Throwable failure) throws IOException {
        StringBuilder markdown = header("FAILED_MINIMUM_SMOKE");
        appendFailure(markdown, evidence, failure);
        appendEvidence(markdown, evidence);
        markdown.append("\n## 验收点矩阵\n\n");
        Map<Integer, MatrixStatus> matrix = failedMatrix(evidence);
        appendMatrix(markdown, matrix);
        Path reportPath = write(markdown);
        writeEvidenceJson(reportPath, "FAILED_MINIMUM_SMOKE", evidence, matrix, failure);
        return reportPath;
    }

    private StringBuilder header(String conclusion) {
        Instant now = clock.instant();
        return new StringBuilder()
                .append("# RD-Bot 多 Agent RAG 编排生产验收报告\n\n")
                .append("- 验收时间：").append(now).append('\n')
                .append("- 结论：").append(conclusion).append('\n')
                .append("- 说明：该报告区分最小生产 smoke 证据和完整 15 项生产验收；")
                .append("未真实运行的项目不得记为通过。\n");
    }

    private void appendSkippedRetryCommand(StringBuilder markdown) {
        markdown.append("""

                ## 下一步补验命令

                先在执行环境注入 provider 和 GitHub 凭证环境变量；命令模板只记录属性名和占位符，不记录 secret 值。

                ```bash
                PROVIDER_A_API_KEY=<secret> PROVIDER_B_API_KEY=<secret> \\
                ./mvnw -pl bootstrap -am -Dtest=MultiAgentRequirementDeliveryRealSmokeTest \\
                  -Drd.integration.multi-agent.enabled=true \\
                  -Drd.multi-agent.smoke.production-evidence=true \\
                  -Drd.multi-agent.smoke.rd-bot-version=<rd-bot-version> \\
                  -Drd.multi-agent.smoke.environment-id=<production-environment-id> \\
                  -Drd.multi-agent.smoke.executed-by=<operator> \\
                  -Drd.multi-agent.smoke.base-url=<rd-bot-base-url> \\
                  -Drd.multi-agent.smoke.postgres-url=<postgres-jdbc-url> \\
                  -Drd.multi-agent.smoke.postgres-user=<postgres-user> \\
                  -Drd.multi-agent.smoke.postgres-password=<postgres-password> \\
                  -Drd.multi-agent.smoke.repository-url=<repository-url> \\
                  -Drd.multi-agent.smoke.repo-owner=<repo-owner> \\
                  -Drd.multi-agent.smoke.repo-name=<repo-name> \\
                  -Drd.multi-agent.smoke.expected-provider-count=2 \\
                  -Drd.multi-agent.smoke.provider-secret-env-names=PROVIDER_A_API_KEY,PROVIDER_B_API_KEY \\
                  -Drd.multi-agent.smoke.github-code-platform-mode=real \\
                  -Drd.multi-agent.smoke.github-auth-mode=GH_CLI_LOCAL_SMOKE \\
                  -Drd.multi-agent.smoke.provider-preflight-evidence-json=<provider-preflight-evidence-json> \\
                  -Drd.multi-agent.smoke.feishu-alert-evidence-json=<feishu-alert-evidence-json> \\
                  -Drd.multi-agent.smoke.recovery-evidence-json=<workflow-recovery-evidence-json> \\
                  -Drd.multi-agent.smoke.skill-policy-evidence-json=<skill-policy-evidence-json> \\
                  -Drd.multi-agent.smoke.requirement-review-evidence-json=<requirement-review-evidence-json> \\
                  -Drd.multi-agent.smoke.docker-coding-evidence-json=<docker-coding-evidence-json> \\
                  -Drd.multi-agent.smoke.qa-failure-evidence-json=<qa-failure-evidence-json> \\
                  -Drd.multi-agent.smoke.delivery-review-failure-evidence-json=<delivery-review-failure-evidence-json> \\
                  -Drd.multi-agent.smoke.github-pr-remote-evidence-json=<github-pr-remote-evidence-json> \\
                  -Drd.multi-agent.smoke.observability-metrics-evidence-json=<observability-metrics-evidence-json> \\
                  -Drd.multi-agent.smoke.secret-scan-needles=<secret-scan-needles> \\
                  -Dsurefire.failIfNoSpecifiedTests=false test
                ```
                """);
    }

    private void appendEvidence(StringBuilder markdown, MinimumSmokeEvidence evidence) {
        markdown.append("\n## 最小生产 Smoke 证据\n\n");
        markdown.append("- RD-Bot 版本：").append(oneLine(evidence.rdBotVersion())).append('\n');
        markdown.append("- 生产环境标识：").append(oneLine(evidence.environmentId())).append('\n');
        markdown.append("- 执行人：").append(oneLine(evidence.executedBy())).append('\n');
        markdown.append("- taskId：").append(oneLine(evidence.taskId())).append('\n');
        markdown.append("- 任务状态：").append(oneLine(evidence.taskStatus())).append('\n');
        markdown.append("- PR：").append(oneLine(evidence.pullRequestUrl())).append('\n');
        markdown.append("- timelineEvents：").append(evidence.timelineEvents()).append('\n');
        markdown.append("- stageEvents：").append(evidence.stageEvents()).append('\n');
        markdown.append("- roleContextPackages：").append(evidence.roleContextPackageCount()).append('\n');
        markdown.append("- roleContextDifferent：").append(evidence.roleContextDifferent()).append('\n');
        markdown.append("- roleContextDistinctCount：").append(evidence.roleContextDistinctCount()).append('\n');
        markdown.append("- deliveryReviewApproved：").append(evidence.deliveryReviewApproved()).append('\n');
        markdown.append("- pullRequestPublicationSucceeded：")
                .append(evidence.pullRequestPublicationSucceeded())
                .append('\n');
        markdown.append("- pullRequestEvidenceValidated：")
                .append(evidence.pullRequestEvidenceValidated())
                .append('\n');
        markdown.append("- pullRequestTargetAllowed：")
                .append(evidence.pullRequestTargetAllowed())
                .append('\n');
        markdown.append("- pullRequestBodyEvidenceIncluded：")
                .append(evidence.pullRequestBodyEvidenceIncluded())
                .append('\n');
        markdown.append("- pullRequestQaAcceptanceResultCount：")
                .append(evidence.pullRequestQaAcceptanceResultCount())
                .append('\n');
        markdown.append("- deliveryReviewFailureEvidenceValidated：")
                .append(deliveryReviewFailureEvidenceValidated(evidence))
                .append('\n');
        DeliveryReviewFailureEvidence deliveryReviewFailure = evidence.deliveryReviewFailureEvidence();
        markdown.append("- deliveryReviewFailureTaskId：")
                .append(oneLine(deliveryReviewFailure.taskId()))
                .append('\n');
        markdown.append("- deliveryReviewFailureTaskStatus：")
                .append(oneLine(deliveryReviewFailure.taskStatus()))
                .append('\n');
        markdown.append("- deliveryReviewFailureApproved：")
                .append(deliveryReviewFailure.deliveryReviewApproved())
                .append('\n');
        markdown.append("- deliveryReviewFailureDecision：")
                .append(oneLine(deliveryReviewFailure.reviewDecision()))
                .append('\n');
        markdown.append("- deliveryReviewFailureReviewer：")
                .append(oneLine(deliveryReviewFailure.reviewer()))
                .append('\n');
        markdown.append("- deliveryReviewFailureReviewArtifactId：")
                .append(oneLine(deliveryReviewFailure.reviewArtifactId()))
                .append('\n');
        markdown.append("- deliveryReviewFailureReviewArtifactUri：")
                .append(oneLine(deliveryReviewFailure.reviewArtifactUri()))
                .append('\n');
        markdown.append("- deliveryReviewFailureRejectionReason：")
                .append(oneLine(deliveryReviewFailure.rejectionReason()))
                .append('\n');
        markdown.append("- deliveryReviewFailurePullRequestPublicationAttempted：")
                .append(deliveryReviewFailure.pullRequestPublicationAttempted())
                .append('\n');
        markdown.append("- deliveryReviewFailurePrCreated：")
                .append(deliveryReviewFailure.prCreated())
                .append('\n');
        markdown.append("- deliveryReviewFailureSuccessReportCreated：")
                .append(deliveryReviewFailure.successReportCreated())
                .append('\n');
        markdown.append("- deliveryReviewFailureFailureReportCreated：")
                .append(deliveryReviewFailure.failureReportCreated())
                .append('\n');
        markdown.append("- deliveryReviewFailureSuccessDeliveryReportExperienceCreated：")
                .append(deliveryReviewFailure.successDeliveryReportExperienceCreated())
                .append('\n');
        markdown.append("- deliveryReviewFailureBlockedBeforePrCreating：")
                .append(deliveryReviewFailure.blockedBeforePrCreating())
                .append('\n');
        markdown.append("- deliveryReviewFailureStagePullRequestUrlRejected：")
                .append(deliveryReviewFailure.stagePullRequestUrlRejected())
                .append('\n');
        markdown.append("- deliveryReviewFailureFeishuAlertDelivered：")
                .append(deliveryReviewFailure.feishuAlertDelivered())
                .append('\n');
        markdown.append("- deliveryReviewFailureFeishuAlertMessageId：")
                .append(oneLine(deliveryReviewFailure.feishuAlertMessageId()))
                .append('\n');
        markdown.append("- experienceTypes：").append(String.join(", ", evidence.experienceTypes())).append('\n');
        markdown.append("- experienceSourceLinkCount：").append(evidence.experienceSourceLinkCount()).append('\n');
        markdown.append("- experienceHashCount：").append(evidence.experienceHashCount()).append('\n');
        markdown.append("- experienceRedactedCount：").append(evidence.experienceRedactedCount()).append('\n');
        markdown.append("- experienceCompleteTypeCount：")
                .append(evidence.experienceCompleteTypeCount())
                .append('\n');
        markdown.append("- experienceRetrievalEvidenceValidated：")
                .append(evidence.experienceRetrievalEvidenceValidated())
                .append('\n');
        markdown.append("- followUpTaskId：").append(oneLine(evidence.followUpTaskId())).append('\n');
        markdown.append("- retrievedExperienceEvidenceCount：")
                .append(evidence.retrievedExperienceEvidenceCount())
                .append('\n');
        markdown.append("- solutionPlanEvidenceValidated：")
                .append(evidence.solutionPlanEvidenceValidated())
                .append('\n');
        markdown.append("- solutionImplementationStepCount：")
                .append(evidence.solutionImplementationStepCount())
                .append('\n');
        markdown.append("- solutionAffectedFileCount：")
                .append(evidence.solutionAffectedFileCount())
                .append('\n');
        markdown.append("- solutionAcceptanceMappingCount：")
                .append(evidence.solutionAcceptanceMappingCount())
                .append('\n');
        markdown.append("- solutionTestPlanStepCount：")
                .append(evidence.solutionTestPlanStepCount())
                .append('\n');
        markdown.append("- codingPromptReferencesSolutionPlan：")
                .append(evidence.codingPromptReferencesSolutionPlan())
                .append('\n');
        markdown.append("- dockerCodingEvidenceValidated：")
                .append(dockerCodingEvidenceValidated(evidence))
                .append('\n');
        DockerCodingEvidence dockerCoding = evidence.dockerCodingEvidence();
        markdown.append("- dockerCodingTaskId：").append(oneLine(dockerCoding.taskId())).append('\n');
        markdown.append("- dockerCodingStageRunId：").append(oneLine(dockerCoding.stageRunId())).append('\n');
        markdown.append("- dockerCodingRepositoryUrl：").append(oneLine(dockerCoding.repositoryUrl())).append('\n');
        markdown.append("- dockerCodingImage：").append(oneLine(dockerCoding.dockerImage())).append('\n');
        markdown.append("- dockerCodingContainerId：").append(oneLine(dockerCoding.containerId())).append('\n');
        markdown.append("- dockerCodingWorkspacePath：").append(oneLine(dockerCoding.workspacePath())).append('\n');
        markdown.append("- dockerCodingCommitHash：").append(oneLine(dockerCoding.commitHash())).append('\n');
        markdown.append("- dockerCodingPatchArtifactId：").append(oneLine(dockerCoding.patchArtifactId())).append('\n');
        markdown.append("- dockerCodingResultArtifactId：").append(oneLine(dockerCoding.resultArtifactId())).append('\n');
        markdown.append("- dockerCodingTestLogArtifactId：").append(oneLine(dockerCoding.testLogArtifactId())).append('\n');
        markdown.append("- dockerCodingDockerMetadataArtifactId：")
                .append(oneLine(dockerCoding.dockerMetadataArtifactId()))
                .append('\n');
        markdown.append("- dockerCodingPatchArtifactUri：").append(oneLine(dockerCoding.patchArtifactUri())).append('\n');
        markdown.append("- dockerCodingResultArtifactUri：").append(oneLine(dockerCoding.resultArtifactUri())).append('\n');
        markdown.append("- dockerCodingTestLogArtifactUri：").append(oneLine(dockerCoding.testLogArtifactUri())).append('\n');
        markdown.append("- dockerCodingDockerMetadataArtifactUri：")
                .append(oneLine(dockerCoding.dockerMetadataArtifactUri()))
                .append('\n');
        markdown.append("- dockerCodingChangedFileCount：").append(dockerCoding.changedFileCount()).append('\n');
        markdown.append("- dockerCodingValidationCommand：")
                .append(oneLine(dockerCoding.validationCommand()))
                .append('\n');
        markdown.append("- dockerCodingValidationExitCode：").append(dockerCoding.validationExitCode()).append('\n');
        markdown.append("- dockerCodingTestsRun：").append(dockerCoding.testsRun()).append('\n');
        markdown.append("- dockerCodingTestsFailed：").append(dockerCoding.testsFailed()).append('\n');
        markdown.append("- dockerCodingPatchNonEmpty：").append(dockerCoding.patchNonEmpty()).append('\n');
        markdown.append("- dockerCodingResultJsonValidated：").append(dockerCoding.resultJsonValidated()).append('\n');
        markdown.append("- dockerCodingRealDockerRun：").append(dockerCoding.realDockerRun()).append('\n');
        markdown.append("- qaReportEvidenceValidated：").append(evidence.qaReportEvidenceValidated()).append('\n');
        markdown.append("- qaAcceptanceResultCount：").append(evidence.qaAcceptanceResultCount()).append('\n');
        markdown.append("- qaPassedAcceptanceResultCount：")
                .append(evidence.qaPassedAcceptanceResultCount())
                .append('\n');
        markdown.append("- qaValidationCommandCount：")
                .append(evidence.qaValidationCommandCount())
                .append('\n');
        markdown.append("- qaValidationLogArtifactCount：")
                .append(evidence.qaValidationLogArtifactCount())
                .append('\n');
        markdown.append("- qaFailureBlockerEvidenceValidated：")
                .append(qaFailureBlockerEvidenceValidated(evidence))
                .append('\n');
        QaFailureBlockerEvidence qaFailureBlocker = evidence.qaFailureBlockerEvidence();
        markdown.append("- qaFailureBlockerTaskId：").append(oneLine(qaFailureBlocker.taskId())).append('\n');
        markdown.append("- qaFailureBlockerStageRunId：")
                .append(oneLine(qaFailureBlocker.stageRunId()))
                .append('\n');
        markdown.append("- qaFailureBlockerTaskStatus：")
                .append(oneLine(qaFailureBlocker.taskStatus()))
                .append('\n');
        markdown.append("- qaFailureBlockerQaStageStatus：")
                .append(oneLine(qaFailureBlocker.qaStageStatus()))
                .append('\n');
        markdown.append("- qaFailureBlockerExecutionResultStatus：")
                .append(oneLine(qaFailureBlocker.executionResultStatus()))
                .append('\n');
        markdown.append("- qaFailureBlockerQaReportArtifactId：")
                .append(oneLine(qaFailureBlocker.qaReportArtifactId()))
                .append('\n');
        markdown.append("- qaFailureBlockerQaReportArtifactUri：")
                .append(oneLine(qaFailureBlocker.qaReportArtifactUri()))
                .append('\n');
        markdown.append("- qaFailureBlockerValidationLogArtifactUris：")
                .append(String.join(", ", qaFailureBlocker.validationLogArtifactUris()))
                .append('\n');
        markdown.append("- qaFailureBlockerFailedAcceptanceCount：")
                .append(qaFailureBlocker.failedAcceptanceCount())
                .append('\n');
        markdown.append("- qaFailureBlockerAcceptanceResultCount：")
                .append(qaFailureBlocker.acceptanceResultCount())
                .append('\n');
        markdown.append("- qaFailureBlockerValidationCommandCount：")
                .append(qaFailureBlocker.validationCommandCount())
                .append('\n');
        markdown.append("- qaFailureBlockerValidationLogArtifactCount：")
                .append(qaFailureBlocker.validationLogArtifactCount())
                .append('\n');
        markdown.append("- qaFailureBlockerPrCreated：").append(qaFailureBlocker.prCreated()).append('\n');
        markdown.append("- qaFailureBlockerSuccessReportCreated：")
                .append(qaFailureBlocker.successReportCreated())
                .append('\n');
        markdown.append("- qaFailureBlockerBlockedBeforePrCreating：")
                .append(qaFailureBlocker.blockedBeforePrCreating())
                .append('\n');
        markdown.append("- qaFailureBlockerFeishuAlertDelivered：")
                .append(qaFailureBlocker.feishuAlertDelivered())
                .append('\n');
        markdown.append("- qaFailureBlockerFeishuAlertMessageId：")
                .append(oneLine(qaFailureBlocker.feishuAlertMessageId()))
                .append('\n');
        markdown.append("- expectedProviderCount：").append(evidence.expectedProviderCount()).append('\n');
        markdown.append("- providerSecretEnvCount：").append(evidence.providerSecretEnvCount()).append('\n');
        markdown.append("- githubCodePlatformMode：").append(oneLine(evidence.githubCodePlatformMode())).append('\n');
        markdown.append("- githubAuthMode：").append(oneLine(evidence.githubAuthMode())).append('\n');
        markdown.append("- githubCredentialEnvCount：").append(evidence.githubCredentialEnvCount()).append('\n');
        markdown.append("- secretScanEvidenceValidated：")
                .append(evidence.secretScanEvidenceValidated())
                .append('\n');
        markdown.append("- secretScannedValueCount：")
                .append(evidence.secretScannedValueCount())
                .append('\n');
        markdown.append("- secretScannedArtifactPreviewCount：")
                .append(evidence.secretScannedArtifactPreviewCount())
                .append('\n');
        markdown.append("- secretScannedPullRequestMetadata：")
                .append(evidence.secretScannedPullRequestMetadata())
                .append('\n');
        markdown.append("- githubPrRemoteEvidenceValidated：")
                .append(githubPrRemoteEvidenceValidated(evidence))
                .append('\n');
        GitHubPrRemoteEvidence githubPrRemoteEvidence = evidence.githubPrRemoteEvidence();
        markdown.append("- githubPrRemoteTaskId：")
                .append(oneLine(githubPrRemoteEvidence.taskId()))
                .append('\n');
        markdown.append("- githubPrRemotePullRequestUrl：")
                .append(oneLine(githubPrRemoteEvidence.pullRequestUrl()))
                .append('\n');
        markdown.append("- githubPrRemotePullRequestNumber：")
                .append(oneLine(githubPrRemoteEvidence.pullRequestNumber()))
                .append('\n');
        markdown.append("- githubPrRemoteBaseBranch：")
                .append(oneLine(githubPrRemoteEvidence.baseBranch()))
                .append('\n');
        markdown.append("- githubPrRemoteWorkBranch：")
                .append(oneLine(githubPrRemoteEvidence.workBranch()))
                .append('\n');
        markdown.append("- githubPrRemoteBodyIncludesDeliveryReview：")
                .append(githubPrRemoteEvidence.pullRequestBodyIncludesDeliveryReview())
                .append('\n');
        markdown.append("- githubPrRemoteBodyIncludesQaEvidence：")
                .append(githubPrRemoteEvidence.pullRequestBodyIncludesQaEvidence())
                .append('\n');
        markdown.append("- githubPrRemoteBodyContainsTaskId：")
                .append(githubPrRemoteEvidence.pullRequestBodyContainsTaskId())
                .append('\n');
        markdown.append("- githubPrRemoteBodyContainsArtifactLink：")
                .append(githubPrRemoteEvidence.pullRequestBodyContainsArtifactLink())
                .append('\n');
        markdown.append("- githubPrRemoteSecretScanEvidenceValidated：")
                .append(githubPrRemoteEvidence.secretScanEvidenceValidated())
                .append('\n');
        markdown.append("- githubPrRemoteSecretLeakFound：")
                .append(githubPrRemoteEvidence.secretLeakFound())
                .append('\n');
        markdown.append("- githubPrRemoteSecretScannedValueCount：")
                .append(githubPrRemoteEvidence.secretScannedValueCount())
                .append('\n');
        markdown.append("- secretNeedleCount：").append(evidence.secretNeedles().size()).append('\n');
        markdown.append("- auditChainEvidenceValidated：")
                .append(auditChainEvidenceValidated(evidence))
                .append('\n');
        markdown.append("- auditStageEventCount：").append(evidence.stageEvents()).append('\n');
        markdown.append("- auditRoleContextPackageCount：")
                .append(evidence.roleContextPackageCount())
                .append('\n');
        markdown.append("- auditArtifactLinkCount：").append(auditArtifactLinkCount(evidence)).append('\n');
        markdown.append("- auditExperienceLinkCount：").append(evidence.experienceSourceLinkCount()).append('\n');
        markdown.append("- auditPullRequestTraceValidated：")
                .append(auditPullRequestTraceValidated(evidence))
                .append('\n');
        markdown.append("- observabilityMetricsEvidenceValidated：")
                .append(observabilityMetricsEvidenceValidated(evidence))
                .append('\n');
        ObservabilityMetricsEvidence observabilityMetrics = evidence.observabilityMetricsEvidence();
        markdown.append("- observabilityMetricsTaskId：")
                .append(oneLine(observabilityMetrics.taskId()))
                .append('\n');
        markdown.append("- observabilityMetricsEndpointUrl：")
                .append(oneLine(observabilityMetrics.metricsEndpointUrl()))
                .append('\n');
        markdown.append("- observabilityMetricsHttpStatus：")
                .append(observabilityMetrics.metricsHttpStatus())
                .append('\n');
        markdown.append("- contextBuildLatencyMetricPresent：")
                .append(observabilityMetrics.contextBuildLatencyMetricPresent())
                .append('\n');
        markdown.append("- repairSuccessRateMetricPresent：")
                .append(observabilityMetrics.repairSuccessRateMetricPresent())
                .append('\n');
        markdown.append("- validationPassRateMetricPresent：")
                .append(observabilityMetrics.validationPassRateMetricPresent())
                .append('\n');
        markdown.append("- prCreationRateMetricPresent：")
                .append(observabilityMetrics.prCreationRateMetricPresent())
                .append('\n');
        markdown.append("- humanInterventionRateMetricPresent：")
                .append(observabilityMetrics.humanInterventionRateMetricPresent())
                .append('\n');
        markdown.append("- retryRateMetricPresent：")
                .append(observabilityMetrics.retryRateMetricPresent())
                .append('\n');
        markdown.append("- meanTimeToRepairMetricPresent：")
                .append(observabilityMetrics.meanTimeToRepairMetricPresent())
                .append('\n');
        markdown.append("- topFailureCategoriesMetricPresent：")
                .append(observabilityMetrics.topFailureCategoriesMetricPresent())
                .append('\n');
        markdown.append("- observabilityStageMetricCount：")
                .append(observabilityMetrics.stageMetricCount())
                .append('\n');
        markdown.append("- observabilityAuditTraceQuerySucceeded：")
                .append(observabilityMetrics.auditTraceQuerySucceeded())
                .append('\n');
        markdown.append("- observabilityRemotePrTraceValidated：")
                .append(observabilityMetrics.remotePrTraceValidated())
                .append('\n');
        markdown.append("- observabilityAuditTraceLinkCount：")
                .append(observabilityMetrics.auditTraceLinkCount())
                .append('\n');
        markdown.append("- observabilityTaskBoundAuditTraceLinkCount：")
                .append(observabilityMetrics.taskBoundAuditTraceLinkCount())
                .append('\n');
        markdown.append("- providerFallbackEvidenceValidated：")
                .append(providerFallbackEvidenceValidated(evidence))
                .append('\n');
        markdown.append("- providerFallbackSummary：")
                .append(providerFallbackSummary(evidence))
                .append('\n');
        markdown.append("- requirementReviewBlockerEvidenceValidated：")
                .append(requirementReviewBlockerEvidenceValidated(evidence))
                .append('\n');
        RequirementReviewBlockerEvidence blocker = evidence.requirementReviewBlockerEvidence();
        markdown.append("- requirementReviewBlockerTaskId：").append(oneLine(blocker.taskId())).append('\n');
        markdown.append("- requirementReviewBlockerStageRunId：")
                .append(oneLine(blocker.stageRunId()))
                .append('\n');
        markdown.append("- requirementReviewBlockerTaskStatus：")
                .append(oneLine(blocker.taskStatus()))
                .append('\n');
        markdown.append("- requirementReviewBlockerExecutionResultStatus：")
                .append(oneLine(blocker.executionResultStatus()))
                .append('\n');
        markdown.append("- requirementReviewBlockerDecision：")
                .append(oneLine(blocker.reviewDecision()))
                .append('\n');
        markdown.append("- requirementReviewBlockerReviewArtifactId：")
                .append(oneLine(blocker.reviewArtifactId()))
                .append('\n');
        markdown.append("- requirementReviewBlockerReviewArtifactUri：")
                .append(oneLine(blocker.reviewArtifactUri()))
                .append('\n');
        markdown.append("- requirementReviewBlockerErrorMessage：")
                .append(oneLine(blocker.errorMessage()))
                .append('\n');
        markdown.append("- requirementReviewBlockerMissingInformation：")
                .append(String.join(", ", blocker.missingInformation()))
                .append('\n');
        markdown.append("- requirementReviewBlockerPendingDownstreamRoleCount：")
                .append(blocker.pendingDownstreamRoleCount())
                .append('\n');
        markdown.append("- requirementReviewBlockerDownstreamAgentsDispatched：")
                .append(blocker.downstreamAgentsDispatched())
                .append('\n');
        markdown.append("- requirementReviewBlockerFeishuAlertDelivered：")
                .append(blocker.feishuAlertDelivered())
                .append('\n');
        markdown.append("- requirementReviewBlockerFeishuAlertMessageId：")
                .append(oneLine(blocker.feishuAlertMessageId()))
                .append('\n');
        markdown.append("- feishuAlertEvidenceValidated：")
                .append(feishuAlertEvidenceValidated(evidence))
                .append('\n');
        markdown.append("- feishuAlertMessageCount：").append(evidence.feishuAlertMessageCount()).append('\n');
        markdown.append("- feishuAlertTaskId：").append(oneLine(evidence.feishuAlertTaskId())).append('\n');
        markdown.append("- feishuAlertMetadataComplete：")
                .append(evidence.feishuAlertMetadataComplete())
                .append('\n');
        markdown.append("- feishuAlertTypes：").append(String.join(", ", evidence.feishuAlertTypes())).append('\n');
        markdown.append("- workflowRecoveryEvidenceValidated：")
                .append(workflowRecoveryEvidenceValidated(evidence))
                .append('\n');
        WorkflowRecoveryEvidence recovery = evidence.workflowRecoveryEvidence();
        markdown.append("- recoveryTaskId：").append(oneLine(recovery.taskId())).append('\n');
        markdown.append("- recoveryStageRunCountBeforeRestart：")
                .append(recovery.stageRunCountBeforeRestart())
                .append('\n');
        markdown.append("- recoveryStageRunCountAfterRestart：")
                .append(recovery.stageRunCountAfterRestart())
                .append('\n');
        markdown.append("- recoveryStageEventCountBeforeRestart：")
                .append(recovery.stageEventCountBeforeRestart())
                .append('\n');
        markdown.append("- recoveryStageEventCountAfterRestart：")
                .append(recovery.stageEventCountAfterRestart())
                .append('\n');
        markdown.append("- duplicateSuccessfulStageCount：")
                .append(recovery.duplicateSuccessfulStageCount())
                .append('\n');
        markdown.append("- retryAttemptCount：").append(recovery.retryAttemptCount()).append('\n');
        markdown.append("- retainedRetryArtifactCount：")
                .append(recovery.retainedRetryArtifactCount())
                .append('\n');
        markdown.append("- deliveryReviewApprovedBeforePrCreating：")
                .append(recovery.deliveryReviewApprovedBeforePrCreating())
                .append('\n');
        markdown.append("- recoveryTimelineStatuses：")
                .append(String.join(" -> ", recovery.timelineStatuses()))
                .append('\n');
        markdown.append("- recoveryStartupLogEvidenceUri：")
                .append(oneLine(recovery.startupLogEvidenceUri()))
                .append('\n');
        markdown.append("- recoveryDatabaseSnapshotEvidenceUri：")
                .append(oneLine(recovery.databaseSnapshotEvidenceUri()))
                .append('\n');
        markdown.append("- skillPolicyEvidenceValidated：")
                .append(skillPolicyEvidenceValidated(evidence))
                .append('\n');
        SkillPolicyEvidence skillPolicy = evidence.skillPolicyEvidence();
        markdown.append("- skillPolicyTaskId：").append(oneLine(skillPolicy.taskId())).append('\n');
        markdown.append("- skillPolicyStageRunId：").append(oneLine(skillPolicy.stageRunId())).append('\n');
        markdown.append("- skillPolicySkillId：").append(oneLine(skillPolicy.skillId())).append('\n');
        markdown.append("- skillPolicySkillVersion：").append(oneLine(skillPolicy.skillVersion())).append('\n');
        markdown.append("- skillPolicyAllowedRole：").append(oneLine(skillPolicy.allowedRole())).append('\n');
        markdown.append("- skillPolicyRejectedRole：").append(oneLine(skillPolicy.rejectedRole())).append('\n');
        markdown.append("- skillPolicyHighRiskRole：").append(oneLine(skillPolicy.highRiskRole())).append('\n');
        markdown.append("- skillPolicyInstallPath：").append(oneLine(skillPolicy.installPath())).append('\n');
        markdown.append("- skillPolicySourceChecksum：").append(oneLine(skillPolicy.sourceChecksum())).append('\n');
        markdown.append("- skillPolicyInstalled：").append(skillPolicy.installed()).append('\n');
        markdown.append("- skillPolicyUnauthorizedRejected：")
                .append(skillPolicy.unauthorizedRejected())
                .append('\n');
        markdown.append("- skillPolicyHighRiskWaitingApproval：")
                .append(skillPolicy.highRiskWaitingApproval())
                .append('\n');
        markdown.append("- skillPolicyMetadataValidated：")
                .append(skillPolicy.metadataValidated())
                .append('\n');
        markdown.append("- skillPolicyInstallerCallCount：")
                .append(skillPolicy.installerCallCount())
                .append('\n');
        markdown.append("\n### Stage Evidence\n\n");
        markdown.append("| role | stageRunId | promptArtifactId | resultArtifactId | stageArtifacts | stageArtifactPreviews | status | provider | providerAttemptsRecorded | providerAttemptCount | providerFallback | providerFallbackDetail | contextPackageRecorded |\n");
        markdown.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (String role : EXPECTED_ROLES) {
            StageEvidence stage = evidence.stages().get(role);
            if (stage == null) {
                markdown.append("| ")
                        .append(role)
                        .append(" |  |  |  | 0 | 0 | MISSING |  | false | providerAttemptCount=0 | false |  | false |\n");
            } else {
                markdown.append("| ").append(role)
                        .append(" | ").append(oneLine(stage.stageRunId()))
                        .append(" | ").append(oneLine(stage.promptArtifactId()))
                        .append(" | ").append(oneLine(stage.resultArtifactId()))
                        .append(" | stageArtifactCount=").append(stage.stageArtifactCount())
                        .append(" | stageArtifactPreviewCount=").append(stage.stageArtifactPreviewCount())
                        .append(" | ").append(oneLine(stage.status()))
                        .append(" | ").append(oneLine(stage.providerName()))
                        .append(" | providerAttemptsRecorded=").append(stage.providerAttemptsRecorded())
                        .append(" | providerAttemptCount=").append(stage.providerAttemptCount())
                        .append(" | providerFallbackDetected=").append(stage.providerFallbackDetected())
                        .append(" | ").append(providerFallbackDetail(stage))
                        .append(" | contextPackageRecorded=").append(stage.contextPackageRecorded())
                        .append(" |\n");
            }
        }
    }

    private void appendFailure(StringBuilder markdown, MinimumSmokeEvidence evidence, Throwable failure) {
        String failureType = failure == null ? "" : failure.getClass().getSimpleName();
        String failureMessage = failure == null ? "" : redact(oneLine(failure.getMessage()), evidence.secretNeedles());
        markdown.append("\n## 失败信息\n\n");
        markdown.append("- errorType：").append(failureType).append('\n');
        markdown.append("- message：").append(failureMessage).append('\n');
        markdown.append("- 说明：最小生产 smoke 已失败，完整 15 项生产验收不能据此判定通过。\n");
    }

    private void requirePassedSmokeEvidence(MinimumSmokeEvidence evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException("evidence must not be null for PASSED report");
        }
        requireEvidenceText(evidence.taskId(), "taskId");
        requireEvidenceText(evidence.pullRequestUrl(), "pullRequestUrl");
        requireEquals("COMPLETED", evidence.taskStatus(), "taskStatus");
        if (evidence.timelineEvents() <= 0) {
            throw new IllegalArgumentException("timelineEvents must be greater than 0 for PASSED report");
        }
        if (evidence.stageEvents() <= 0) {
            throw new IllegalArgumentException("stageEvents must be greater than 0 for PASSED report");
        }
        if (evidence.roleContextPackageCount() < EXPECTED_ROLES.size()) {
            throw new IllegalArgumentException("roleContextPackages must include every expected role for PASSED report");
        }
        if (!evidence.roleContextDifferent()) {
            throw new IllegalArgumentException("roleContextPackages must be role-specific for PASSED report");
        }
        if (evidence.roleContextDistinctCount() < EXPECTED_ROLES.size()) {
            throw new IllegalArgumentException(
                    "roleContextDistinctCount must cover every expected role for PASSED report"
            );
        }
        if (!evidence.deliveryReviewApproved()) {
            throw new IllegalArgumentException("deliveryReviewApproved must be true for PASSED report");
        }
        if (!evidence.pullRequestPublicationSucceeded()) {
            throw new IllegalArgumentException("pullRequestPublicationSucceeded must be true for PASSED report");
        }
        if (!evidence.pullRequestEvidenceValidated()) {
            throw new IllegalArgumentException("pullRequestEvidenceValidated must be true for PASSED report");
        }
        if (!evidence.pullRequestTargetAllowed()) {
            throw new IllegalArgumentException("pullRequestTargetAllowed must be true for PASSED report");
        }
        if (!evidence.pullRequestBodyEvidenceIncluded()) {
            throw new IllegalArgumentException("pullRequestBodyEvidenceIncluded must be true for PASSED report");
        }
        if (evidence.pullRequestQaAcceptanceResultCount() <= 0) {
            throw new IllegalArgumentException(
                    "pullRequestQaAcceptanceResultCount must be greater than 0 for PASSED report"
            );
        }
        if (evidence.experienceSourceLinkCount() < requiredExperienceTypeCount()) {
            throw new IllegalArgumentException(
                    "experienceSourceLinkCount must cover every required experience type for PASSED report"
            );
        }
        if (evidence.experienceHashCount() < requiredExperienceTypeCount()) {
            throw new IllegalArgumentException(
                    "experienceHashCount must cover every required experience type for PASSED report"
            );
        }
        if (evidence.experienceRedactedCount() < requiredExperienceTypeCount()) {
            throw new IllegalArgumentException(
                    "experienceRedactedCount must cover every required experience type for PASSED report"
            );
        }
        if (evidence.experienceCompleteTypeCount() < requiredExperienceTypeCount()) {
            throw new IllegalArgumentException(
                    "experienceCompleteTypeCount must cover every required experience type for PASSED report"
            );
        }
        if (!evidence.experienceRetrievalEvidenceValidated()) {
            throw new IllegalArgumentException(
                    "experienceRetrievalEvidenceValidated must be true for PASSED report"
            );
        }
        requireEvidenceText(evidence.followUpTaskId(), "followUpTaskId");
        if (evidence.taskId().equals(evidence.followUpTaskId())) {
            throw new IllegalArgumentException("followUpTaskId must be different from taskId for PASSED report");
        }
        if (evidence.retrievedExperienceEvidenceCount() <= 0) {
            throw new IllegalArgumentException(
                    "retrievedExperienceEvidenceCount must be greater than 0 for PASSED report"
            );
        }
        if (!evidence.solutionPlanEvidenceValidated()) {
            throw new IllegalArgumentException("solutionPlanEvidenceValidated must be true for PASSED report");
        }
        if (evidence.solutionImplementationStepCount() <= 0) {
            throw new IllegalArgumentException(
                    "solutionImplementationStepCount must be greater than 0 for PASSED report"
            );
        }
        if (evidence.solutionAffectedFileCount() <= 0) {
            throw new IllegalArgumentException(
                    "solutionAffectedFileCount must be greater than 0 for PASSED report"
            );
        }
        if (evidence.solutionAcceptanceMappingCount() <= 0) {
            throw new IllegalArgumentException(
                    "solutionAcceptanceMappingCount must be greater than 0 for PASSED report"
            );
        }
        if (evidence.solutionTestPlanStepCount() <= 0) {
            throw new IllegalArgumentException(
                    "solutionTestPlanStepCount must be greater than 0 for PASSED report"
            );
        }
        if (!evidence.codingPromptReferencesSolutionPlan()) {
            throw new IllegalArgumentException("codingPromptReferencesSolutionPlan must be true for PASSED report");
        }
        if (!evidence.qaReportEvidenceValidated()) {
            throw new IllegalArgumentException("qaReportEvidenceValidated must be true for PASSED report");
        }
        if (evidence.qaAcceptanceResultCount() <= 0) {
            throw new IllegalArgumentException("qaAcceptanceResultCount must be greater than 0 for PASSED report");
        }
        if (evidence.qaPassedAcceptanceResultCount() != evidence.qaAcceptanceResultCount()) {
            throw new IllegalArgumentException(
                    "qaPassedAcceptanceResultCount must match qaAcceptanceResultCount for PASSED report"
            );
        }
        if (evidence.qaValidationCommandCount() < evidence.qaAcceptanceResultCount()) {
            throw new IllegalArgumentException(
                    "qaValidationCommandCount must cover every QA acceptance result for PASSED report"
            );
        }
        if (evidence.qaValidationLogArtifactCount() < evidence.qaAcceptanceResultCount()) {
            throw new IllegalArgumentException(
                    "qaValidationLogArtifactCount must cover every QA acceptance result for PASSED report"
            );
        }
        if (evidence.expectedProviderCount() < 2) {
            throw new IllegalArgumentException("expectedProviderCount must be at least 2 for PASSED report");
        }
        if (evidence.providerSecretEnvCount() < evidence.expectedProviderCount()) {
            throw new IllegalArgumentException(
                    "providerSecretEnvCount must cover every expected provider for PASSED report"
            );
        }
        requireEquals("real", evidence.githubCodePlatformMode(), "githubCodePlatformMode");
        requireEvidenceText(evidence.githubAuthMode(), "githubAuthMode");
        if (githubCredentialEnvironmentRequired(evidence.githubAuthMode()) && evidence.githubCredentialEnvCount() <= 0) {
            throw new IllegalArgumentException("githubCredentialEnvCount must be greater than 0 for PASSED report");
        }
        if (!evidence.secretScanEvidenceValidated()) {
            throw new IllegalArgumentException("secretScanEvidenceValidated must be true for PASSED report");
        }
        if (evidence.secretNeedles().isEmpty()) {
            throw new IllegalArgumentException("secretNeedles must include at least one configured production value");
        }
        if (evidence.secretScannedValueCount() <= 0) {
            throw new IllegalArgumentException("secretScannedValueCount must be greater than 0 for PASSED report");
        }
        if (evidence.secretScannedArtifactPreviewCount() < EXPECTED_ROLES.size() * 2) {
            throw new IllegalArgumentException(
                    "secretScannedArtifactPreviewCount must cover prompt and result artifacts for every role"
            );
        }
        if (!evidence.secretScannedPullRequestMetadata()) {
            throw new IllegalArgumentException(
                    "secretScannedPullRequestMetadata must be true for PASSED report"
            );
        }
        requireExperienceTypes(evidence);
        for (String role : EXPECTED_ROLES) {
            requireStageEvidence(evidence, role);
        }
        if (!auditChainEvidenceValidated(evidence)) {
            throw new IllegalArgumentException(
                    "auditChainEvidence must include timeline, stage events, role contexts, artifacts, experience links and PR trace"
            );
        }
    }

    private void requireStageEvidence(MinimumSmokeEvidence evidence, String role) {
        StageEvidence stage = evidence.stages().get(role);
        if (stage == null) {
            throw new IllegalArgumentException(role + " stage must exist for PASSED report");
        }
        requireEvidenceText(stage.stageRunId(), role + " stageRunId");
        requireEvidenceText(stage.promptArtifactId(), role + " promptArtifactId");
        requireEvidenceText(stage.resultArtifactId(), role + " resultArtifactId");
        if (stage.stageArtifactCount() < 2) {
            throw new IllegalArgumentException(role + " stageArtifacts must include prompt and result for PASSED report");
        }
        if (stage.stageArtifactPreviewCount() < 2) {
            throw new IllegalArgumentException(
                    role + " stageArtifactPreviews must include prompt and result content previews for PASSED report"
            );
        }
        requireEquals("SUCCEEDED", stage.status(), role + " stage status");
        requireEvidenceText(stage.providerName(), role + " providerName");
        if (!stage.providerAttemptsRecorded()) {
            throw new IllegalArgumentException(role + " providerAttemptsRecorded must be true for PASSED report");
        }
        if (stage.providerAttemptCount() <= 0) {
            throw new IllegalArgumentException(
                    role + " providerAttemptCount must include at least one real provider attempt for PASSED report"
            );
        }
        if (!stage.contextPackageRecorded()) {
            throw new IllegalArgumentException(role + " contextPackageRecorded must be true for PASSED report");
        }
    }

    private static boolean githubCredentialEnvironmentRequired(String githubAuthMode) {
        return !"GH_CLI_LOCAL_SMOKE".equalsIgnoreCase(oneLine(githubAuthMode));
    }

    private void requireExperienceTypes(MinimumSmokeEvidence evidence) {
        if (hasRequiredExperienceTypes(evidence)) {
            return;
        }
        List<String> expectedExperienceTypes = List.of(
                "REQUIREMENT_REVIEW",
                "TECHNICAL_DESIGN",
                "CODE_CHANGE",
                "QA_REPORT",
                "DELIVERY_REPORT"
        );
        for (String expectedType : expectedExperienceTypes) {
            if (!evidence.experienceTypes().contains(expectedType)) {
                throw new IllegalArgumentException(
                        "experienceTypes must include " + expectedType + " for PASSED report"
                );
            }
        }
    }

    private boolean hasRequiredExperienceTypes(MinimumSmokeEvidence evidence) {
        return evidence.experienceTypes().containsAll(List.of(
                "REQUIREMENT_REVIEW",
                "TECHNICAL_DESIGN",
                "CODE_CHANGE",
                "QA_REPORT",
                "DELIVERY_REPORT"
        ));
    }

    private Map<Integer, MatrixStatus> minimumSmokeMatrix(MinimumSmokeEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = new LinkedHashMap<>();
        String evidenceRef = "taskId=" + oneLine(evidence.taskId())
                + ", PR=" + oneLine(evidence.pullRequestUrl())
                + ", stageEvents=" + evidence.stageEvents();
        statuses.put(1, new MatrixStatus("PASSED",
                evidenceRef + "；真实 HTTP 创建任务并查询到四角色阶段及阶段事件。"));
        statuses.put(2, new MatrixStatus("PASSED",
                evidenceRef + "；真实 PostgreSQL 查询到四个角色上下文包且 roleContextDistinctCount="
                        + evidence.roleContextDistinctCount()
                        + "。"));
        if (requirementReviewBlockerEvidenceValidated(evidence)) {
            RequirementReviewBlockerEvidence blocker = evidence.requirementReviewBlockerEvidence();
            statuses.put(3, new MatrixStatus("PASSED",
                    "requirementReviewTaskId=" + oneLine(blocker.taskId())
                            + "；真实缺信息需求已由 REQUIREMENT_REVIEWER 阻断，taskStatus="
                            + oneLine(blocker.taskStatus())
                            + "，decision="
                            + oneLine(blocker.reviewDecision())
                            + "，pendingDownstreamRoleCount="
                            + blocker.pendingDownstreamRoleCount()
                            + "，feishuAlertMessageId="
                            + oneLine(blocker.feishuAlertMessageId())
                            + "。"));
        } else {
            statuses.put(3, new MatrixStatus("NOT_RUN",
                    "需要提供同版本、同环境、同执行人的 requirement review blocker JSON sidecar，证明缺信息需求由评审 Agent 阻断。"));
        }
        statuses.put(4, new MatrixStatus("PASSED",
                evidenceRef + "；方案结果产物已通过协议校验，implementationSteps="
                        + evidence.solutionImplementationStepCount()
                        + "，affectedFiles="
                        + evidence.solutionAffectedFileCount()
                        + "，acceptanceMapping="
                        + evidence.solutionAcceptanceMappingCount()
                        + "，testPlan="
                        + evidence.solutionTestPlanStepCount()
                        + "，编码 prompt 已引用方案上游结果。"));
        if (providerFallbackEvidenceValidated(evidence)) {
            statuses.put(5, new MatrixStatus("PASSED",
                    evidenceRef + "；provider fallback 已由真实 providerAttemptsJson 和 Feishu PROVIDER_FALLBACK 告警共同证明，"
                            + providerFallbackSummary(evidence) + "。"));
        } else {
            statuses.put(5, new MatrixStatus("NOT_RUN",
                    "已记录 providerAttemptsJson；还需受控演练第一 provider 失败后的真实降级，"
                            + "以及 feishuAlertEvidenceValidated=true、feishuAlertMetadataComplete=true 的 Feishu 告警 sidecar。"));
        }
        if (dockerCodingEvidenceValidated(evidence)) {
            DockerCodingEvidence dockerCoding = evidence.dockerCodingEvidence();
            statuses.put(6, new MatrixStatus("PASSED",
                    "dockerCodingTaskId=" + oneLine(dockerCoding.taskId())
                            + "；真实 CODING_AGENT Docker 执行证据已校验，image="
                            + oneLine(dockerCoding.dockerImage())
                            + "，changedFileCount="
                            + dockerCoding.changedFileCount()
                            + "，validationCommand="
                            + oneLine(dockerCoding.validationCommand())
                            + "，testsRun="
                            + dockerCoding.testsRun()
                            + "。"));
        } else {
            statuses.put(6, new MatrixStatus("NOT_RUN",
                    "需要提供同版本、同环境、同执行人的 Docker coding JSON sidecar，证明 CODING_AGENT 在 Docker 中真实改代码并运行测试。"));
        }
        if (qaDeliveryEvidenceValidated(evidence) && qaFailureBlockerEvidenceValidated(evidence)) {
            QaFailureBlockerEvidence qaFailureBlocker = evidence.qaFailureBlockerEvidence();
            statuses.put(7, new MatrixStatus("PASSED",
                    "taskId=" + oneLine(evidence.taskId())
                            + "；成功路径 QA report 已逐条绑定验收标准、真实命令和日志产物，qaAcceptanceResultCount="
                            + evidence.qaAcceptanceResultCount()
                            + "，qaValidationCommandCount="
                            + evidence.qaValidationCommandCount()
                            + "，qaValidationLogArtifactCount="
                            + evidence.qaValidationLogArtifactCount()
                            + "；qaFailureBlockerTaskId=" + oneLine(qaFailureBlocker.taskId())
                            + "；真实 QA 失败需求已阻断交付，qaStageStatus="
                            + oneLine(qaFailureBlocker.qaStageStatus())
                            + "，failedAcceptanceCount="
                            + qaFailureBlocker.failedAcceptanceCount()
                            + "，prCreated="
                            + qaFailureBlocker.prCreated()
                            + "，successReportCreated="
                            + qaFailureBlocker.successReportCreated()
                            + "，feishuAlertMessageId="
                            + oneLine(qaFailureBlocker.feishuAlertMessageId())
                            + "。"));
        } else {
            statuses.put(7, new MatrixStatus("NOT_RUN",
                    "需要成功路径 QA report 逐条绑定 PASSED、真实命令和日志产物，并提供同版本、同环境、同执行人的 QA failure blocker JSON sidecar，证明 QA 失败会阻断 PR、成功报告和完成态。"));
        }
        if (deliveryReviewFailureEvidenceValidated(evidence) && githubPrRemoteEvidenceValidated(evidence)) {
            DeliveryReviewFailureEvidence deliveryReviewFailure = evidence.deliveryReviewFailureEvidence();
            GitHubPrRemoteEvidence githubPrRemoteEvidence = evidence.githubPrRemoteEvidence();
            statuses.put(8, new MatrixStatus("PASSED",
                    evidenceRef + "；成功路径 PR 发布 metadata 已验证，且真实复核失败演练已阻断交付，deliveryReviewFailureTaskId="
                            + oneLine(deliveryReviewFailure.taskId())
                            + "，reviewDecision="
                            + oneLine(deliveryReviewFailure.reviewDecision())
                            + "，pullRequestPublicationAttempted="
                            + deliveryReviewFailure.pullRequestPublicationAttempted()
                            + "，prCreated="
                            + deliveryReviewFailure.prCreated()
                            + "，successReportCreated="
                            + deliveryReviewFailure.successReportCreated()
                            + "，feishuAlertMessageId="
                            + oneLine(deliveryReviewFailure.feishuAlertMessageId())
                            + "；远端 PR body 已反查交付复核和 QA 证据，pullRequestUrl="
                            + oneLine(githubPrRemoteEvidence.pullRequestUrl())
                            + "。"));
        } else {
            statuses.put(8, new MatrixStatus("NOT_RUN",
                    "需要提供同版本、同环境、同执行人的 Delivery review failure JSON sidecar，证明复核失败会阻断 PR、成功报告和完成态；还需要 GitHub PR 远端反查 JSON sidecar，证明远端 PR body 包含交付复核、QA 证据、taskId 和产物链接。"));
        }
        if (workflowRecoveryEvidenceValidated(evidence)) {
            WorkflowRecoveryEvidence recovery = evidence.workflowRecoveryEvidence();
            statuses.put(9, new MatrixStatus("PASSED",
                    "recoveryTaskId=" + oneLine(recovery.taskId())
                            + "；真实重启恢复证据已校验，stageRuns="
                            + recovery.stageRunCountBeforeRestart()
                            + "->"
                            + recovery.stageRunCountAfterRestart()
                            + "，stageEvents="
                            + recovery.stageEventCountBeforeRestart()
                            + "->"
                            + recovery.stageEventCountAfterRestart()
                            + "，duplicateSuccessfulStageCount="
                            + recovery.duplicateSuccessfulStageCount()
                            + "，retryAttemptCount="
                            + recovery.retryAttemptCount()
                            + "。"));
        } else {
            statuses.put(9, new MatrixStatus("NOT_RUN",
                    "需要提供同版本、同环境、同执行人且 taskId 等于主任务的 workflow recovery JSON sidecar，证明重启后未重复派发且事件只增不减。"));
        }
        if (feishuAlertEvidenceValidated(evidence)) {
            statuses.put(10, new MatrixStatus("PASSED",
                    evidenceRef + "；六类真实 Feishu 告警均已送达，messageCount="
                            + evidence.feishuAlertMessageCount()
                            + "，taskId=" + oneLine(evidence.feishuAlertTaskId())
                            + "，types=" + String.join(",", evidence.feishuAlertTypes()) + "。"));
        } else {
            statuses.put(10, new MatrixStatus("NOT_RUN",
                    "需要提供同版本、同环境、同执行人的 Feishu alert JSON sidecar，证明六类告警真实送达、"
                            + "metadata 完整且 feishuAlertTaskId 等于主任务。"));
        }
        if (skillPolicyEvidenceValidated(evidence)) {
            SkillPolicyEvidence skillPolicy = evidence.skillPolicyEvidence();
            statuses.put(11, new MatrixStatus("PASSED",
                    "skillPolicyTaskId=" + oneLine(skillPolicy.taskId())
                            + "，skillId=" + oneLine(skillPolicy.skillId())
                            + "，installPath=" + oneLine(skillPolicy.installPath())
                            + "；真实 Skill 安装 sidecar 已证明低风险安装成功、未授权角色被拒绝、高风险 Skill 等待审批且 installerCallCount="
                            + skillPolicy.installerCallCount()
                            + "。"));
        } else {
            statuses.put(11, new MatrixStatus("NOT_RUN",
                    "需要提供同版本、同环境、同执行人的 Skill policy JSON sidecar，证明安装、拒绝和高风险审批链路。"));
        }
        if (experienceEvidenceValidated(evidence)) {
            statuses.put(12, new MatrixStatus("PASSED",
                    evidenceRef + "；follow-up taskId=" + oneLine(evidence.followUpTaskId())
                            + " 的角色上下文已检索到 "
                            + evidence.retrievedExperienceEvidenceCount()
                            + " 条历史经验证据；experienceHashCount="
                            + evidence.experienceHashCount()
                            + "，experienceRedactedCount="
                            + evidence.experienceRedactedCount()
                            + "，experienceCompleteTypeCount="
                            + evidence.experienceCompleteTypeCount()
                            + "。"));
        } else {
            statuses.put(12, new MatrixStatus("NOT_RUN",
                    "需要五类经验都绑定 source artifact、hash 和脱敏状态，并创建不同于主任务的 follow-up 真实需求，证明角色上下文能检索到 rd-experience:// 历史经验证据。"));
        }
        if (githubPrRemoteEvidenceValidated(evidence)) {
            GitHubPrRemoteEvidence githubPrRemoteEvidence = evidence.githubPrRemoteEvidence();
            statuses.put(13, new MatrixStatus("PASSED",
                    evidenceRef + "；已扫描最终结果、角色上下文、provider attempts、PR 发布 metadata、远端 PR body 和 "
                            + evidence.secretScannedArtifactPreviewCount()
                            + " 个阶段产物 preview，远端 PR="
                            + oneLine(githubPrRemoteEvidence.pullRequestUrl())
                            + "，secretScannedValueCount="
                            + githubPrRemoteEvidence.secretScannedValueCount()
                            + "，未发现配置的 secret needle。"));
        } else {
            statuses.put(13, new MatrixStatus("NOT_RUN",
                    "本地 secret 扫描已记录；还需要同版本、同环境、同执行人的 GitHub PR 远端反查 JSON sidecar，证明远端 PR body 不包含 secret needle。"));
        }
        if (observabilityMetricsEvidenceValidated(evidence) && githubPrRemoteEvidenceValidated(evidence)) {
            ObservabilityMetricsEvidence observabilityMetrics = evidence.observabilityMetricsEvidence();
            GitHubPrRemoteEvidence githubPrRemoteEvidence = evidence.githubPrRemoteEvidence();
            statuses.put(14, new MatrixStatus("PASSED",
                    evidenceRef + "；最小审计链和真实指标端点均已验证，metricsEndpoint="
                            + oneLine(observabilityMetrics.metricsEndpointUrl())
                            + "，stageMetricCount="
                            + observabilityMetrics.stageMetricCount()
                            + "，auditTraceLinkCount="
                            + observabilityMetrics.auditTraceLinkCount()
                            + "，taskBoundAuditTraceLinkCount="
                            + observabilityMetrics.taskBoundAuditTraceLinkCount()
                            + "，remotePrTraceValidated="
                            + observabilityMetrics.remotePrTraceValidated()
                            + "，remotePrUrl="
                            + oneLine(githubPrRemoteEvidence.pullRequestUrl())
                            + "。"));
        } else {
            statuses.put(14, new MatrixStatus("NOT_RUN",
                    "需要提供同版本、同环境、同执行人的 Observability metrics JSON sidecar，证明指标端点、关键指标和远端 PR 审计反查均真实通过；还需要 GitHub PR 远端反查 JSON sidecar，证明远端 PR body 可按 taskId 反查并包含产物链接。"));
        }
        List<String> pendingPriorAcceptances = pendingPriorAcceptanceNumbers(statuses);
        if (pendingPriorAcceptances.isEmpty()) {
            statuses.put(15, new MatrixStatus("PASSED",
                    evidenceRef + "；#1-#14 生产验收均已通过，本次报告可作为完整生产真实测试结论归档。"));
        } else {
            statuses.put(15, new MatrixStatus("NOT_RUN",
                    "必须先完成 #1-#14 全部生产验收；仍未通过或未运行：" + String.join(", ", pendingPriorAcceptances)
                            + "。"));
        }
        return statuses;
    }

    private boolean auditChainEvidenceValidated(MinimumSmokeEvidence evidence) {
        return evidence.timelineEvents() > 0
                && evidence.stageEvents() >= EXPECTED_ROLES.size()
                && evidence.roleContextPackageCount() >= EXPECTED_ROLES.size()
                && evidence.roleContextDifferent()
                && evidence.roleContextDistinctCount() >= EXPECTED_ROLES.size()
                && auditArtifactLinkCount(evidence) >= EXPECTED_ROLES.size() * 2
                && evidence.experienceSourceLinkCount() >= requiredExperienceTypeCount()
                && auditPullRequestTraceValidated(evidence);
    }

    private boolean feishuAlertEvidenceValidated(MinimumSmokeEvidence evidence) {
        List<String> requiredAlertTypes = List.of(
                "STAGE_FAILED_RETRYABLE",
                "STAGE_FAILED_NEEDS_HUMAN",
                "PROVIDER_FALLBACK",
                "QA_FAILED",
                "DELIVERY_REVIEW_FAILED",
                "PR_PUBLICATION_FAILED"
        );
        return evidence.feishuAlertMessageCount() >= requiredAlertTypes.size()
                && evidence.feishuAlertTypes().containsAll(requiredAlertTypes)
                && !evidence.feishuAlertTaskId().isBlank()
                && evidence.taskId().equals(evidence.feishuAlertTaskId())
                && evidence.feishuAlertMetadataComplete();
    }

    private boolean providerFallbackEvidenceValidated(MinimumSmokeEvidence evidence) {
        return evidence.stages()
                .values()
                .stream()
                .anyMatch(this::providerFallbackAttemptDetailsValidated)
                && evidence.feishuAlertTypes().contains("PROVIDER_FALLBACK")
                && feishuAlertEvidenceValidated(evidence);
    }

    private boolean providerFallbackAttemptDetailsValidated(StageEvidence stage) {
        return stage.providerFallbackDetected()
                && !oneLine(stage.failedProvider()).isBlank()
                && !oneLine(stage.failedStatus()).isBlank()
                && !oneLine(stage.activeProvider()).isBlank()
                && !sameProvider(stage.failedProvider(), stage.activeProvider());
    }

    private static boolean sameProvider(String left, String right) {
        return oneLine(left).equalsIgnoreCase(oneLine(right));
    }

    private boolean requirementReviewBlockerEvidenceValidated(MinimumSmokeEvidence evidence) {
        RequirementReviewBlockerEvidence blocker = evidence.requirementReviewBlockerEvidence();
        return blocker != null
                && blocker.validated()
                && evidence.taskId().equals(blocker.taskId())
                && requirementReviewBlockerOutcomeValidated(blocker);
    }

    private boolean requirementReviewBlockerOutcomeValidated(RequirementReviewBlockerEvidence blocker) {
        return !oneLine(blocker.stageRunId()).isBlank()
                && blockingRequirementReviewTaskStatus(blocker.taskStatus())
                && blockingRequirementReviewExecutionStatus(blocker.executionResultStatus())
                && blockingRequirementReviewDecision(blocker.reviewDecision())
                && !oneLine(blocker.reviewArtifactId()).isBlank()
                && productionEvidenceUri(blocker.reviewArtifactUri())
                && !oneLine(blocker.errorMessage()).isBlank()
                && !blocker.missingInformation().isEmpty()
                && blocker.pendingDownstreamRoleCount() >= 3
                && !blocker.downstreamAgentsDispatched()
                && blocker.feishuAlertDelivered()
                && !oneLine(blocker.feishuAlertMessageId()).isBlank();
    }

    private boolean blockingRequirementReviewTaskStatus(String taskStatus) {
        return List.of("FAILED_NEEDS_HUMAN", "REJECTED").contains(oneLine(taskStatus));
    }

    private boolean blockingRequirementReviewExecutionStatus(String executionStatus) {
        return List.of("NEEDS_HUMAN", "FAILED", "FAILED_NEEDS_HUMAN").contains(oneLine(executionStatus));
    }

    private boolean blockingRequirementReviewDecision(String reviewDecision) {
        return List.of(
                "NEED_INFO",
                "NEEDS_HUMAN",
                "UNSAFE",
                "REJECTED",
                "FAILED",
                "BLOCKED"
        ).contains(oneLine(reviewDecision));
    }

    private boolean dockerCodingEvidenceValidated(MinimumSmokeEvidence evidence) {
        DockerCodingEvidence dockerCoding = evidence.dockerCodingEvidence();
        return dockerCoding != null
                && dockerCoding.validated()
                && evidence.taskId().equals(dockerCoding.taskId())
                && dockerCodingExecutionEvidenceValidated(dockerCoding)
                && dockerCodingArtifactIdsAreDistinct(dockerCoding)
                && dockerCodingArtifactUrisAreProduction(dockerCoding);
    }

    private boolean dockerCodingExecutionEvidenceValidated(DockerCodingEvidence dockerCoding) {
        return !oneLine(dockerCoding.stageRunId()).isBlank()
                && !oneLine(dockerCoding.repositoryUrl()).isBlank()
                && !oneLine(dockerCoding.dockerImage()).isBlank()
                && !oneLine(dockerCoding.containerId()).isBlank()
                && !oneLine(dockerCoding.workspacePath()).isBlank()
                && fullGitCommitHash(dockerCoding.commitHash())
                && dockerCoding.changedFileCount() > 0
                && !oneLine(dockerCoding.validationCommand()).isBlank()
                && dockerCoding.validationExitCode() == 0
                && dockerCoding.testsRun() > 0
                && dockerCoding.testsFailed() == 0
                && dockerCoding.patchNonEmpty()
                && dockerCoding.resultJsonValidated()
                && dockerCoding.realDockerRun();
    }

    private boolean dockerCodingArtifactIdsAreDistinct(DockerCodingEvidence dockerCoding) {
        List<String> artifactIds = List.of(
                dockerCoding.patchArtifactId(),
                dockerCoding.resultArtifactId(),
                dockerCoding.testLogArtifactId(),
                dockerCoding.dockerMetadataArtifactId()
        );
        return artifactIds.stream().noneMatch(String::isBlank)
                && artifactIds.stream().distinct().count() == artifactIds.size();
    }

    private boolean dockerCodingArtifactUrisAreProduction(DockerCodingEvidence dockerCoding) {
        List<String> artifactUris = List.of(
                dockerCoding.patchArtifactUri(),
                dockerCoding.resultArtifactUri(),
                dockerCoding.testLogArtifactUri(),
                dockerCoding.dockerMetadataArtifactUri()
        );
        return artifactUris.stream().allMatch(this::productionEvidenceUri)
                && artifactUris.stream().distinct().count() == artifactUris.size();
    }

    private boolean fullGitCommitHash(String commitHash) {
        return oneLine(commitHash).matches("([0-9a-fA-F]{40}|[0-9a-fA-F]{64})");
    }

    private boolean qaFailureBlockerEvidenceValidated(MinimumSmokeEvidence evidence) {
        QaFailureBlockerEvidence qaFailureBlocker = evidence.qaFailureBlockerEvidence();
        return qaFailureBlocker != null
                && qaFailureBlocker.validated()
                && evidence.taskId().equals(qaFailureBlocker.taskId())
                && qaFailureBlockerOutcomeValidated(qaFailureBlocker);
    }

    private boolean qaFailureBlockerOutcomeValidated(QaFailureBlockerEvidence qaFailureBlocker) {
        return !oneLine(qaFailureBlocker.stageRunId()).isBlank()
                && "FAILED_NEEDS_HUMAN".equals(oneLine(qaFailureBlocker.taskStatus()))
                && blockingQaStageStatus(qaFailureBlocker.qaStageStatus())
                && !oneLine(qaFailureBlocker.executionResultStatus()).isBlank()
                && !oneLine(qaFailureBlocker.qaReportArtifactId()).isBlank()
                && productionEvidenceUri(qaFailureBlocker.qaReportArtifactUri())
                && qaFailureValidationLogArtifactUrisCover(qaFailureBlocker)
                && qaFailureBlocker.failedAcceptanceCount() > 0
                && qaFailureBlocker.acceptanceResultCount() >= qaFailureBlocker.failedAcceptanceCount()
                && qaFailureBlocker.validationCommandCount() >= qaFailureBlocker.acceptanceResultCount()
                && qaFailureBlocker.validationLogArtifactCount() >= qaFailureBlocker.acceptanceResultCount()
                && !qaFailureBlocker.prCreated()
                && !qaFailureBlocker.successReportCreated()
                && qaFailureBlocker.blockedBeforePrCreating()
                && qaFailureBlocker.feishuAlertDelivered()
                && !oneLine(qaFailureBlocker.feishuAlertMessageId()).isBlank();
    }

    private boolean blockingQaStageStatus(String status) {
        return List.of("FAILED_NEEDS_HUMAN", "FAILED_VALIDATION").contains(oneLine(status));
    }

    private boolean qaFailureValidationLogArtifactUrisCover(QaFailureBlockerEvidence qaFailureBlocker) {
        return qaFailureBlocker.validationLogArtifactUris().size() >= qaFailureBlocker.acceptanceResultCount()
                && qaFailureBlocker.validationLogArtifactUris().stream().allMatch(this::productionEvidenceUri)
                && qaFailureBlocker.validationLogArtifactUris().stream().distinct().count()
                == qaFailureBlocker.validationLogArtifactUris().size();
    }

    private boolean deliveryReviewFailureEvidenceValidated(MinimumSmokeEvidence evidence) {
        DeliveryReviewFailureEvidence deliveryReviewFailure = evidence.deliveryReviewFailureEvidence();
        return deliveryReviewFailure != null
                && deliveryReviewFailure.validated()
                && evidence.taskId().equals(deliveryReviewFailure.taskId())
                && deliveryReviewFailureOutcomeValidated(deliveryReviewFailure);
    }

    private boolean deliveryReviewFailureOutcomeValidated(DeliveryReviewFailureEvidence deliveryReviewFailure) {
        return "REJECTED".equals(oneLine(deliveryReviewFailure.taskStatus()))
                && !deliveryReviewFailure.deliveryReviewApproved()
                && "REJECTED".equals(oneLine(deliveryReviewFailure.reviewDecision()))
                && !oneLine(deliveryReviewFailure.reviewer()).isBlank()
                && !oneLine(deliveryReviewFailure.reviewArtifactId()).isBlank()
                && productionEvidenceUri(deliveryReviewFailure.reviewArtifactUri())
                && !oneLine(deliveryReviewFailure.rejectionReason()).isBlank()
                && !deliveryReviewFailure.pullRequestPublicationAttempted()
                && !deliveryReviewFailure.prCreated()
                && !deliveryReviewFailure.successReportCreated()
                && deliveryReviewFailure.failureReportCreated()
                && !deliveryReviewFailure.successDeliveryReportExperienceCreated()
                && deliveryReviewFailure.blockedBeforePrCreating()
                && deliveryReviewFailure.feishuAlertDelivered()
                && !oneLine(deliveryReviewFailure.feishuAlertMessageId()).isBlank();
    }

    private boolean githubPrRemoteEvidenceValidated(MinimumSmokeEvidence evidence) {
        GitHubPrRemoteEvidence githubPrRemote = evidence.githubPrRemoteEvidence();
        return githubPrRemote != null
                && githubPrRemote.validated()
                && evidence.taskId().equals(githubPrRemote.taskId())
                && oneLine(evidence.pullRequestUrl()).equals(oneLine(githubPrRemote.pullRequestUrl()))
                && githubPrRemoteOutcomeValidated(githubPrRemote);
    }

    private boolean githubPrRemoteOutcomeValidated(GitHubPrRemoteEvidence githubPrRemote) {
        return githubPullRequestUrlValidated(githubPrRemote.pullRequestUrl(), githubPrRemote.pullRequestNumber())
                && !oneLine(githubPrRemote.pullRequestNumber()).isBlank()
                && !oneLine(githubPrRemote.baseBranch()).isBlank()
                && githubPrRemoteWorkBranchValidated(githubPrRemote)
                && githubPrRemote.pullRequestBodyIncludesDeliveryReview()
                && githubPrRemote.pullRequestBodyIncludesQaEvidence()
                && githubPrRemote.pullRequestBodyContainsTaskId()
                && githubPrRemote.pullRequestBodyContainsArtifactLink()
                && githubPrRemote.secretScanEvidenceValidated()
                && !githubPrRemote.secretLeakFound()
                && githubPrRemote.secretScannedValueCount() > 0;
    }

    private boolean githubPrRemoteWorkBranchValidated(GitHubPrRemoteEvidence githubPrRemote) {
        String taskId = oneLine(githubPrRemote.taskId());
        return !taskId.isBlank() && ("requirement/" + taskId).equals(oneLine(githubPrRemote.workBranch()));
    }

    private boolean observabilityMetricsEvidenceValidated(MinimumSmokeEvidence evidence) {
        ObservabilityMetricsEvidence observabilityMetrics = evidence.observabilityMetricsEvidence();
        return observabilityMetrics != null
                && observabilityMetrics.validated()
                && evidence.taskId().equals(observabilityMetrics.taskId())
                && observabilityMetricsOutcomeValidated(observabilityMetrics);
    }

    private boolean observabilityMetricsOutcomeValidated(ObservabilityMetricsEvidence observabilityMetrics) {
        return metricsEndpointUrlValidated(observabilityMetrics.metricsEndpointUrl())
                && observabilityMetrics.metricsHttpStatus() == 200
                && observabilityMetrics.contextBuildLatencyMetricPresent()
                && observabilityMetrics.repairSuccessRateMetricPresent()
                && observabilityMetrics.validationPassRateMetricPresent()
                && observabilityMetrics.prCreationRateMetricPresent()
                && observabilityMetrics.humanInterventionRateMetricPresent()
                && observabilityMetrics.retryRateMetricPresent()
                && observabilityMetrics.meanTimeToRepairMetricPresent()
                && observabilityMetrics.topFailureCategoriesMetricPresent()
                && observabilityMetrics.stageMetricCount() >= EXPECTED_ROLES.size()
                && observabilityMetrics.auditTraceQuerySucceeded()
                && observabilityMetrics.remotePrTraceValidated()
                && observabilityMetrics.auditTraceLinkCount() >= 8
                && observabilityMetrics.taskBoundAuditTraceLinkCount() >= 8
                && observabilityMetrics.taskBoundAuditTraceLinkCount() <= observabilityMetrics.auditTraceLinkCount();
    }

    private boolean githubPullRequestUrlValidated(String pullRequestUrl, String pullRequestNumber) {
        String safePullRequestUrl = oneLine(pullRequestUrl);
        String safePullRequestNumber = oneLine(pullRequestNumber);
        if (safePullRequestUrl.isBlank() || !safePullRequestNumber.matches("\\d+")) {
            return false;
        }
        try {
            URI uri = URI.create(safePullRequestUrl).normalize();
            String path = oneLine(uri.getPath());
            return uri.isAbsolute()
                    && httpOrHttps(uri)
                    && "github.com".equalsIgnoreCase(oneLine(uri.getHost()))
                    && path.matches("/[^/]+/[^/]+/pull/" + safePullRequestNumber);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean metricsEndpointUrlValidated(String metricsEndpointUrl) {
        String safeMetricsEndpointUrl = oneLine(metricsEndpointUrl);
        if (safeMetricsEndpointUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(safeMetricsEndpointUrl).normalize();
            return uri.isAbsolute()
                    && httpOrHttps(uri)
                    && !oneLine(uri.getHost()).isBlank()
                    && "/actuator/prometheus".equals(oneLine(uri.getPath()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean workflowRecoveryEvidenceValidated(MinimumSmokeEvidence evidence) {
        WorkflowRecoveryEvidence recovery = evidence.workflowRecoveryEvidence();
        return recovery != null
                && recovery.validated()
                && evidence.taskId().equals(recovery.taskId())
                && workflowRecoveryOutcomeValidated(recovery);
    }

    private boolean workflowRecoveryOutcomeValidated(WorkflowRecoveryEvidence recovery) {
        return recovery.stageRunCountBeforeRestart() > 0
                && recovery.stageRunCountAfterRestart() >= EXPECTED_ROLES.size()
                && recovery.stageRunCountAfterRestart() >= recovery.stageRunCountBeforeRestart()
                && recovery.stageEventCountBeforeRestart() > 0
                && recovery.stageEventCountAfterRestart() >= recovery.stageEventCountBeforeRestart()
                && recovery.duplicateSuccessfulStageCount() == 0
                && recovery.retryAttemptCount() >= 0
                && recovery.retainedRetryArtifactCount() >= 0
                && recovery.deliveryReviewApprovedBeforePrCreating()
                && statusesContainInOrder(recovery.timelineStatuses(), List.of(
                        "EXECUTING",
                        "VALIDATING",
                        "PR_CREATING",
                        "COMMITTED",
                        "REPORTING",
                        "COMPLETED"
                ))
                && productionEvidenceUri(recovery.startupLogEvidenceUri())
                && productionEvidenceUri(recovery.databaseSnapshotEvidenceUri());
    }

    private boolean productionEvidenceUri(String value) {
        return ProductionEvidenceUris.isProductionArtifactUri(value);
    }

    private boolean httpOrHttps(URI uri) {
        String scheme = oneLine(uri.getScheme()).toLowerCase(java.util.Locale.ROOT);
        return "http".equals(scheme) || "https".equals(scheme);
    }

    private boolean statusesContainInOrder(List<String> actual, List<String> expected) {
        int cursor = 0;
        for (String status : actual) {
            if (cursor < expected.size() && expected.get(cursor).equals(oneLine(status))) {
                cursor++;
            }
        }
        return cursor == expected.size() && firstStatusOccurrencesAreOrdered(actual, expected);
    }

    private boolean firstStatusOccurrencesAreOrdered(List<String> actual, List<String> expected) {
        int previous = -1;
        for (String status : expected) {
            int current = firstStatusIndex(actual, status);
            if (current <= previous) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private int firstStatusIndex(List<String> actual, String expectedStatus) {
        for (int index = 0; index < actual.size(); index++) {
            if (oneLine(actual.get(index)).equals(expectedStatus)) {
                return index;
            }
        }
        return -1;
    }

    private boolean skillPolicyEvidenceValidated(MinimumSmokeEvidence evidence) {
        SkillPolicyEvidence skillPolicy = evidence.skillPolicyEvidence();
        return skillPolicy != null
                && skillPolicy.validated()
                && evidence.taskId().equals(skillPolicy.taskId())
                && skillPolicyRolesAreDistinct(skillPolicy)
                && skillPolicyOutcomesValidated(skillPolicy)
                && skillInstallPathMatchesIdentity(skillPolicy)
                && sourceChecksumIsFullSha256(skillPolicy.sourceChecksum());
    }

    private boolean sourceChecksumIsFullSha256(String sourceChecksum) {
        return oneLine(sourceChecksum).matches("sha256:[0-9a-fA-F]{64}");
    }

    private boolean skillPolicyRolesAreDistinct(SkillPolicyEvidence skillPolicy) {
        String allowedRole = oneLine(skillPolicy.allowedRole());
        String rejectedRole = oneLine(skillPolicy.rejectedRole());
        String highRiskRole = oneLine(skillPolicy.highRiskRole());
        return !allowedRole.isBlank()
                && !rejectedRole.isBlank()
                && !highRiskRole.isBlank()
                && !allowedRole.equals(rejectedRole)
                && !allowedRole.equals(highRiskRole)
                && !rejectedRole.equals(highRiskRole);
    }

    private boolean skillPolicyOutcomesValidated(SkillPolicyEvidence skillPolicy) {
        return skillPolicy.installed()
                && skillPolicy.unauthorizedRejected()
                && skillPolicy.highRiskWaitingApproval()
                && skillPolicy.metadataValidated()
                && skillPolicy.installerCallCount() == 1;
    }

    private boolean skillInstallPathMatchesIdentity(SkillPolicyEvidence skillPolicy) {
        String installPath = oneLine(skillPolicy.installPath());
        String skillId = oneLine(skillPolicy.skillId());
        String skillVersion = oneLine(skillPolicy.skillVersion());
        if (installPath.isBlank() || skillId.isBlank() || skillVersion.isBlank()) {
            return false;
        }
        try {
            java.nio.file.Path normalizedInstallPath = java.nio.file.Path.of(installPath).normalize();
            return normalizedInstallPath.isAbsolute()
                    && normalizedInstallPath.endsWith(java.nio.file.Path.of(skillId, skillVersion));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String providerFallbackSummary(MinimumSmokeEvidence evidence) {
        return evidence.stages()
                .entrySet()
                .stream()
                .filter(entry -> providerFallbackAttemptDetailsValidated(entry.getValue()))
                .findFirst()
                .map(entry -> "role=" + entry.getKey() + ", " + providerFallbackDetail(entry.getValue()))
                .orElse("");
    }

    private String providerFallbackDetail(StageEvidence stage) {
        if (!stage.providerFallbackDetected()) {
            return "";
        }
        return "failedProvider=" + oneLine(stage.failedProvider())
                + ", failedStatus=" + oneLine(stage.failedStatus())
                + ", activeProvider=" + oneLine(stage.activeProvider());
    }

    private int auditArtifactLinkCount(MinimumSmokeEvidence evidence) {
        return evidence.stages()
                .values()
                .stream()
                .mapToInt(StageEvidence::stageArtifactCount)
                .sum();
    }

    private boolean auditPullRequestTraceValidated(MinimumSmokeEvidence evidence) {
        return evidence.pullRequestPublicationSucceeded()
                && evidence.pullRequestEvidenceValidated()
                && evidence.pullRequestTargetAllowed()
                && evidence.pullRequestBodyEvidenceIncluded();
    }

    private Map<Integer, MatrixStatus> notRunMatrix(String reason) {
        Map<Integer, MatrixStatus> statuses = new LinkedHashMap<>();
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            statuses.put(point.number(), new MatrixStatus("NOT_RUN", reason));
        }
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix() {
        Map<Integer, MatrixStatus> statuses = notRunMatrix("最小生产 smoke 失败，该验收点未被当前证据证明。");
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "最小生产 smoke 失败，本次报告不能作为完整生产真实测试结论。"
        ));
        return statuses;
    }

    private Map<Integer, MatrixStatus> failedMatrix(MinimumSmokeEvidence evidence) {
        Map<Integer, MatrixStatus> statuses = failedMatrix();
        String evidenceRef = "taskId=" + oneLine(evidence.taskId())
                + ", PR=" + oneLine(evidence.pullRequestUrl())
                + ", stageEvents=" + evidence.stageEvents();
        if (minimumWorkflowEntryEvidenceValidated(evidence)) {
            statuses.put(1, new MatrixStatus("PASSED",
                    evidenceRef + "；真实 HTTP 创建任务并查询到四角色阶段及阶段事件。"));
        }
        if (roleContextEvidenceValidated(evidence)) {
            statuses.put(2, new MatrixStatus("PASSED",
                    evidenceRef + "；真实 PostgreSQL 查询到四个角色上下文包且 roleContextDistinctCount="
                            + evidence.roleContextDistinctCount()
                            + "。"));
        }
        if (requirementReviewBlockerEvidenceValidated(evidence)) {
            RequirementReviewBlockerEvidence blocker = evidence.requirementReviewBlockerEvidence();
            statuses.put(3, new MatrixStatus("PASSED",
                    "requirementReviewTaskId=" + oneLine(blocker.taskId())
                            + "；真实缺信息需求已由 REQUIREMENT_REVIEWER 阻断，taskStatus="
                            + oneLine(blocker.taskStatus())
                            + "，decision="
                            + oneLine(blocker.reviewDecision())
                            + "，pendingDownstreamRoleCount="
                            + blocker.pendingDownstreamRoleCount()
                            + "，feishuAlertMessageId="
                            + oneLine(blocker.feishuAlertMessageId())
                            + "。"));
        }
        if (solutionPlanEvidenceValidated(evidence)) {
            statuses.put(4, new MatrixStatus("PASSED",
                    evidenceRef + "；方案结果产物已通过协议校验，implementationSteps="
                            + evidence.solutionImplementationStepCount()
                            + "，affectedFiles="
                            + evidence.solutionAffectedFileCount()
                            + "，acceptanceMapping="
                            + evidence.solutionAcceptanceMappingCount()
                            + "，testPlan="
                            + evidence.solutionTestPlanStepCount()
                            + "，编码 prompt 已引用方案上游结果。"));
        }
        if (providerFallbackEvidenceValidated(evidence)) {
            statuses.put(5, new MatrixStatus("PASSED",
                    evidenceRef + "；provider fallback 已由真实 providerAttemptsJson 和 Feishu PROVIDER_FALLBACK 告警共同证明，"
                            + providerFallbackSummary(evidence) + "。"));
        }
        if (dockerCodingEvidenceValidated(evidence)) {
            DockerCodingEvidence dockerCoding = evidence.dockerCodingEvidence();
            statuses.put(6, new MatrixStatus("PASSED",
                    "dockerCodingTaskId=" + oneLine(dockerCoding.taskId())
                            + "；真实 CODING_AGENT Docker 执行证据已校验，image="
                            + oneLine(dockerCoding.dockerImage())
                            + "，changedFileCount="
                            + dockerCoding.changedFileCount()
                            + "，validationCommand="
                            + oneLine(dockerCoding.validationCommand())
                            + "，testsRun="
                            + dockerCoding.testsRun()
                            + "。"));
        }
        if (qaDeliveryEvidenceValidated(evidence) && qaFailureBlockerEvidenceValidated(evidence)) {
            QaFailureBlockerEvidence qaFailureBlocker = evidence.qaFailureBlockerEvidence();
            statuses.put(7, new MatrixStatus("PASSED",
                    "taskId=" + oneLine(evidence.taskId())
                            + "；成功路径 QA report 已逐条绑定验收标准、真实命令和日志产物，qaAcceptanceResultCount="
                            + evidence.qaAcceptanceResultCount()
                            + "，qaValidationCommandCount="
                            + evidence.qaValidationCommandCount()
                            + "，qaValidationLogArtifactCount="
                            + evidence.qaValidationLogArtifactCount()
                            + "；qaFailureBlockerTaskId=" + oneLine(qaFailureBlocker.taskId())
                            + "；真实 QA 失败需求已阻断交付，qaStageStatus="
                            + oneLine(qaFailureBlocker.qaStageStatus())
                            + "，failedAcceptanceCount="
                            + qaFailureBlocker.failedAcceptanceCount()
                            + "，prCreated="
                            + qaFailureBlocker.prCreated()
                            + "，successReportCreated="
                            + qaFailureBlocker.successReportCreated()
                            + "，feishuAlertMessageId="
                            + oneLine(qaFailureBlocker.feishuAlertMessageId())
                            + "。"));
        }
        if (deliveryReviewEvidenceValidated(evidence) && githubPrRemoteEvidenceValidated(evidence)) {
            DeliveryReviewFailureEvidence deliveryReviewFailure = evidence.deliveryReviewFailureEvidence();
            statuses.put(8, new MatrixStatus("PASSED",
                    evidenceRef + "；成功路径 PR 发布 metadata 已验证，且真实复核失败演练已阻断交付，deliveryReviewFailureTaskId="
                            + oneLine(deliveryReviewFailure.taskId())
                            + "，reviewDecision="
                            + oneLine(deliveryReviewFailure.reviewDecision())
                            + "，pullRequestPublicationAttempted="
                            + deliveryReviewFailure.pullRequestPublicationAttempted()
                            + "，prCreated="
                            + deliveryReviewFailure.prCreated()
                            + "，successReportCreated="
                            + deliveryReviewFailure.successReportCreated()
                            + "，feishuAlertMessageId="
                            + oneLine(deliveryReviewFailure.feishuAlertMessageId())
                            + "。"));
        } else if (deliveryReviewEvidenceValidated(evidence)) {
            statuses.put(8, new MatrixStatus("NOT_RUN",
                    "本地交付复核和 PR metadata 已记录；还需要同版本、同环境、同执行人的 GitHub PR 远端反查 JSON sidecar，证明最终交付 PR body 真实包含复核结论和 QA 证据。"));
        }
        if (workflowRecoveryEvidenceValidated(evidence)) {
            WorkflowRecoveryEvidence recovery = evidence.workflowRecoveryEvidence();
            statuses.put(9, new MatrixStatus("PASSED",
                    "recoveryTaskId=" + oneLine(recovery.taskId())
                            + "；真实重启恢复证据已校验，stageRuns="
                            + recovery.stageRunCountBeforeRestart()
                            + "->"
                            + recovery.stageRunCountAfterRestart()
                            + "，stageEvents="
                            + recovery.stageEventCountBeforeRestart()
                            + "->"
                            + recovery.stageEventCountAfterRestart()
                            + "，duplicateSuccessfulStageCount="
                            + recovery.duplicateSuccessfulStageCount()
                            + "，retryAttemptCount="
                            + recovery.retryAttemptCount()
                            + "。"));
        }
        if (feishuAlertEvidenceValidated(evidence)) {
            statuses.put(10, new MatrixStatus("PASSED",
                    evidenceRef + "；六类真实 Feishu 告警均已送达，messageCount="
                            + evidence.feishuAlertMessageCount()
                            + "，taskId=" + oneLine(evidence.feishuAlertTaskId())
                            + "，types=" + String.join(",", evidence.feishuAlertTypes()) + "。"));
        }
        if (skillPolicyEvidenceValidated(evidence)) {
            SkillPolicyEvidence skillPolicy = evidence.skillPolicyEvidence();
            statuses.put(11, new MatrixStatus("PASSED",
                    "skillPolicyTaskId=" + oneLine(skillPolicy.taskId())
                            + "，skillId=" + oneLine(skillPolicy.skillId())
                            + "，installPath=" + oneLine(skillPolicy.installPath())
                            + "；真实 Skill 安装 sidecar 已证明低风险安装成功、未授权角色被拒绝、高风险 Skill 等待审批且 installerCallCount="
                            + skillPolicy.installerCallCount()
                            + "。"));
        }
        if (experienceEvidenceValidated(evidence)) {
            statuses.put(12, new MatrixStatus("PASSED",
                    evidenceRef + "；follow-up taskId=" + oneLine(evidence.followUpTaskId())
                            + " 的角色上下文已检索到 "
                            + evidence.retrievedExperienceEvidenceCount()
                            + " 条历史经验证据；experienceHashCount="
                            + evidence.experienceHashCount()
                            + "，experienceRedactedCount="
                            + evidence.experienceRedactedCount()
                            + "，experienceCompleteTypeCount="
                            + evidence.experienceCompleteTypeCount()
                            + "。"));
        } else {
            statuses.put(12, new MatrixStatus("NOT_RUN",
                    "需要五类经验都绑定 source artifact、hash 和脱敏状态，并创建不同于主任务的 follow-up 真实需求，证明角色上下文能检索到 rd-experience:// 历史经验证据。"));
        }
        if (githubPrRemoteEvidenceValidated(evidence)) {
            GitHubPrRemoteEvidence githubPrRemoteEvidence = evidence.githubPrRemoteEvidence();
            statuses.put(13, new MatrixStatus("PASSED",
                    evidenceRef + "；已扫描最终结果、角色上下文、provider attempts、PR 发布 metadata、远端 PR body 和 "
                            + evidence.secretScannedArtifactPreviewCount()
                            + " 个阶段产物 preview，远端 PR="
                            + oneLine(githubPrRemoteEvidence.pullRequestUrl())
                            + "，secretScannedValueCount="
                            + githubPrRemoteEvidence.secretScannedValueCount()
                            + "，未发现配置的 secret needle。"));
        } else {
            statuses.put(13, new MatrixStatus("NOT_RUN",
                    "本地 secret 扫描已记录；还需要同版本、同环境、同执行人的 GitHub PR 远端反查 JSON sidecar，证明远端 PR body 不包含 secret needle。"));
        }
        if (observabilityMetricsEvidenceValidated(evidence) && githubPrRemoteEvidenceValidated(evidence)) {
            ObservabilityMetricsEvidence observabilityMetrics = evidence.observabilityMetricsEvidence();
            statuses.put(14, new MatrixStatus("PASSED",
                    evidenceRef + "；最小审计链和真实指标端点均已验证，metricsEndpoint="
                            + oneLine(observabilityMetrics.metricsEndpointUrl())
                            + "，stageMetricCount="
                            + observabilityMetrics.stageMetricCount()
                            + "，auditTraceLinkCount="
                            + observabilityMetrics.auditTraceLinkCount()
                            + "，taskBoundAuditTraceLinkCount="
                            + observabilityMetrics.taskBoundAuditTraceLinkCount()
                            + "，remotePrTraceValidated="
                            + observabilityMetrics.remotePrTraceValidated()
                            + "。"));
        } else if (observabilityMetricsEvidenceValidated(evidence)) {
            statuses.put(14, new MatrixStatus("NOT_RUN",
                    "本地指标端点和审计链已记录；还需要同版本、同环境、同执行人的 GitHub PR 远端反查 JSON sidecar，证明审计链可追溯到真实远端 PR。"));
        }
        statuses.put(15, new MatrixStatus(
                "FAILED",
                "最小生产 smoke 失败，本次报告不能作为完整生产真实测试结论。"
        ));
        return statuses;
    }

    private boolean minimumWorkflowEntryEvidenceValidated(MinimumSmokeEvidence evidence) {
        return !oneLine(evidence.taskId()).isBlank()
                && evidence.stageEvents() >= EXPECTED_ROLES.size()
                && EXPECTED_ROLES.stream().allMatch(role -> evidence.stages().containsKey(role));
    }

    private boolean roleContextEvidenceValidated(MinimumSmokeEvidence evidence) {
        return !oneLine(evidence.taskId()).isBlank()
                && evidence.roleContextPackageCount() >= EXPECTED_ROLES.size()
                && evidence.roleContextDifferent()
                && evidence.roleContextDistinctCount() >= EXPECTED_ROLES.size()
                && EXPECTED_ROLES.stream()
                .map(evidence.stages()::get)
                .allMatch(stage -> stage != null && stage.contextPackageRecorded());
    }

    private boolean solutionPlanEvidenceValidated(MinimumSmokeEvidence evidence) {
        return !oneLine(evidence.taskId()).isBlank()
                && evidence.solutionPlanEvidenceValidated()
                && evidence.solutionImplementationStepCount() > 0
                && evidence.solutionAffectedFileCount() > 0
                && evidence.solutionAcceptanceMappingCount() > 0
                && evidence.solutionTestPlanStepCount() > 0
                && evidence.codingPromptReferencesSolutionPlan();
    }

    private boolean deliveryReviewEvidenceValidated(MinimumSmokeEvidence evidence) {
        return evidence.deliveryReviewApproved()
                && evidence.pullRequestPublicationSucceeded()
                && evidence.pullRequestEvidenceValidated()
                && evidence.pullRequestTargetAllowed()
                && evidence.pullRequestBodyEvidenceIncluded()
                && evidence.pullRequestQaAcceptanceResultCount() > 0
                && deliveryReviewFailureEvidenceValidated(evidence);
    }

    private boolean qaDeliveryEvidenceValidated(MinimumSmokeEvidence evidence) {
        return evidence.qaReportEvidenceValidated()
                && evidence.qaAcceptanceResultCount() > 0
                && evidence.qaPassedAcceptanceResultCount() == evidence.qaAcceptanceResultCount()
                && evidence.qaValidationCommandCount() >= evidence.qaAcceptanceResultCount()
                && evidence.qaValidationLogArtifactCount() >= evidence.qaAcceptanceResultCount();
    }

    private boolean experienceEvidenceValidated(MinimumSmokeEvidence evidence) {
        return evidence.experienceSourceLinkCount() >= requiredExperienceTypeCount()
                && evidence.experienceHashCount() >= requiredExperienceTypeCount()
                && evidence.experienceRedactedCount() >= requiredExperienceTypeCount()
                && evidence.experienceCompleteTypeCount() >= requiredExperienceTypeCount()
                && evidence.experienceRetrievalEvidenceValidated()
                && !oneLine(evidence.followUpTaskId()).isBlank()
                && !oneLine(evidence.taskId()).equals(oneLine(evidence.followUpTaskId()))
                && evidence.retrievedExperienceEvidenceCount() > 0
                && hasRequiredExperienceTypes(evidence);
    }

    private int requiredExperienceTypeCount() {
        return List.of(
                "REQUIREMENT_REVIEW",
                "TECHNICAL_DESIGN",
                "CODE_CHANGE",
                "QA_REPORT",
                "DELIVERY_REPORT"
        ).size();
    }

    private static int defaultRoleContextDistinctCount(int roleContextPackageCount, boolean roleContextDifferent) {
        return roleContextDifferent ? Math.max(roleContextPackageCount, 0) : 0;
    }

    private static int defaultEvidenceCount(boolean evidenceValidated, int fallbackCount) {
        return evidenceValidated ? Math.max(fallbackCount, 0) : 0;
    }

    private boolean secretScanEvidenceValidated(MinimumSmokeEvidence evidence) {
        return evidence.secretScanEvidenceValidated()
                && !evidence.secretNeedles().isEmpty()
                && evidence.secretScannedValueCount() > 0
                && evidence.secretScannedArtifactPreviewCount() >= EXPECTED_ROLES.size() * 2
                && evidence.secretScannedPullRequestMetadata();
    }

    private String passedSmokeConclusion(Map<Integer, MatrixStatus> matrix) {
        MatrixStatus finalAcceptance = matrix.get(15);
        if (finalAcceptance != null && "PASSED".equals(finalAcceptance.status())) {
            return "PASSED_FULL_PRODUCTION_ACCEPTANCE";
        }
        return "PASSED_MINIMUM_SMOKE";
    }

    private List<String> pendingPriorAcceptanceNumbers(Map<Integer, MatrixStatus> statuses) {
        List<String> pending = new ArrayList<>();
        for (int point = 1; point <= 14; point++) {
            MatrixStatus status = statuses.get(point);
            if (status == null || !"PASSED".equals(status.status())) {
                pending.add("#" + point);
            }
        }
        return pending;
    }

    private void appendMatrix(StringBuilder markdown, Map<Integer, MatrixStatus> statuses) {
        markdown.append("| # | 验收点 | 结论 | 证据/缺口 |\n");
        markdown.append("| --- | --- | --- | --- |\n");
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            MatrixStatus status = statuses.getOrDefault(point.number(), new MatrixStatus(
                    "NOT_RUN",
                    "本次最小 smoke 未覆盖，需要生产专项演练。"
            ));
            markdown.append("| ").append(point.number())
                    .append(" | ").append(point.title())
                    .append(" | ").append(status.status())
                    .append(" | ").append(oneLine(status.evidence()))
                    .append(" |\n");
        }
    }

    private Path write(StringBuilder markdown) throws IOException {
        Files.createDirectories(reportRoot);
        Path reportPath = reportRoot.resolve("multi-agent-production-acceptance-"
                + FILE_TIME.format(clock.instant()) + ".md");
        Files.writeString(reportPath, markdown.toString());
        return reportPath;
    }

    private void writeSkippedJson(
            Path markdownReportPath,
            List<String> missingRequirements,
            Map<Integer, MatrixStatus> matrix
    ) throws IOException {
        Map<String, Object> json = baseJson("SKIPPED");
        json.put("multiAgentProductionEvidenceValidated", false);
        json.put("minimumSmokeEvidenceValidated", false);
        json.put("missingRequirements", missingRequirements == null ? List.of() : List.copyOf(missingRequirements));
        json.put("matrix", matrixJson(matrix));
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private void writeEvidenceJson(
            Path markdownReportPath,
            String conclusion,
            MinimumSmokeEvidence evidence,
            Map<Integer, MatrixStatus> matrix,
            Throwable failure
    ) throws IOException {
        Map<String, Object> json = baseJson(conclusion);
        json.put("multiAgentProductionEvidenceValidated", "PASSED_FULL_PRODUCTION_ACCEPTANCE".equals(conclusion));
        json.put("minimumSmokeEvidenceValidated", "PASSED_FULL_PRODUCTION_ACCEPTANCE".equals(conclusion)
                || "PASSED_MINIMUM_SMOKE".equals(conclusion));
        json.put("taskId", evidence == null ? "" : evidence.taskId());
        json.put("taskStatus", evidence == null ? "" : evidence.taskStatus());
        json.put("pullRequestUrl", evidence == null ? "" : evidence.pullRequestUrl());
        json.put("rdBotVersion", evidence == null ? "" : evidence.rdBotVersion());
        json.put("environmentId", evidence == null ? "" : evidence.environmentId());
        json.put("executedBy", evidence == null ? "" : evidence.executedBy());
        json.put("timelineEvents", evidence == null ? 0 : evidence.timelineEvents());
        json.put("stageEvents", evidence == null ? 0 : evidence.stageEvents());
        json.put("expectedProviderCount", evidence == null ? 0 : evidence.expectedProviderCount());
        json.put("providerSecretEnvCount", evidence == null ? 0 : evidence.providerSecretEnvCount());
        json.put("providerFallbackEvidenceValidated", evidence != null && providerFallbackEvidenceValidated(evidence));
        json.put("feishuAlertEvidenceValidated", evidence != null && feishuAlertEvidenceValidated(evidence));
        json.put("githubPrRemoteEvidenceValidated", evidence != null && githubPrRemoteEvidenceValidated(evidence));
        json.put("secretScanEvidenceValidated", evidence != null && secretScanEvidenceValidated(evidence));
        if (failure != null) {
            json.put("errorType", failure.getClass().getSimpleName());
            json.put("message", redact(oneLine(failure.getMessage()), evidence == null ? List.of() : evidence.secretNeedles()));
        }
        json.put("matrix", matrixJson(matrix));
        Files.writeString(jsonPath(markdownReportPath), OBJECT_MAPPER.writeValueAsString(json));
    }

    private Map<String, Object> baseJson(String conclusion) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("conclusion", oneLine(conclusion));
        json.put("generatedAt", clock.instant().toString());
        return json;
    }

    private List<Map<String, Object>> matrixJson(Map<Integer, MatrixStatus> statuses) {
        Map<Integer, MatrixStatus> safeStatuses = statuses == null ? Map.of() : statuses;
        List<Map<String, Object>> matrix = new ArrayList<>();
        for (AcceptancePoint point : ACCEPTANCE_POINTS) {
            MatrixStatus status = safeStatuses.getOrDefault(point.number(), new MatrixStatus(
                    "NOT_RUN",
                    "本次最小 smoke 未覆盖，需要生产专项演练。"
            ));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", point.number());
            row.put("title", point.title());
            row.put("status", status.status());
            row.put("evidence", status.evidence());
            matrix.add(row);
        }
        return List.copyOf(matrix);
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static void requireEvidenceText(String value, String fieldName) {
        if (oneLine(value).isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank for PASSED report");
        }
    }

    private static void requireEquals(String expected, String actual, String fieldName) {
        if (!expected.equalsIgnoreCase(oneLine(actual))) {
            throw new IllegalArgumentException(
                    fieldName + " must be " + expected + " for PASSED report"
            );
        }
    }

    private static String redact(String value, List<String> secretNeedles) {
        String redacted = value == null ? "" : value;
        if (secretNeedles == null) {
            return redacted;
        }
        for (String needle : secretNeedles) {
            String normalized = oneLine(needle);
            if (!normalized.isBlank()) {
                redacted = redacted.replace(normalized, "[REDACTED]");
            }
        }
        return redacted;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path cursor = current;
        while (cursor != null) {
            if (Files.exists(cursor.resolve(".git"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return current;
    }

    record MinimumSmokeEvidence(
            String taskId,
            String taskStatus,
            String pullRequestUrl,
            String rdBotVersion,
            String environmentId,
            String executedBy,
            int timelineEvents,
            int stageEvents,
            Map<String, StageEvidence> stages,
            int roleContextPackageCount,
            boolean roleContextDifferent,
            int roleContextDistinctCount,
            boolean deliveryReviewApproved,
            boolean pullRequestPublicationSucceeded,
            boolean pullRequestEvidenceValidated,
            boolean pullRequestTargetAllowed,
            boolean pullRequestBodyEvidenceIncluded,
            int pullRequestQaAcceptanceResultCount,
            List<String> experienceTypes,
            int experienceSourceLinkCount,
            int experienceHashCount,
            int experienceRedactedCount,
            int experienceCompleteTypeCount,
            boolean experienceRetrievalEvidenceValidated,
            String followUpTaskId,
            int retrievedExperienceEvidenceCount,
            boolean solutionPlanEvidenceValidated,
            int solutionImplementationStepCount,
            int solutionAffectedFileCount,
            int solutionAcceptanceMappingCount,
            int solutionTestPlanStepCount,
            boolean codingPromptReferencesSolutionPlan,
            boolean qaReportEvidenceValidated,
            int qaAcceptanceResultCount,
            int qaPassedAcceptanceResultCount,
            int qaValidationCommandCount,
            int qaValidationLogArtifactCount,
            int expectedProviderCount,
            int providerSecretEnvCount,
            String githubCodePlatformMode,
            String githubAuthMode,
            int githubCredentialEnvCount,
            boolean secretScanEvidenceValidated,
            int secretScannedValueCount,
            int secretScannedArtifactPreviewCount,
            boolean secretScannedPullRequestMetadata,
            List<String> feishuAlertTypes,
            int feishuAlertMessageCount,
            String feishuAlertTaskId,
            boolean feishuAlertMetadataComplete,
            WorkflowRecoveryEvidence workflowRecoveryEvidence,
            SkillPolicyEvidence skillPolicyEvidence,
            RequirementReviewBlockerEvidence requirementReviewBlockerEvidence,
            DockerCodingEvidence dockerCodingEvidence,
            QaFailureBlockerEvidence qaFailureBlockerEvidence,
            DeliveryReviewFailureEvidence deliveryReviewFailureEvidence,
            GitHubPrRemoteEvidence githubPrRemoteEvidence,
            ObservabilityMetricsEvidence observabilityMetricsEvidence,
            List<String> secretNeedles
    ) {
        MinimumSmokeEvidence(
                String taskId,
                String taskStatus,
                String pullRequestUrl,
                String rdBotVersion,
                String environmentId,
                String executedBy,
                int timelineEvents,
                int stageEvents,
                Map<String, StageEvidence> stages,
                int roleContextPackageCount,
                boolean roleContextDifferent,
                boolean deliveryReviewApproved,
                boolean pullRequestPublicationSucceeded,
                boolean pullRequestEvidenceValidated,
                boolean pullRequestTargetAllowed,
                boolean pullRequestBodyEvidenceIncluded,
                int pullRequestQaAcceptanceResultCount,
                List<String> experienceTypes,
                int experienceSourceLinkCount,
                boolean experienceRetrievalEvidenceValidated,
                String followUpTaskId,
                int retrievedExperienceEvidenceCount,
                boolean solutionPlanEvidenceValidated,
                int solutionImplementationStepCount,
                boolean codingPromptReferencesSolutionPlan,
                boolean qaReportEvidenceValidated,
                int qaAcceptanceResultCount,
                int expectedProviderCount,
                int providerSecretEnvCount,
                String githubCodePlatformMode,
                String githubAuthMode,
                int githubCredentialEnvCount,
                boolean secretScanEvidenceValidated,
                int secretScannedValueCount,
                int secretScannedArtifactPreviewCount,
                List<String> feishuAlertTypes,
                int feishuAlertMessageCount,
                boolean feishuAlertMetadataComplete,
                WorkflowRecoveryEvidence workflowRecoveryEvidence,
                List<String> secretNeedles
        ) {
            this(
                    taskId,
                    taskStatus,
                    pullRequestUrl,
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    timelineEvents,
                    stageEvents,
                    stages,
                    roleContextPackageCount,
                    roleContextDifferent,
                    deliveryReviewApproved,
                    pullRequestPublicationSucceeded,
                    pullRequestEvidenceValidated,
                    pullRequestTargetAllowed,
                    pullRequestBodyEvidenceIncluded,
                    pullRequestQaAcceptanceResultCount,
                    experienceTypes,
                    experienceSourceLinkCount,
                    experienceRetrievalEvidenceValidated,
                    followUpTaskId,
                    retrievedExperienceEvidenceCount,
                    solutionPlanEvidenceValidated,
                    solutionImplementationStepCount,
                    codingPromptReferencesSolutionPlan,
                    qaReportEvidenceValidated,
                    qaAcceptanceResultCount,
                    expectedProviderCount,
                    providerSecretEnvCount,
                    githubCodePlatformMode,
                    githubAuthMode,
                    githubCredentialEnvCount,
                    secretScanEvidenceValidated,
                    secretScannedValueCount,
                    secretScannedArtifactPreviewCount,
                    feishuAlertTypes,
                    feishuAlertMessageCount,
                    "",
                    feishuAlertMetadataComplete,
                    workflowRecoveryEvidence,
                    SkillPolicyEvidence.empty(),
                    RequirementReviewBlockerEvidence.empty(),
                    DockerCodingEvidence.empty(),
                    QaFailureBlockerEvidence.empty(),
                    DeliveryReviewFailureEvidence.empty(),
                    GitHubPrRemoteEvidence.empty(),
                    ObservabilityMetricsEvidence.empty(),
                    secretNeedles
            );
        }

        MinimumSmokeEvidence(
                String taskId,
                String taskStatus,
                String pullRequestUrl,
                String rdBotVersion,
                String environmentId,
                String executedBy,
                int timelineEvents,
                int stageEvents,
                Map<String, StageEvidence> stages,
                int roleContextPackageCount,
                boolean roleContextDifferent,
                boolean deliveryReviewApproved,
                boolean pullRequestPublicationSucceeded,
                boolean pullRequestEvidenceValidated,
                boolean pullRequestTargetAllowed,
                boolean pullRequestBodyEvidenceIncluded,
                int pullRequestQaAcceptanceResultCount,
                List<String> experienceTypes,
                int experienceSourceLinkCount,
                boolean experienceRetrievalEvidenceValidated,
                String followUpTaskId,
                int retrievedExperienceEvidenceCount,
                boolean solutionPlanEvidenceValidated,
                int solutionImplementationStepCount,
                boolean codingPromptReferencesSolutionPlan,
                boolean qaReportEvidenceValidated,
                int qaAcceptanceResultCount,
                int expectedProviderCount,
                int providerSecretEnvCount,
                String githubCodePlatformMode,
                String githubAuthMode,
                int githubCredentialEnvCount,
                boolean secretScanEvidenceValidated,
                int secretScannedValueCount,
                int secretScannedArtifactPreviewCount,
                List<String> feishuAlertTypes,
                int feishuAlertMessageCount,
                WorkflowRecoveryEvidence workflowRecoveryEvidence,
                SkillPolicyEvidence skillPolicyEvidence,
                RequirementReviewBlockerEvidence requirementReviewBlockerEvidence,
                DockerCodingEvidence dockerCodingEvidence,
                QaFailureBlockerEvidence qaFailureBlockerEvidence,
                List<String> secretNeedles
        ) {
            this(
                    taskId,
                    taskStatus,
                    pullRequestUrl,
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    timelineEvents,
                    stageEvents,
                    stages,
                    roleContextPackageCount,
                    roleContextDifferent,
                    deliveryReviewApproved,
                    pullRequestPublicationSucceeded,
                    pullRequestEvidenceValidated,
                    pullRequestTargetAllowed,
                    pullRequestBodyEvidenceIncluded,
                    pullRequestQaAcceptanceResultCount,
                    experienceTypes,
                    experienceSourceLinkCount,
                    experienceRetrievalEvidenceValidated,
                    followUpTaskId,
                    retrievedExperienceEvidenceCount,
                    solutionPlanEvidenceValidated,
                    solutionImplementationStepCount,
                    codingPromptReferencesSolutionPlan,
                    qaReportEvidenceValidated,
                    qaAcceptanceResultCount,
                    expectedProviderCount,
                    providerSecretEnvCount,
                    githubCodePlatformMode,
                    githubAuthMode,
                    githubCredentialEnvCount,
                    secretScanEvidenceValidated,
                    secretScannedValueCount,
                    secretScannedArtifactPreviewCount,
                    feishuAlertTypes,
                    feishuAlertMessageCount,
                    "",
                    false,
                    workflowRecoveryEvidence,
                    skillPolicyEvidence,
                    requirementReviewBlockerEvidence,
                    dockerCodingEvidence,
                    qaFailureBlockerEvidence,
                    DeliveryReviewFailureEvidence.empty(),
                    GitHubPrRemoteEvidence.empty(),
                    ObservabilityMetricsEvidence.empty(),
                    secretNeedles
            );
        }

        MinimumSmokeEvidence(
                String taskId,
                String taskStatus,
                String pullRequestUrl,
                String rdBotVersion,
                String environmentId,
                String executedBy,
                int timelineEvents,
                int stageEvents,
                Map<String, StageEvidence> stages,
                int roleContextPackageCount,
                boolean roleContextDifferent,
                boolean deliveryReviewApproved,
                boolean pullRequestPublicationSucceeded,
                boolean pullRequestEvidenceValidated,
                boolean pullRequestTargetAllowed,
                boolean pullRequestBodyEvidenceIncluded,
                int pullRequestQaAcceptanceResultCount,
                List<String> experienceTypes,
                int experienceSourceLinkCount,
                boolean experienceRetrievalEvidenceValidated,
                String followUpTaskId,
                int retrievedExperienceEvidenceCount,
                boolean solutionPlanEvidenceValidated,
                int solutionImplementationStepCount,
                boolean codingPromptReferencesSolutionPlan,
                boolean qaReportEvidenceValidated,
                int qaAcceptanceResultCount,
                int expectedProviderCount,
                int providerSecretEnvCount,
                String githubCodePlatformMode,
                String githubAuthMode,
                int githubCredentialEnvCount,
                boolean secretScanEvidenceValidated,
                int secretScannedValueCount,
                int secretScannedArtifactPreviewCount,
                List<String> feishuAlertTypes,
                int feishuAlertMessageCount,
                WorkflowRecoveryEvidence workflowRecoveryEvidence,
                SkillPolicyEvidence skillPolicyEvidence,
                RequirementReviewBlockerEvidence requirementReviewBlockerEvidence,
                DockerCodingEvidence dockerCodingEvidence,
                List<String> secretNeedles
        ) {
            this(
                    taskId,
                    taskStatus,
                    pullRequestUrl,
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    timelineEvents,
                    stageEvents,
                    stages,
                    roleContextPackageCount,
                    roleContextDifferent,
                    deliveryReviewApproved,
                    pullRequestPublicationSucceeded,
                    pullRequestEvidenceValidated,
                    pullRequestTargetAllowed,
                    pullRequestBodyEvidenceIncluded,
                    pullRequestQaAcceptanceResultCount,
                    experienceTypes,
                    experienceSourceLinkCount,
                    experienceRetrievalEvidenceValidated,
                    followUpTaskId,
                    retrievedExperienceEvidenceCount,
                    solutionPlanEvidenceValidated,
                    solutionImplementationStepCount,
                    codingPromptReferencesSolutionPlan,
                    qaReportEvidenceValidated,
                    qaAcceptanceResultCount,
                    expectedProviderCount,
                    providerSecretEnvCount,
                    githubCodePlatformMode,
                    githubAuthMode,
                    githubCredentialEnvCount,
                    secretScanEvidenceValidated,
                    secretScannedValueCount,
                    secretScannedArtifactPreviewCount,
                    feishuAlertTypes,
                    feishuAlertMessageCount,
                    "",
                    false,
                    workflowRecoveryEvidence,
                    skillPolicyEvidence,
                    requirementReviewBlockerEvidence,
                    dockerCodingEvidence,
                    QaFailureBlockerEvidence.empty(),
                    DeliveryReviewFailureEvidence.empty(),
                    GitHubPrRemoteEvidence.empty(),
                    ObservabilityMetricsEvidence.empty(),
                    secretNeedles
            );
        }

        MinimumSmokeEvidence(
                String taskId,
                String taskStatus,
                String pullRequestUrl,
                String rdBotVersion,
                String environmentId,
                String executedBy,
                int timelineEvents,
                int stageEvents,
                Map<String, StageEvidence> stages,
                int roleContextPackageCount,
                boolean roleContextDifferent,
                boolean deliveryReviewApproved,
                boolean pullRequestPublicationSucceeded,
                boolean pullRequestEvidenceValidated,
                boolean pullRequestTargetAllowed,
                boolean pullRequestBodyEvidenceIncluded,
                int pullRequestQaAcceptanceResultCount,
                List<String> experienceTypes,
                int experienceSourceLinkCount,
                boolean experienceRetrievalEvidenceValidated,
                String followUpTaskId,
                int retrievedExperienceEvidenceCount,
                boolean solutionPlanEvidenceValidated,
                int solutionImplementationStepCount,
                boolean codingPromptReferencesSolutionPlan,
                boolean qaReportEvidenceValidated,
                int qaAcceptanceResultCount,
                int expectedProviderCount,
                int providerSecretEnvCount,
                String githubCodePlatformMode,
                String githubAuthMode,
                int githubCredentialEnvCount,
                boolean secretScanEvidenceValidated,
                int secretScannedValueCount,
                int secretScannedArtifactPreviewCount,
                List<String> feishuAlertTypes,
                int feishuAlertMessageCount,
                WorkflowRecoveryEvidence workflowRecoveryEvidence,
                SkillPolicyEvidence skillPolicyEvidence,
                List<String> secretNeedles
        ) {
            this(
                    taskId,
                    taskStatus,
                    pullRequestUrl,
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    timelineEvents,
                    stageEvents,
                    stages,
                    roleContextPackageCount,
                    roleContextDifferent,
                    deliveryReviewApproved,
                    pullRequestPublicationSucceeded,
                    pullRequestEvidenceValidated,
                    pullRequestTargetAllowed,
                    pullRequestBodyEvidenceIncluded,
                    pullRequestQaAcceptanceResultCount,
                    experienceTypes,
                    experienceSourceLinkCount,
                    experienceRetrievalEvidenceValidated,
                    followUpTaskId,
                    retrievedExperienceEvidenceCount,
                    solutionPlanEvidenceValidated,
                    solutionImplementationStepCount,
                    codingPromptReferencesSolutionPlan,
                    qaReportEvidenceValidated,
                    qaAcceptanceResultCount,
                    expectedProviderCount,
                    providerSecretEnvCount,
                    githubCodePlatformMode,
                    githubAuthMode,
                    githubCredentialEnvCount,
                    secretScanEvidenceValidated,
                    secretScannedValueCount,
                    secretScannedArtifactPreviewCount,
                    feishuAlertTypes,
                    feishuAlertMessageCount,
                    "",
                    false,
                    workflowRecoveryEvidence,
                    skillPolicyEvidence,
                    RequirementReviewBlockerEvidence.empty(),
                    DockerCodingEvidence.empty(),
                    QaFailureBlockerEvidence.empty(),
                    DeliveryReviewFailureEvidence.empty(),
                    GitHubPrRemoteEvidence.empty(),
                    ObservabilityMetricsEvidence.empty(),
                    secretNeedles
            );
        }

        MinimumSmokeEvidence(
                String taskId,
                String taskStatus,
                String pullRequestUrl,
                String rdBotVersion,
                String environmentId,
                String executedBy,
                int timelineEvents,
                int stageEvents,
                Map<String, StageEvidence> stages,
                int roleContextPackageCount,
                boolean roleContextDifferent,
                boolean deliveryReviewApproved,
                boolean pullRequestPublicationSucceeded,
                boolean pullRequestEvidenceValidated,
                boolean pullRequestTargetAllowed,
                boolean pullRequestBodyEvidenceIncluded,
                int pullRequestQaAcceptanceResultCount,
                List<String> experienceTypes,
                int experienceSourceLinkCount,
                boolean experienceRetrievalEvidenceValidated,
                String followUpTaskId,
                int retrievedExperienceEvidenceCount,
                boolean solutionPlanEvidenceValidated,
                int solutionImplementationStepCount,
                boolean codingPromptReferencesSolutionPlan,
                boolean qaReportEvidenceValidated,
                int qaAcceptanceResultCount,
                int expectedProviderCount,
                int providerSecretEnvCount,
                String githubCodePlatformMode,
                String githubAuthMode,
                int githubCredentialEnvCount,
                boolean secretScanEvidenceValidated,
                int secretScannedValueCount,
                int secretScannedArtifactPreviewCount,
                List<String> feishuAlertTypes,
                int feishuAlertMessageCount,
                List<String> secretNeedles
        ) {
            this(
                    taskId,
                    taskStatus,
                    pullRequestUrl,
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    timelineEvents,
                    stageEvents,
                    stages,
                    roleContextPackageCount,
                    roleContextDifferent,
                    deliveryReviewApproved,
                    pullRequestPublicationSucceeded,
                    pullRequestEvidenceValidated,
                    pullRequestTargetAllowed,
                    pullRequestBodyEvidenceIncluded,
                    pullRequestQaAcceptanceResultCount,
                    experienceTypes,
                    experienceSourceLinkCount,
                    experienceRetrievalEvidenceValidated,
                    followUpTaskId,
                    retrievedExperienceEvidenceCount,
                    solutionPlanEvidenceValidated,
                    solutionImplementationStepCount,
                    codingPromptReferencesSolutionPlan,
                    qaReportEvidenceValidated,
                    qaAcceptanceResultCount,
                    expectedProviderCount,
                    providerSecretEnvCount,
                    githubCodePlatformMode,
                    githubAuthMode,
                    githubCredentialEnvCount,
                    secretScanEvidenceValidated,
                    secretScannedValueCount,
                    secretScannedArtifactPreviewCount,
                    feishuAlertTypes,
                    feishuAlertMessageCount,
                    "",
                    false,
                    WorkflowRecoveryEvidence.empty(),
                    SkillPolicyEvidence.empty(),
                    RequirementReviewBlockerEvidence.empty(),
                    DockerCodingEvidence.empty(),
                    QaFailureBlockerEvidence.empty(),
                    DeliveryReviewFailureEvidence.empty(),
                    GitHubPrRemoteEvidence.empty(),
                    ObservabilityMetricsEvidence.empty(),
                    secretNeedles
            );
        }

        MinimumSmokeEvidence(
                String taskId,
                String taskStatus,
                String pullRequestUrl,
                String rdBotVersion,
                String environmentId,
                String executedBy,
                int timelineEvents,
                int stageEvents,
                Map<String, StageEvidence> stages,
                int roleContextPackageCount,
                boolean roleContextDifferent,
                boolean deliveryReviewApproved,
                boolean pullRequestPublicationSucceeded,
                boolean pullRequestEvidenceValidated,
                boolean pullRequestTargetAllowed,
                boolean pullRequestBodyEvidenceIncluded,
                int pullRequestQaAcceptanceResultCount,
                List<String> experienceTypes,
                int experienceSourceLinkCount,
                boolean experienceRetrievalEvidenceValidated,
                String followUpTaskId,
                int retrievedExperienceEvidenceCount,
                boolean solutionPlanEvidenceValidated,
                int solutionImplementationStepCount,
                boolean codingPromptReferencesSolutionPlan,
                boolean qaReportEvidenceValidated,
                int qaAcceptanceResultCount,
                int expectedProviderCount,
                int providerSecretEnvCount,
                String githubCodePlatformMode,
                String githubAuthMode,
                int githubCredentialEnvCount,
                boolean secretScanEvidenceValidated,
                int secretScannedValueCount,
                int secretScannedArtifactPreviewCount,
                List<String> feishuAlertTypes,
                int feishuAlertMessageCount,
                boolean feishuAlertMetadataComplete,
                List<String> secretNeedles
        ) {
            this(
                    taskId,
                    taskStatus,
                    pullRequestUrl,
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    timelineEvents,
                    stageEvents,
                    stages,
                    roleContextPackageCount,
                    roleContextDifferent,
                    deliveryReviewApproved,
                    pullRequestPublicationSucceeded,
                    pullRequestEvidenceValidated,
                    pullRequestTargetAllowed,
                    pullRequestBodyEvidenceIncluded,
                    pullRequestQaAcceptanceResultCount,
                    experienceTypes,
                    experienceSourceLinkCount,
                    experienceRetrievalEvidenceValidated,
                    followUpTaskId,
                    retrievedExperienceEvidenceCount,
                    solutionPlanEvidenceValidated,
                    solutionImplementationStepCount,
                    codingPromptReferencesSolutionPlan,
                    qaReportEvidenceValidated,
                    qaAcceptanceResultCount,
                    expectedProviderCount,
                    providerSecretEnvCount,
                    githubCodePlatformMode,
                    githubAuthMode,
                    githubCredentialEnvCount,
                    secretScanEvidenceValidated,
                    secretScannedValueCount,
                    secretScannedArtifactPreviewCount,
                    feishuAlertTypes,
                    feishuAlertMessageCount,
                    "",
                    feishuAlertMetadataComplete,
                    WorkflowRecoveryEvidence.empty(),
                    SkillPolicyEvidence.empty(),
                    RequirementReviewBlockerEvidence.empty(),
                    DockerCodingEvidence.empty(),
                    QaFailureBlockerEvidence.empty(),
                    DeliveryReviewFailureEvidence.empty(),
                    GitHubPrRemoteEvidence.empty(),
                    ObservabilityMetricsEvidence.empty(),
                    secretNeedles
            );
        }

        MinimumSmokeEvidence(
                String taskId,
                String taskStatus,
                String pullRequestUrl,
                String rdBotVersion,
                String environmentId,
                String executedBy,
                int timelineEvents,
                int stageEvents,
                Map<String, StageEvidence> stages,
                int roleContextPackageCount,
                boolean roleContextDifferent,
                boolean deliveryReviewApproved,
                boolean pullRequestPublicationSucceeded,
                boolean pullRequestEvidenceValidated,
                boolean pullRequestTargetAllowed,
                boolean pullRequestBodyEvidenceIncluded,
                int pullRequestQaAcceptanceResultCount,
                List<String> experienceTypes,
                int experienceSourceLinkCount,
                boolean experienceRetrievalEvidenceValidated,
                String followUpTaskId,
                int retrievedExperienceEvidenceCount,
                boolean solutionPlanEvidenceValidated,
                int solutionImplementationStepCount,
                boolean codingPromptReferencesSolutionPlan,
                boolean qaReportEvidenceValidated,
                int qaAcceptanceResultCount,
                int expectedProviderCount,
                int providerSecretEnvCount,
                String githubCodePlatformMode,
                String githubAuthMode,
                int githubCredentialEnvCount,
                boolean secretScanEvidenceValidated,
                int secretScannedValueCount,
                int secretScannedArtifactPreviewCount,
                List<String> feishuAlertTypes,
                int feishuAlertMessageCount,
                String feishuAlertTaskId,
                boolean feishuAlertMetadataComplete,
                List<String> secretNeedles
        ) {
            this(
                    taskId,
                    taskStatus,
                    pullRequestUrl,
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    timelineEvents,
                    stageEvents,
                    stages,
                    roleContextPackageCount,
                    roleContextDifferent,
                    deliveryReviewApproved,
                    pullRequestPublicationSucceeded,
                    pullRequestEvidenceValidated,
                    pullRequestTargetAllowed,
                    pullRequestBodyEvidenceIncluded,
                    pullRequestQaAcceptanceResultCount,
                    experienceTypes,
                    experienceSourceLinkCount,
                    experienceRetrievalEvidenceValidated,
                    followUpTaskId,
                    retrievedExperienceEvidenceCount,
                    solutionPlanEvidenceValidated,
                    solutionImplementationStepCount,
                    codingPromptReferencesSolutionPlan,
                    qaReportEvidenceValidated,
                    qaAcceptanceResultCount,
                    expectedProviderCount,
                    providerSecretEnvCount,
                    githubCodePlatformMode,
                    githubAuthMode,
                    githubCredentialEnvCount,
                    secretScanEvidenceValidated,
                    secretScannedValueCount,
                    secretScannedArtifactPreviewCount,
                    feishuAlertTypes,
                    feishuAlertMessageCount,
                    feishuAlertTaskId,
                    feishuAlertMetadataComplete,
                    WorkflowRecoveryEvidence.empty(),
                    SkillPolicyEvidence.empty(),
                    RequirementReviewBlockerEvidence.empty(),
                    DockerCodingEvidence.empty(),
                    QaFailureBlockerEvidence.empty(),
                    DeliveryReviewFailureEvidence.empty(),
                    GitHubPrRemoteEvidence.empty(),
                    ObservabilityMetricsEvidence.empty(),
                    secretNeedles
            );
        }

        MinimumSmokeEvidence(
                String taskId,
                String taskStatus,
                String pullRequestUrl,
                String rdBotVersion,
                String environmentId,
                String executedBy,
                int timelineEvents,
                int stageEvents,
                Map<String, StageEvidence> stages,
                int roleContextPackageCount,
                boolean roleContextDifferent,
                boolean deliveryReviewApproved,
                boolean pullRequestPublicationSucceeded,
                boolean pullRequestEvidenceValidated,
                boolean pullRequestTargetAllowed,
                boolean pullRequestBodyEvidenceIncluded,
                int pullRequestQaAcceptanceResultCount,
                List<String> experienceTypes,
                int experienceSourceLinkCount,
                boolean experienceRetrievalEvidenceValidated,
                String followUpTaskId,
                int retrievedExperienceEvidenceCount,
                boolean solutionPlanEvidenceValidated,
                int solutionImplementationStepCount,
                boolean codingPromptReferencesSolutionPlan,
                boolean qaReportEvidenceValidated,
                int qaAcceptanceResultCount,
                int expectedProviderCount,
                int providerSecretEnvCount,
                String githubCodePlatformMode,
                String githubAuthMode,
                int githubCredentialEnvCount,
                boolean secretScanEvidenceValidated,
                int secretScannedValueCount,
                int secretScannedArtifactPreviewCount,
                List<String> feishuAlertTypes,
                int feishuAlertMessageCount,
                String feishuAlertTaskId,
                boolean feishuAlertMetadataComplete,
                WorkflowRecoveryEvidence workflowRecoveryEvidence,
                SkillPolicyEvidence skillPolicyEvidence,
                RequirementReviewBlockerEvidence requirementReviewBlockerEvidence,
                DockerCodingEvidence dockerCodingEvidence,
                QaFailureBlockerEvidence qaFailureBlockerEvidence,
                DeliveryReviewFailureEvidence deliveryReviewFailureEvidence,
                GitHubPrRemoteEvidence githubPrRemoteEvidence,
                ObservabilityMetricsEvidence observabilityMetricsEvidence,
                List<String> secretNeedles
        ) {
            this(
                    taskId,
                    taskStatus,
                    pullRequestUrl,
                    rdBotVersion,
                    environmentId,
                    executedBy,
                    timelineEvents,
                    stageEvents,
                    stages,
                    roleContextPackageCount,
                    roleContextDifferent,
                    defaultRoleContextDistinctCount(roleContextPackageCount, roleContextDifferent),
                    deliveryReviewApproved,
                    pullRequestPublicationSucceeded,
                    pullRequestEvidenceValidated,
                    pullRequestTargetAllowed,
                    pullRequestBodyEvidenceIncluded,
                    pullRequestQaAcceptanceResultCount,
                    experienceTypes,
                    experienceSourceLinkCount,
                    experienceSourceLinkCount,
                    experienceSourceLinkCount,
                    experienceSourceLinkCount,
                    experienceRetrievalEvidenceValidated,
                    followUpTaskId,
                    retrievedExperienceEvidenceCount,
                    solutionPlanEvidenceValidated,
                    solutionImplementationStepCount,
                    defaultEvidenceCount(solutionPlanEvidenceValidated, solutionImplementationStepCount),
                    defaultEvidenceCount(solutionPlanEvidenceValidated, solutionImplementationStepCount),
                    defaultEvidenceCount(solutionPlanEvidenceValidated, solutionImplementationStepCount),
                    codingPromptReferencesSolutionPlan,
                    qaReportEvidenceValidated,
                    qaAcceptanceResultCount,
                    defaultEvidenceCount(qaReportEvidenceValidated, qaAcceptanceResultCount),
                    defaultEvidenceCount(qaReportEvidenceValidated, qaAcceptanceResultCount),
                    defaultEvidenceCount(qaReportEvidenceValidated, qaAcceptanceResultCount),
                    expectedProviderCount,
                    providerSecretEnvCount,
                    githubCodePlatformMode,
                    githubAuthMode,
                    githubCredentialEnvCount,
                    secretScanEvidenceValidated,
                    secretScannedValueCount,
                    secretScannedArtifactPreviewCount,
                    secretScanEvidenceValidated,
                    feishuAlertTypes,
                    feishuAlertMessageCount,
                    feishuAlertTaskId,
                    feishuAlertMetadataComplete,
                    workflowRecoveryEvidence,
                    skillPolicyEvidence,
                    requirementReviewBlockerEvidence,
                    dockerCodingEvidence,
                    qaFailureBlockerEvidence,
                    deliveryReviewFailureEvidence,
                    githubPrRemoteEvidence,
                    observabilityMetricsEvidence,
                    secretNeedles
            );
        }

        MinimumSmokeEvidence {
            taskId = oneLine(taskId);
            taskStatus = oneLine(taskStatus);
            pullRequestUrl = oneLine(pullRequestUrl);
            rdBotVersion = oneLine(rdBotVersion);
            environmentId = oneLine(environmentId);
            executedBy = oneLine(executedBy);
            stages = stages == null ? Map.of() : Map.copyOf(stages);
            roleContextDistinctCount = Math.max(roleContextDistinctCount, 0);
            experienceTypes = experienceTypes == null ? List.of() : List.copyOf(experienceTypes);
            experienceSourceLinkCount = Math.max(experienceSourceLinkCount, 0);
            experienceHashCount = Math.max(experienceHashCount, 0);
            experienceRedactedCount = Math.max(experienceRedactedCount, 0);
            experienceCompleteTypeCount = Math.max(experienceCompleteTypeCount, 0);
            solutionImplementationStepCount = Math.max(solutionImplementationStepCount, 0);
            solutionAffectedFileCount = Math.max(solutionAffectedFileCount, 0);
            solutionAcceptanceMappingCount = Math.max(solutionAcceptanceMappingCount, 0);
            solutionTestPlanStepCount = Math.max(solutionTestPlanStepCount, 0);
            qaAcceptanceResultCount = Math.max(qaAcceptanceResultCount, 0);
            qaPassedAcceptanceResultCount = Math.max(qaPassedAcceptanceResultCount, 0);
            qaValidationCommandCount = Math.max(qaValidationCommandCount, 0);
            qaValidationLogArtifactCount = Math.max(qaValidationLogArtifactCount, 0);
            expectedProviderCount = Math.max(expectedProviderCount, 0);
            providerSecretEnvCount = Math.max(providerSecretEnvCount, 0);
            githubCodePlatformMode = oneLine(githubCodePlatformMode);
            githubAuthMode = oneLine(githubAuthMode);
            githubCredentialEnvCount = Math.max(githubCredentialEnvCount, 0);
            pullRequestQaAcceptanceResultCount = Math.max(pullRequestQaAcceptanceResultCount, 0);
            followUpTaskId = oneLine(followUpTaskId);
            retrievedExperienceEvidenceCount = Math.max(retrievedExperienceEvidenceCount, 0);
            secretScannedValueCount = Math.max(secretScannedValueCount, 0);
            secretScannedArtifactPreviewCount = Math.max(secretScannedArtifactPreviewCount, 0);
            feishuAlertTypes = feishuAlertTypes == null ? List.of() : List.copyOf(feishuAlertTypes);
            feishuAlertMessageCount = Math.max(feishuAlertMessageCount, 0);
            feishuAlertTaskId = oneLine(feishuAlertTaskId);
            workflowRecoveryEvidence = workflowRecoveryEvidence == null
                    ? WorkflowRecoveryEvidence.empty()
                    : workflowRecoveryEvidence;
            skillPolicyEvidence = skillPolicyEvidence == null ? SkillPolicyEvidence.empty() : skillPolicyEvidence;
            requirementReviewBlockerEvidence = requirementReviewBlockerEvidence == null
                    ? RequirementReviewBlockerEvidence.empty()
                    : requirementReviewBlockerEvidence;
            dockerCodingEvidence = dockerCodingEvidence == null ? DockerCodingEvidence.empty() : dockerCodingEvidence;
            qaFailureBlockerEvidence = qaFailureBlockerEvidence == null
                    ? QaFailureBlockerEvidence.empty()
                    : qaFailureBlockerEvidence;
            deliveryReviewFailureEvidence = deliveryReviewFailureEvidence == null
                    ? DeliveryReviewFailureEvidence.empty()
                    : deliveryReviewFailureEvidence;
            githubPrRemoteEvidence = githubPrRemoteEvidence == null
                    ? GitHubPrRemoteEvidence.empty()
                    : githubPrRemoteEvidence;
            observabilityMetricsEvidence = observabilityMetricsEvidence == null
                    ? ObservabilityMetricsEvidence.empty()
                    : observabilityMetricsEvidence;
            secretNeedles = secretNeedles == null ? List.of() : List.copyOf(secretNeedles);
        }
    }

    record DockerCodingEvidence(
            boolean validated,
            String taskId,
            String stageRunId,
            String repositoryUrl,
            String dockerImage,
            String containerId,
            String workspacePath,
            String commitHash,
            String patchArtifactId,
            String resultArtifactId,
            String testLogArtifactId,
            String dockerMetadataArtifactId,
            String patchArtifactUri,
            String resultArtifactUri,
            String testLogArtifactUri,
            String dockerMetadataArtifactUri,
            int changedFileCount,
            String validationCommand,
            int validationExitCode,
            int testsRun,
            int testsFailed,
            boolean patchNonEmpty,
            boolean resultJsonValidated,
            boolean realDockerRun
    ) {
        DockerCodingEvidence {
            taskId = oneLine(taskId);
            stageRunId = oneLine(stageRunId);
            repositoryUrl = oneLine(repositoryUrl);
            dockerImage = oneLine(dockerImage);
            containerId = oneLine(containerId);
            workspacePath = oneLine(workspacePath);
            commitHash = oneLine(commitHash);
            patchArtifactId = oneLine(patchArtifactId);
            resultArtifactId = oneLine(resultArtifactId);
            testLogArtifactId = oneLine(testLogArtifactId);
            dockerMetadataArtifactId = oneLine(dockerMetadataArtifactId);
            patchArtifactUri = oneLine(patchArtifactUri);
            resultArtifactUri = oneLine(resultArtifactUri);
            testLogArtifactUri = oneLine(testLogArtifactUri);
            dockerMetadataArtifactUri = oneLine(dockerMetadataArtifactUri);
            changedFileCount = Math.max(changedFileCount, 0);
            validationCommand = oneLine(validationCommand);
            validationExitCode = Math.max(validationExitCode, 0);
            testsRun = Math.max(testsRun, 0);
            testsFailed = Math.max(testsFailed, 0);
        }

        DockerCodingEvidence(
                boolean validated,
                String taskId,
                String stageRunId,
                String repositoryUrl,
                String dockerImage,
                String containerId,
                String workspacePath,
                String commitHash,
                String patchArtifactId,
                String resultArtifactId,
                String testLogArtifactId,
                String dockerMetadataArtifactId,
                int changedFileCount,
                String validationCommand,
                int validationExitCode,
                int testsRun,
                int testsFailed,
                boolean patchNonEmpty,
                boolean resultJsonValidated,
                boolean realDockerRun
        ) {
            this(
                    validated,
                    taskId,
                    stageRunId,
                    repositoryUrl,
                    dockerImage,
                    containerId,
                    workspacePath,
                    commitHash,
                    patchArtifactId,
                    resultArtifactId,
                    testLogArtifactId,
                    dockerMetadataArtifactId,
                    "",
                    "",
                    "",
                    "",
                    changedFileCount,
                    validationCommand,
                    validationExitCode,
                    testsRun,
                    testsFailed,
                    patchNonEmpty,
                    resultJsonValidated,
                    realDockerRun
            );
        }

        static DockerCodingEvidence empty() {
            return new DockerCodingEvidence(
                    false,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    0,
                    "",
                    0,
                    0,
                    0,
                    false,
                    false,
                    false
            );
        }
    }

    record QaFailureBlockerEvidence(
            boolean validated,
            String taskId,
            String stageRunId,
            String taskStatus,
            String qaStageStatus,
            String executionResultStatus,
            String qaReportArtifactId,
            String qaReportArtifactUri,
            List<String> validationLogArtifactUris,
            int failedAcceptanceCount,
            int acceptanceResultCount,
            int validationCommandCount,
            int validationLogArtifactCount,
            boolean prCreated,
            boolean successReportCreated,
            boolean blockedBeforePrCreating,
            boolean feishuAlertDelivered,
            String feishuAlertMessageId
    ) {
        QaFailureBlockerEvidence {
            taskId = oneLine(taskId);
            stageRunId = oneLine(stageRunId);
            taskStatus = oneLine(taskStatus);
            qaStageStatus = oneLine(qaStageStatus);
            executionResultStatus = oneLine(executionResultStatus);
            qaReportArtifactId = oneLine(qaReportArtifactId);
            qaReportArtifactUri = oneLine(qaReportArtifactUri);
            validationLogArtifactUris = validationLogArtifactUris == null
                    ? List.of()
                    : validationLogArtifactUris.stream()
                    .map(MultiAgentProductionAcceptanceReport::oneLine)
                    .filter(value -> !value.isBlank())
                    .toList();
            failedAcceptanceCount = Math.max(failedAcceptanceCount, 0);
            acceptanceResultCount = Math.max(acceptanceResultCount, 0);
            validationCommandCount = Math.max(validationCommandCount, 0);
            validationLogArtifactCount = Math.max(validationLogArtifactCount, 0);
            feishuAlertMessageId = oneLine(feishuAlertMessageId);
        }

        QaFailureBlockerEvidence(
                boolean validated,
                String taskId,
                String stageRunId,
                String taskStatus,
                String qaStageStatus,
                String executionResultStatus,
                String qaReportArtifactId,
                int failedAcceptanceCount,
                int acceptanceResultCount,
                int validationCommandCount,
                int validationLogArtifactCount,
                boolean prCreated,
                boolean successReportCreated,
                boolean blockedBeforePrCreating,
                boolean feishuAlertDelivered,
                String feishuAlertMessageId
        ) {
            this(
                    validated,
                    taskId,
                    stageRunId,
                    taskStatus,
                    qaStageStatus,
                    executionResultStatus,
                    qaReportArtifactId,
                    "",
                    List.of(),
                    failedAcceptanceCount,
                    acceptanceResultCount,
                    validationCommandCount,
                    validationLogArtifactCount,
                    prCreated,
                    successReportCreated,
                    blockedBeforePrCreating,
                    feishuAlertDelivered,
                    feishuAlertMessageId
            );
        }

        static QaFailureBlockerEvidence empty() {
            return new QaFailureBlockerEvidence(
                    false,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    List.of(),
                    0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    false,
                    false,
                    ""
            );
        }
    }

    record DeliveryReviewFailureEvidence(
            boolean validated,
            String taskId,
            String taskStatus,
            boolean deliveryReviewApproved,
            String reviewDecision,
            String reviewer,
            String reviewArtifactId,
            String reviewArtifactUri,
            String rejectionReason,
            boolean pullRequestPublicationAttempted,
            boolean prCreated,
            boolean successReportCreated,
            boolean failureReportCreated,
            boolean successDeliveryReportExperienceCreated,
            boolean blockedBeforePrCreating,
            boolean stagePullRequestUrlRejected,
            boolean feishuAlertDelivered,
            String feishuAlertMessageId
    ) {
        DeliveryReviewFailureEvidence {
            taskId = oneLine(taskId);
            taskStatus = oneLine(taskStatus);
            reviewDecision = oneLine(reviewDecision);
            reviewer = oneLine(reviewer);
            reviewArtifactId = oneLine(reviewArtifactId);
            reviewArtifactUri = oneLine(reviewArtifactUri);
            rejectionReason = oneLine(rejectionReason);
            feishuAlertMessageId = oneLine(feishuAlertMessageId);
        }

        DeliveryReviewFailureEvidence(
                boolean validated,
                String taskId,
                String taskStatus,
                boolean deliveryReviewApproved,
                String reviewDecision,
                String reviewer,
                String reviewArtifactId,
                String rejectionReason,
                boolean pullRequestPublicationAttempted,
                boolean prCreated,
                boolean successReportCreated,
                boolean failureReportCreated,
                boolean successDeliveryReportExperienceCreated,
                boolean blockedBeforePrCreating,
                boolean stagePullRequestUrlRejected,
                boolean feishuAlertDelivered,
                String feishuAlertMessageId
        ) {
            this(
                    validated,
                    taskId,
                    taskStatus,
                    deliveryReviewApproved,
                    reviewDecision,
                    reviewer,
                    reviewArtifactId,
                    "",
                    rejectionReason,
                    pullRequestPublicationAttempted,
                    prCreated,
                    successReportCreated,
                    failureReportCreated,
                    successDeliveryReportExperienceCreated,
                    blockedBeforePrCreating,
                    stagePullRequestUrlRejected,
                    feishuAlertDelivered,
                    feishuAlertMessageId
            );
        }

        static DeliveryReviewFailureEvidence empty() {
            return new DeliveryReviewFailureEvidence(
                    false,
                    "",
                    "",
                    false,
                    "",
                    "",
                    "",
                    "",
                    "",
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    ""
            );
        }
    }

    record GitHubPrRemoteEvidence(
            boolean validated,
            String taskId,
            String pullRequestUrl,
            String pullRequestNumber,
            String baseBranch,
            String workBranch,
            boolean pullRequestBodyIncludesDeliveryReview,
            boolean pullRequestBodyIncludesQaEvidence,
            boolean pullRequestBodyContainsTaskId,
            boolean pullRequestBodyContainsArtifactLink,
            boolean secretScanEvidenceValidated,
            boolean secretLeakFound,
            int secretScannedValueCount
    ) {
        GitHubPrRemoteEvidence {
            taskId = oneLine(taskId);
            pullRequestUrl = oneLine(pullRequestUrl);
            pullRequestNumber = oneLine(pullRequestNumber);
            baseBranch = oneLine(baseBranch);
            workBranch = oneLine(workBranch);
            secretScannedValueCount = Math.max(secretScannedValueCount, 0);
        }

        static GitHubPrRemoteEvidence empty() {
            return new GitHubPrRemoteEvidence(
                    false,
                    "",
                    "",
                    "",
                    "",
                    "",
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    0
            );
        }
    }

    record ObservabilityMetricsEvidence(
            boolean validated,
            String taskId,
            String metricsEndpointUrl,
            int metricsHttpStatus,
            boolean contextBuildLatencyMetricPresent,
            boolean repairSuccessRateMetricPresent,
            boolean validationPassRateMetricPresent,
            boolean prCreationRateMetricPresent,
            boolean humanInterventionRateMetricPresent,
            boolean retryRateMetricPresent,
            boolean meanTimeToRepairMetricPresent,
            boolean topFailureCategoriesMetricPresent,
            int stageMetricCount,
            boolean auditTraceQuerySucceeded,
            boolean remotePrTraceValidated,
            int auditTraceLinkCount,
            int taskBoundAuditTraceLinkCount
    ) {
        ObservabilityMetricsEvidence {
            taskId = oneLine(taskId);
            metricsEndpointUrl = oneLine(metricsEndpointUrl);
            metricsHttpStatus = Math.max(metricsHttpStatus, 0);
            stageMetricCount = Math.max(stageMetricCount, 0);
            auditTraceLinkCount = Math.max(auditTraceLinkCount, 0);
            taskBoundAuditTraceLinkCount = Math.max(taskBoundAuditTraceLinkCount, 0);
        }

        static ObservabilityMetricsEvidence empty() {
            return new ObservabilityMetricsEvidence(
                    false,
                    "",
                    "",
                    0,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    0,
                    false,
                    false,
                    0,
                    0
            );
        }
    }

    record RequirementReviewBlockerEvidence(
            boolean validated,
            String taskId,
            String stageRunId,
            String taskStatus,
            String executionResultStatus,
            String reviewDecision,
            String reviewArtifactId,
            String reviewArtifactUri,
            String errorMessage,
            List<String> missingInformation,
            int pendingDownstreamRoleCount,
            boolean downstreamAgentsDispatched,
            boolean feishuAlertDelivered,
            String feishuAlertMessageId
    ) {
        RequirementReviewBlockerEvidence {
            taskId = oneLine(taskId);
            stageRunId = oneLine(stageRunId);
            taskStatus = oneLine(taskStatus);
            executionResultStatus = oneLine(executionResultStatus);
            reviewDecision = oneLine(reviewDecision);
            reviewArtifactId = oneLine(reviewArtifactId);
            reviewArtifactUri = oneLine(reviewArtifactUri);
            errorMessage = oneLine(errorMessage);
            missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
            pendingDownstreamRoleCount = Math.max(pendingDownstreamRoleCount, 0);
            feishuAlertMessageId = oneLine(feishuAlertMessageId);
        }

        RequirementReviewBlockerEvidence(
                boolean validated,
                String taskId,
                String stageRunId,
                String taskStatus,
                String executionResultStatus,
                String reviewDecision,
                String reviewArtifactId,
                String errorMessage,
                List<String> missingInformation,
                int pendingDownstreamRoleCount,
                boolean downstreamAgentsDispatched,
                boolean feishuAlertDelivered,
                String feishuAlertMessageId
        ) {
            this(
                    validated,
                    taskId,
                    stageRunId,
                    taskStatus,
                    executionResultStatus,
                    reviewDecision,
                    reviewArtifactId,
                    "",
                    errorMessage,
                    missingInformation,
                    pendingDownstreamRoleCount,
                    downstreamAgentsDispatched,
                    feishuAlertDelivered,
                    feishuAlertMessageId
            );
        }

        static RequirementReviewBlockerEvidence empty() {
            return new RequirementReviewBlockerEvidence(
                    false,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    List.of(),
                    0,
                    false,
                    false,
                    ""
            );
        }
    }

    record WorkflowRecoveryEvidence(
            boolean validated,
            String taskId,
            int stageRunCountBeforeRestart,
            int stageRunCountAfterRestart,
            int stageEventCountBeforeRestart,
            int stageEventCountAfterRestart,
            int duplicateSuccessfulStageCount,
            int retryAttemptCount,
            int retainedRetryArtifactCount,
            boolean deliveryReviewApprovedBeforePrCreating,
            List<String> timelineStatuses,
            String startupLogEvidenceUri,
            String databaseSnapshotEvidenceUri
    ) {
        WorkflowRecoveryEvidence {
            taskId = oneLine(taskId);
            stageRunCountBeforeRestart = Math.max(stageRunCountBeforeRestart, 0);
            stageRunCountAfterRestart = Math.max(stageRunCountAfterRestart, 0);
            stageEventCountBeforeRestart = Math.max(stageEventCountBeforeRestart, 0);
            stageEventCountAfterRestart = Math.max(stageEventCountAfterRestart, 0);
            duplicateSuccessfulStageCount = Math.max(duplicateSuccessfulStageCount, 0);
            retryAttemptCount = Math.max(retryAttemptCount, 0);
            retainedRetryArtifactCount = Math.max(retainedRetryArtifactCount, 0);
            timelineStatuses = timelineStatuses == null ? List.of() : List.copyOf(timelineStatuses);
            startupLogEvidenceUri = oneLine(startupLogEvidenceUri);
            databaseSnapshotEvidenceUri = oneLine(databaseSnapshotEvidenceUri);
        }

        static WorkflowRecoveryEvidence empty() {
            return new WorkflowRecoveryEvidence(
                    false,
                    "",
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    List.of(),
                    "",
                    ""
            );
        }
    }

    record SkillPolicyEvidence(
            boolean validated,
            String taskId,
            String stageRunId,
            String skillId,
            String skillVersion,
            String allowedRole,
            String rejectedRole,
            String highRiskRole,
            String installPath,
            String sourceChecksum,
            boolean installed,
            boolean unauthorizedRejected,
            boolean highRiskWaitingApproval,
            boolean metadataValidated,
            int installerCallCount
    ) {
        SkillPolicyEvidence {
            taskId = oneLine(taskId);
            stageRunId = oneLine(stageRunId);
            skillId = oneLine(skillId);
            skillVersion = oneLine(skillVersion);
            allowedRole = oneLine(allowedRole);
            rejectedRole = oneLine(rejectedRole);
            highRiskRole = oneLine(highRiskRole);
            installPath = oneLine(installPath);
            sourceChecksum = oneLine(sourceChecksum);
            installerCallCount = Math.max(installerCallCount, 0);
        }

        static SkillPolicyEvidence empty() {
            return new SkillPolicyEvidence(
                    false,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    false,
                    false,
                    false,
                    false,
                    0
            );
        }
    }

    record StageEvidence(
            String stageRunId,
            String promptArtifactId,
            String resultArtifactId,
            int stageArtifactCount,
            int stageArtifactPreviewCount,
            String status,
            String providerName,
            boolean providerAttemptsRecorded,
            int providerAttemptCount,
            boolean providerFallbackDetected,
            String failedProvider,
            String failedStatus,
            String activeProvider,
            boolean contextPackageRecorded
    ) {
        StageEvidence(
                String stageRunId,
                String promptArtifactId,
                String resultArtifactId,
                int stageArtifactCount,
                int stageArtifactPreviewCount,
                String status,
                String providerName,
                boolean providerAttemptsRecorded,
                int providerAttemptCount,
                boolean contextPackageRecorded
        ) {
            this(
                    stageRunId,
                    promptArtifactId,
                    resultArtifactId,
                    stageArtifactCount,
                    stageArtifactPreviewCount,
                    status,
                    providerName,
                    providerAttemptsRecorded,
                    providerAttemptCount,
                    false,
                    "",
                    "",
                    "",
                    contextPackageRecorded
            );
        }

        StageEvidence {
            stageRunId = oneLine(stageRunId);
            promptArtifactId = oneLine(promptArtifactId);
            resultArtifactId = oneLine(resultArtifactId);
            stageArtifactCount = Math.max(stageArtifactCount, 0);
            stageArtifactPreviewCount = Math.max(stageArtifactPreviewCount, 0);
            status = oneLine(status);
            providerName = oneLine(providerName);
            providerAttemptCount = Math.max(providerAttemptCount, 0);
            failedProvider = oneLine(failedProvider);
            failedStatus = oneLine(failedStatus);
            activeProvider = oneLine(activeProvider);
        }
    }

    private record AcceptancePoint(int number, String title) {
    }

    private record MatrixStatus(String status, String evidence) {
    }
}
