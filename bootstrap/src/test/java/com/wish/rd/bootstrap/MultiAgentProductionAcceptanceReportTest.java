package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAgentProductionAcceptanceReportTest {

    private static final String FULL_COMMIT_HASH = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path reportRoot;

    @Test
    void shouldWriteSkippedReportWithMissingProductionRequirements() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );

        Path reportPath = report.writeSkipped(List.of(
                "rd.multi-agent.smoke.base-url",
                "rd.multi-agent.smoke.postgres-password"
        ));

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("# RD-Bot 多 Agent RAG 编排生产验收报告"));
        assertTrue(markdown.contains("验收时间：2026-07-01T08:00:00Z"));
        assertTrue(markdown.contains("结论：SKIPPED"));
        assertTrue(markdown.contains("rd.multi-agent.smoke.base-url"));
        assertTrue(markdown.contains("rd.multi-agent.smoke.postgres-password"));
        assertTrue(markdown.contains("下一步补验命令"));
        assertTrue(markdown.contains("MultiAgentRequirementDeliveryRealSmokeTest"));
        assertTrue(markdown.contains(
                "-Drd.multi-agent.smoke.github-pr-remote-evidence-json=<github-pr-remote-evidence-json>"
        ));
        assertTrue(markdown.contains("-Drd.multi-agent.smoke.secret-scan-needles=<secret-scan-needles>"));
        assertTrue(markdown.contains("| 15 | 生产真实测试结论要求 | NOT_RUN |"));

        String json = Files.readString(jsonPath(reportPath));
        assertTrue(json.contains("\"conclusion\":\"SKIPPED\""));
        assertTrue(json.contains("\"multiAgentProductionEvidenceValidated\":false"));
        assertTrue(json.contains("\"missingRequirements\""));
        assertTrue(json.contains("rd.multi-agent.smoke.base-url"));
        assertTrue(json.contains("rd.multi-agent.smoke.postgres-password"));
    }

    @Test
    void shouldResolveRelativeReportDirectoryAgainstRepositoryRoot() {
        Path resolved = MultiAgentProductionAcceptanceReport.resolveReportRoot(
                "qa-runs/multi-agent-production-acceptance"
        );

        assertTrue(resolved.endsWith("qa-runs/multi-agent-production-acceptance"));
        assertTrue(Files.exists(resolved.getParent().getParent().resolve(".git")));
        assertFalse(resolved.toString().contains("/bootstrap/qa-runs/"));
    }

    @Test
    void shouldWriteMinimumSmokeReportWithoutSecretNeedleValues() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of("secret-value")
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("结论：PASSED_MINIMUM_SMOKE"));
        assertTrue(markdown.contains("验收时间：2026-07-01T08:00:00Z"));
        assertTrue(markdown.contains("RD-Bot 版本：0.1.0-smoke"));
        assertTrue(markdown.contains("生产环境标识：prod-equivalent-a"));
        assertTrue(markdown.contains("执行人：qa-runner"));
        assertTrue(markdown.contains("taskId：123456"));
        assertTrue(markdown.contains("PR：https://github.com/acme/rd-bot-smoke/pull/7"));
        assertTrue(markdown.contains("stageEvents：12"));
        assertTrue(markdown.contains("deliveryReviewApproved：true"));
        assertTrue(markdown.contains("pullRequestPublicationSucceeded：true"));
        assertTrue(markdown.contains("roleContextDistinctCount：4"));
        assertTrue(markdown.contains("pullRequestEvidenceValidated：true"));
        assertTrue(markdown.contains("pullRequestTargetAllowed：true"));
        assertTrue(markdown.contains("pullRequestQaAcceptanceResultCount：1"));
        assertTrue(markdown.contains("experienceSourceLinkCount：5"));
        assertTrue(markdown.contains("experienceHashCount：5"));
        assertTrue(markdown.contains("experienceRedactedCount：5"));
        assertTrue(markdown.contains("experienceCompleteTypeCount：5"));
        assertTrue(markdown.contains("experienceRetrievalEvidenceValidated：true"));
        assertTrue(markdown.contains("followUpTaskId：789012"));
        assertTrue(markdown.contains("retrievedExperienceEvidenceCount：4"));
        assertTrue(markdown.contains("solutionPlanEvidenceValidated：true"));
        assertTrue(markdown.contains("solutionImplementationStepCount：1"));
        assertTrue(markdown.contains("solutionAffectedFileCount：1"));
        assertTrue(markdown.contains("solutionAcceptanceMappingCount：1"));
        assertTrue(markdown.contains("solutionTestPlanStepCount：1"));
        assertTrue(markdown.contains("codingPromptReferencesSolutionPlan：true"));
        assertTrue(markdown.contains("qaReportEvidenceValidated：true"));
        assertTrue(markdown.contains("qaAcceptanceResultCount：1"));
        assertTrue(markdown.contains("qaPassedAcceptanceResultCount：1"));
        assertTrue(markdown.contains("qaValidationCommandCount：1"));
        assertTrue(markdown.contains("qaValidationLogArtifactCount：1"));
        assertTrue(markdown.contains("providerSecretEnvCount：2"));
        assertTrue(markdown.contains("githubCodePlatformMode：real"));
        assertTrue(markdown.contains("githubAuthMode：PAT_LOCAL_SMOKE"));
        assertTrue(markdown.contains("githubCredentialEnvCount：1"));
        assertTrue(markdown.contains("secretScanEvidenceValidated：true"));
        assertTrue(markdown.contains("secretScannedValueCount：8"));
        assertTrue(markdown.contains("secretScannedArtifactPreviewCount：8"));
        assertTrue(markdown.contains("secretScannedPullRequestMetadata：true"));
        assertTrue(markdown.contains("auditChainEvidenceValidated：true"));
        assertTrue(markdown.contains("auditArtifactLinkCount：8"));
        assertTrue(markdown.contains("auditExperienceLinkCount：5"));
        assertTrue(markdown.contains("auditPullRequestTraceValidated：true"));
        assertTrue(markdown.contains("REQUIREMENT_REVIEWER"));
        assertTrue(markdown.contains("stageRunId"));
        assertTrue(markdown.contains("stage-requirement-reviewer"));
        assertTrue(markdown.contains("prompt-requirement-reviewer"));
        assertTrue(markdown.contains("result-requirement-reviewer"));
        assertTrue(markdown.contains("stageArtifactCount=2"));
        assertTrue(markdown.contains("stageArtifactPreviewCount=2"));
        assertTrue(markdown.contains("providerAttemptsRecorded=true"));
        assertTrue(markdown.contains("providerAttemptCount=2"));
        assertTrue(markdown.contains("| 1 | 需求任务可进入多 Agent 工作流 | PASSED | taskId=123456"));
        assertTrue(markdown.contains("| 2 | 角色上下文包真实落库且内容不同 | PASSED | taskId=123456"));
        assertTrue(markdown.contains("| 4 | 方案 Agent 产出可执行开发方案 | PASSED | taskId=123456"));
        assertTrue(markdown.contains("| 7 | QA Agent 逐条验收并阻断失败交付 | NOT_RUN |"));
        assertTrue(markdown.contains("QA failure blocker JSON sidecar"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | NOT_RUN |"));
        assertTrue(markdown.contains("Delivery review failure JSON sidecar"));
        assertTrue(markdown.contains("| 12 | 经验自动沉淀且可被后续 RAG 检索 | PASSED | taskId=123456"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
        assertTrue(markdown.contains("GitHub PR 远端反查 JSON sidecar"));
        assertTrue(markdown.contains("| 14 | 指标和审计可观测 | NOT_RUN |"));
        assertTrue(markdown.contains("Observability metrics JSON sidecar"));
        assertTrue(markdown.contains("| 15 | 生产真实测试结论要求 | NOT_RUN |"));
        assertTrue(markdown.contains("必须先完成 #1-#14 全部生产验收"));
        assertTrue(markdown.contains("| 5 | 多 provider 降级重试真实生效 | NOT_RUN |"));
        assertTrue(markdown.contains("需要提供同版本、同环境、同执行人的 Feishu alert JSON sidecar"));
        assertTrue(markdown.contains("feishuAlertTaskId 等于主任务"));
        assertUsesOnlyFinalAcceptanceStatuses(markdown);
        assertFalse(markdown.contains("secret-value"));
    }

    @Test
    void shouldAcceptSuccessfulStagesThatOnlyUseFirstHealthyProvider() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStagesWithProviderAttemptCount(1),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of("secret-value")
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("结论：PASSED_MINIMUM_SMOKE"));
        assertTrue(markdown.contains("providerAttemptCount=1"));
    }

    @Test
    void shouldAcceptGhCliSmokeWithoutCredentialEnvironmentVariables() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence = withGithubAuthMode(
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of("secret-value")
                ),
                "GH_CLI_LOCAL_SMOKE",
                0
        );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("githubAuthMode：GH_CLI_LOCAL_SMOKE"));
        assertTrue(markdown.contains("githubCredentialEnvCount：0"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutTraceableEvidence() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "",
                        "COMPLETED",
                        "",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("taskId"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportUnlessWorkflowCompleted() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "VALIDATING",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("taskStatus"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportUnlessAllExpectedRolesSucceeded() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        Map.of(
                                "REQUIREMENT_REVIEWER", stage("SUCCEEDED", "provider-a", true),
                                "SOLUTION_ARCHITECT", stage("FAILED", "provider-b", true),
                                "CODING_AGENT", stage("SUCCEEDED", "provider-b", true),
                                "QA_AGENT", stage("SUCCEEDED", "provider-b", true)
                        ),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("SOLUTION_ARCHITECT"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutStageRunIds() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStagesWithoutStageRunIds(),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("stageRunId"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutStageArtifacts() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStagesWithoutArtifactRows(),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("stageArtifacts"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutCompleteAuditChain() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        1,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("auditChainEvidence"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutAuditableStageArtifactPreviews() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStagesWithoutArtifactPreviews(),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("stageArtifactPreviews"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutDistinctRoleContextPackages() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        3,
                        false,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("roleContextPackages"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutUniqueContextForEveryRole() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithRoleContextAndExperienceCounts(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        2,
                        true,
                        true,
                        5,
                        5,
                        5,
                        true,
                        1,
                        true,
                        true,
                        1,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("roleContextDistinctCount"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutDeliveryReviewAndPrPublication() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        false,
                        false,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("deliveryReviewApproved"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutExperienceSourceLinks() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        0,
                        true,
                        1,
                        true,
                        true,
                        1,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("experienceSourceLinkCount"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutExperienceHashAndRedactionEvidence() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithExperienceStorageCounts(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        5,
                        0,
                        0,
                        true,
                        1,
                        true,
                        true,
                        1,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("experienceHashCount"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWhenExperienceSourceLinksDoNotCoverEveryRequiredType() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithExperienceStorageCounts(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        4,
                        5,
                        5,
                        true,
                        1,
                        true,
                        true,
                        1,
                        2,
                        2,
                        "real",
                        1,
                        List.of("secret-value")
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("experienceSourceLinkCount"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWhenExperienceCompleteTypeCoverageIsMissing() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithExperienceCompleteTypeCount(0);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("experienceCompleteTypeCount"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWhenFollowUpTaskReusesMainTask() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence = withFollowUpTaskId(
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        5,
                        true,
                        1,
                        true,
                        true,
                        1,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                ),
                "123456"
        );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("followUpTaskId"));
        assertTrue(failure.getMessage().contains("different from taskId"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutSolutionPlanEvidence() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        5,
                        false,
                        0,
                        false,
                        true,
                        1,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("solutionPlanEvidenceValidated"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutQaReportEvidence() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        5,
                        true,
                        1,
                        true,
                        false,
                        0,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("qaReportEvidenceValidated"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutDetailedSolutionPlanEvidence() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithSolutionAndQaEvidenceCounts(
                        0,
                        1,
                        1,
                        1,
                        1,
                        1
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("solutionAffectedFileCount"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutExecutableQaAcceptanceEvidence() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithSolutionAndQaEvidenceCounts(
                        1,
                        1,
                        1,
                        2,
                        2,
                        1
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("qaValidationLogArtifactCount"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutConfiguredSecretNeedles() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                withSecretNeedles(minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        5,
                        true,
                        1,
                        true,
                        true,
                        1,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                ), List.of());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("secretNeedles"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWhenPullRequestMetadataWasNotSecretScanned() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithSecretScannedPullRequestMetadata(false);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("secretScannedPullRequestMetadata"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutRealGithubPublicationEvidence() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "fake",
                        0,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("githubCodePlatformMode"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutProviderAttemptEvidenceForEachRole() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        Map.of(
                                "REQUIREMENT_REVIEWER", stage("SUCCEEDED", "provider-a", true),
                                "SOLUTION_ARCHITECT", stage("SUCCEEDED", "provider-b", true),
                                "CODING_AGENT", stage("SUCCEEDED", "provider-b", false),
                                "QA_AGENT", stage("SUCCEEDED", "provider-b", true)
                        ),
                        4,
                        true,
                        true,
                        true,
                        2,
                        2,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("CODING_AGENT"));
    }

    @Test
    void shouldRejectPassedMinimumSmokeReportWithoutTwoConfiguredProviders() {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        2,
                        1,
                        "real",
                        1,
                        List.of()
                );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> report.writePassedMinimumSmoke(evidence)
        );

        assertTrue(failure.getMessage().contains("providerSecretEnvCount"));
    }

    @Test
    void shouldMarkFeishuAlertAcceptancePassedWhenSixRealAlertTypesAreDelivered() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithFeishuAlerts(List.of(
                        "STAGE_FAILED_RETRYABLE",
                        "STAGE_FAILED_NEEDS_HUMAN",
                        "PROVIDER_FALLBACK",
                        "QA_FAILED",
                        "DELIVERY_REVIEW_FAILED",
                        "PR_PUBLICATION_FAILED"
                ), 6);

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("feishuAlertEvidenceValidated：true"));
        assertTrue(markdown.contains("feishuAlertMessageCount：6"));
        assertTrue(markdown.contains("feishuAlertTaskId：123456"));
        assertTrue(markdown.contains("PROVIDER_FALLBACK"));
        assertTrue(markdown.contains("| 10 | 错误通知真实送达 Feishu | PASSED |"));
    }

    @Test
    void shouldNotMarkFeishuAlertAcceptancePassedWhenAlertsBelongToAnotherTask() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithFeishuAlerts(List.of(
                        "STAGE_FAILED_RETRYABLE",
                        "STAGE_FAILED_NEEDS_HUMAN",
                        "PROVIDER_FALLBACK",
                        "QA_FAILED",
                        "DELIVERY_REVIEW_FAILED",
                        "PR_PUBLICATION_FAILED"
                ), 6, "another-task");

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("feishuAlertEvidenceValidated：false"));
        assertTrue(markdown.contains("feishuAlertTaskId：another-task"));
        assertTrue(markdown.contains("| 10 | 错误通知真实送达 Feishu | NOT_RUN |"));
    }

    @Test
    void shouldMarkProviderFallbackAcceptancePassedWhenAttemptsShowFailureThenSuccessAndFeishuAlertExists()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages = new java.util.LinkedHashMap<>(
                allSucceededStages()
        );
        stages.put("CODING_AGENT", stageWithProviderFallback(
                "stage-coding-agent",
                "provider-b",
                "provider-a",
                "FAILED_VALIDATION"
        ));
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithStagesAndFeishuAlerts(stages, List.of(
                        "STAGE_FAILED_RETRYABLE",
                        "STAGE_FAILED_NEEDS_HUMAN",
                        "PROVIDER_FALLBACK",
                        "QA_FAILED",
                        "DELIVERY_REVIEW_FAILED",
                        "PR_PUBLICATION_FAILED"
                ), 6);

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("providerFallbackEvidenceValidated：true"));
        assertTrue(markdown.contains("failedProvider=provider-a"));
        assertTrue(markdown.contains("activeProvider=provider-b"));
        assertTrue(markdown.contains("| 5 | 多 provider 降级重试真实生效 | PASSED |"));
    }

    @Test
    void shouldNotMarkProviderFallbackAcceptancePassedWhenFallbackAttemptDetailsAreMissing()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages = new java.util.LinkedHashMap<>(
                allSucceededStages()
        );
        stages.put("CODING_AGENT", new MultiAgentProductionAcceptanceReport.StageEvidence(
                "stage-coding-agent",
                "prompt-coding-agent",
                "result-coding-agent",
                2,
                2,
                "SUCCEEDED",
                "provider-b",
                true,
                2,
                true,
                "",
                "",
                "",
                true
        ));
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithStagesAndFeishuAlerts(stages, List.of(
                        "STAGE_FAILED_RETRYABLE",
                        "STAGE_FAILED_NEEDS_HUMAN",
                        "PROVIDER_FALLBACK",
                        "QA_FAILED",
                        "DELIVERY_REVIEW_FAILED",
                        "PR_PUBLICATION_FAILED"
                ), 6);

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("providerFallbackEvidenceValidated：false"));
        assertTrue(markdown.contains("| 5 | 多 provider 降级重试真实生效 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkProviderFallbackAcceptancePassedWhenProviderOnlyDiffersByCase()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages = new java.util.LinkedHashMap<>(
                allSucceededStages()
        );
        stages.put("CODING_AGENT", stageWithProviderFallback(
                "stage-coding-agent",
                "provider-a",
                "Provider-A",
                "FAILED_VALIDATION"
        ));
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithStagesAndFeishuAlerts(stages, List.of(
                        "STAGE_FAILED_RETRYABLE",
                        "STAGE_FAILED_NEEDS_HUMAN",
                        "PROVIDER_FALLBACK",
                        "QA_FAILED",
                        "DELIVERY_REVIEW_FAILED",
                        "PR_PUBLICATION_FAILED"
                ), 6);

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("providerFallbackEvidenceValidated：false"));
        assertTrue(markdown.contains("| 5 | 多 provider 降级重试真实生效 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkProviderFallbackAcceptancePassedWhenFeishuFallbackAlertMetadataIsIncomplete()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages = new java.util.LinkedHashMap<>(
                allSucceededStages()
        );
        stages.put("CODING_AGENT", stageWithProviderFallback(
                "stage-coding-agent",
                "provider-b",
                "provider-a",
                "FAILED_VALIDATION"
        ));
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithStagesAndFeishuAlerts(stages, List.of(
                        "STAGE_FAILED_RETRYABLE",
                        "STAGE_FAILED_NEEDS_HUMAN",
                        "PROVIDER_FALLBACK",
                        "QA_FAILED",
                        "DELIVERY_REVIEW_FAILED",
                        "PR_PUBLICATION_FAILED"
                ), 6, false);

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("providerFallbackEvidenceValidated：false"));
        assertTrue(markdown.contains("feishuAlertMetadataComplete：false"));
        assertTrue(markdown.contains("| 5 | 多 provider 降级重试真实生效 | NOT_RUN |"));
    }

    @Test
    void shouldMarkWorkflowRecoveryAcceptancePassedWhenRecoveryEvidenceIsValidated() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithWorkflowRecovery(new MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence(
                        true,
                        "123456",
                        2,
                        4,
                        5,
                        14,
                        0,
                        1,
                        2,
                        true,
                        List.of(
                                "EXECUTING",
                                "VALIDATING",
                                "PR_CREATING",
                                "COMMITTED",
                                "REPORTING",
                                "COMPLETED"
                        ),
                        "s3://rd-bot-qa/startup.log",
                        "s3://rd-bot-qa/recovery-snapshots.json"
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("workflowRecoveryEvidenceValidated：true"));
        assertTrue(markdown.contains("recoveryTaskId：123456"));
        assertTrue(markdown.contains("recoveryStageRunCountBeforeRestart：2"));
        assertTrue(markdown.contains("recoveryStageRunCountAfterRestart：4"));
        assertTrue(markdown.contains("duplicateSuccessfulStageCount：0"));
        assertTrue(markdown.contains("retryAttemptCount：1"));
        assertTrue(markdown.contains("retainedRetryArtifactCount：2"));
        assertTrue(markdown.contains("deliveryReviewApprovedBeforePrCreating：true"));
        assertTrue(markdown.contains("EXECUTING -> VALIDATING -> PR_CREATING -> COMMITTED -> REPORTING -> COMPLETED"));
        assertTrue(markdown.contains("| 9 | 状态机可恢复且不会重复派发 | PASSED |"));
    }

    @Test
    void shouldNotMarkWorkflowRecoveryAcceptancePassedWhenRecoveryEvidenceBelongsToAnotherTask() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithWorkflowRecovery(new MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence(
                        true,
                        "another-task",
                        2,
                        4,
                        5,
                        14,
                        0,
                        1,
                        2,
                        true,
                        List.of(
                                "EXECUTING",
                                "VALIDATING",
                                "PR_CREATING",
                                "COMMITTED",
                                "REPORTING",
                                "COMPLETED"
                        ),
                        "s3://rd-bot-qa/startup.log",
                        "s3://rd-bot-qa/recovery-snapshots.json"
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("workflowRecoveryEvidenceValidated：false"));
        assertTrue(markdown.contains("recoveryTaskId：another-task"));
        assertTrue(markdown.contains("| 9 | 状态机可恢复且不会重复派发 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkWorkflowRecoveryAcceptancePassedWhenSuccessfulStagesWereDuplicated()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithWorkflowRecovery(new MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence(
                        true,
                        "123456",
                        2,
                        4,
                        5,
                        14,
                        1,
                        1,
                        2,
                        true,
                        List.of(
                                "EXECUTING",
                                "VALIDATING",
                                "PR_CREATING",
                                "COMMITTED",
                                "REPORTING",
                                "COMPLETED"
                        ),
                        "s3://rd-bot-qa/startup.log",
                        "s3://rd-bot-qa/recovery-snapshots.json"
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("workflowRecoveryEvidenceValidated：false"));
        assertTrue(markdown.contains("duplicateSuccessfulStageCount：1"));
        assertTrue(markdown.contains("| 9 | 状态机可恢复且不会重复派发 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkWorkflowRecoveryAcceptancePassedWhenEvidenceUrisAreNotProductionArtifacts()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithWorkflowRecovery(new MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence(
                        true,
                        "123456",
                        2,
                        4,
                        5,
                        14,
                        0,
                        1,
                        2,
                        true,
                        List.of(
                                "EXECUTING",
                                "VALIDATING",
                                "PR_CREATING",
                                "COMMITTED",
                                "REPORTING",
                                "COMPLETED"
                        ),
                        "mock://rd-bot-qa/startup.log",
                        "recovery-snapshots.json"
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("workflowRecoveryEvidenceValidated：false"));
        assertTrue(markdown.contains("recoveryStartupLogEvidenceUri：mock://rd-bot-qa/startup.log"));
        assertTrue(markdown.contains("| 9 | 状态机可恢复且不会重复派发 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkWorkflowRecoveryAcceptancePassedWhenEvidenceUrisAreLocalFiles()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithWorkflowRecovery(new MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence(
                        true,
                        "123456",
                        2,
                        4,
                        5,
                        14,
                        0,
                        1,
                        2,
                        true,
                        List.of(
                                "EXECUTING",
                                "VALIDATING",
                                "PR_CREATING",
                                "COMMITTED",
                                "REPORTING",
                                "COMPLETED"
                        ),
                        "file:///tmp/rd-bot/startup.log",
                        "file:///tmp/rd-bot/recovery-snapshots.json"
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("workflowRecoveryEvidenceValidated：false"));
        assertTrue(markdown.contains("recoveryStartupLogEvidenceUri：file:///tmp/rd-bot/startup.log"));
        assertTrue(markdown.contains("| 9 | 状态机可恢复且不会重复派发 | NOT_RUN |"));
    }

    @Test
    void shouldMarkSkillPolicyAcceptancePassedWhenSkillPolicyEvidenceIsValidated() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithSkillPolicy(new MultiAgentProductionAcceptanceReport.SkillPolicyEvidence(
                        true,
                        "123456",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/qa-real-runner/v1",
                        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        true,
                        true,
                        true,
                        true,
                        1
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("skillPolicyEvidenceValidated：true"));
        assertTrue(markdown.contains("skillPolicyTaskId：123456"));
        assertTrue(markdown.contains("skillPolicyStageRunId：stage-skill-smoke"));
        assertTrue(markdown.contains("skillPolicySkillId：qa-real-runner"));
        assertTrue(markdown.contains("skillPolicyInstalled：true"));
        assertTrue(markdown.contains("skillPolicyUnauthorizedRejected：true"));
        assertTrue(markdown.contains("skillPolicyHighRiskWaitingApproval：true"));
        assertTrue(markdown.contains("skillPolicyInstallerCallCount：1"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | PASSED |"));
    }

    @Test
    void shouldNotMarkSkillPolicyAcceptancePassedWhenSourceChecksumIsNotFullSha256() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithSkillPolicy(new MultiAgentProductionAcceptanceReport.SkillPolicyEvidence(
                        true,
                        "123456",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/qa-real-runner/v1",
                        "sha256:abc123",
                        true,
                        true,
                        true,
                        true,
                        1
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("skillPolicyEvidenceValidated：false"));
        assertTrue(markdown.contains("skillPolicySourceChecksum：sha256:abc123"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkSkillPolicyAcceptancePassedWhenInstallPathDoesNotMatchSkillIdentity() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithSkillPolicy(new MultiAgentProductionAcceptanceReport.SkillPolicyEvidence(
                        true,
                        "123456",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/other-skill/v1",
                        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        true,
                        true,
                        true,
                        true,
                        1
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("skillPolicyEvidenceValidated：false"));
        assertTrue(markdown.contains("skillPolicyInstallPath：/opt/rd-bot/skills/other-skill/v1"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkSkillPolicyAcceptancePassedWhenInstallPathIsRelative() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithSkillPolicy(new MultiAgentProductionAcceptanceReport.SkillPolicyEvidence(
                        true,
                        "123456",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "qa-real-runner/v1",
                        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        true,
                        true,
                        true,
                        true,
                        1
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("skillPolicyEvidenceValidated：false"));
        assertTrue(markdown.contains("skillPolicyInstallPath：qa-real-runner/v1"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkSkillPolicyAcceptancePassedWhenRoleBoundariesAreNotDistinct() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithSkillPolicy(new MultiAgentProductionAcceptanceReport.SkillPolicyEvidence(
                        true,
                        "123456",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "QA_AGENT",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/qa-real-runner/v1",
                        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        true,
                        true,
                        true,
                        true,
                        1
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("skillPolicyEvidenceValidated：false"));
        assertTrue(markdown.contains("skillPolicyAllowedRole：QA_AGENT"));
        assertTrue(markdown.contains("skillPolicyRejectedRole：QA_AGENT"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkSkillPolicyAcceptancePassedWhenPolicyOutcomesAreIncomplete() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithSkillPolicy(new MultiAgentProductionAcceptanceReport.SkillPolicyEvidence(
                        true,
                        "123456",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/qa-real-runner/v1",
                        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        false,
                        true,
                        true,
                        true,
                        1
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("skillPolicyEvidenceValidated：false"));
        assertTrue(markdown.contains("skillPolicyInstalled：false"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkSkillPolicyAcceptancePassedWhenSkillPolicyEvidenceBelongsToAnotherTask()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithSkillPolicy(new MultiAgentProductionAcceptanceReport.SkillPolicyEvidence(
                        true,
                        "another-task",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/qa-real-runner/v1",
                        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        true,
                        true,
                        true,
                        true,
                        1
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("skillPolicyEvidenceValidated：false"));
        assertTrue(markdown.contains("skillPolicyTaskId：another-task"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | NOT_RUN |"));
    }

    @Test
    void shouldMarkRequirementReviewBlockerAcceptancePassedWhenBlockerEvidenceIsValidated() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithRequirementReviewBlocker(
                        new MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence(
                                true,
                                "123456",
                                "stage-reviewer",
                                "FAILED_NEEDS_HUMAN",
                                "NEEDS_HUMAN",
                                "NEED_INFO",
                                "artifact-review-json",
                                "s3://rd-bot-review/requirement-review/blocker.json",
                                "缺少业务边界和验收命令",
                                List.of("业务边界", "验收命令"),
                                3,
                                false,
                                true,
                                "om-review-blocked"
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("requirementReviewBlockerEvidenceValidated：true"));
        assertTrue(markdown.contains("requirementReviewBlockerTaskId：123456"));
        assertTrue(markdown.contains("requirementReviewBlockerStageRunId：stage-reviewer"));
        assertTrue(markdown.contains("requirementReviewBlockerDecision：NEED_INFO"));
        assertTrue(markdown.contains("requirementReviewBlockerMissingInformation：业务边界, 验收命令"));
        assertTrue(markdown.contains("requirementReviewBlockerPendingDownstreamRoleCount：3"));
        assertTrue(markdown.contains("requirementReviewBlockerFeishuAlertDelivered：true"));
        assertTrue(markdown.contains("| 3 | 需求评审 Agent 能阻断不可交付需求 | PASSED |"));
    }

    @Test
    void shouldNotMarkRequirementReviewBlockerPassedWhenBlockerEvidenceBelongsToAnotherTask()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithRequirementReviewBlocker(
                        new MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence(
                                true,
                                "another-task",
                                "stage-reviewer",
                                "FAILED_NEEDS_HUMAN",
                                "NEEDS_HUMAN",
                                "NEED_INFO",
                                "artifact-review-json",
                                "s3://rd-bot-review/requirement-review/blocker.json",
                                "缺少业务边界和验收命令",
                                List.of("业务边界", "验收命令"),
                                3,
                                false,
                                true,
                                "om-review-blocked"
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("requirementReviewBlockerEvidenceValidated：false"));
        assertTrue(markdown.contains("requirementReviewBlockerTaskId：another-task"));
        assertTrue(markdown.contains("| 3 | 需求评审 Agent 能阻断不可交付需求 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkRequirementReviewBlockerPassedWhenDownstreamAgentsWereDispatched()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithRequirementReviewBlocker(
                        new MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence(
                                true,
                                "123456",
                                "stage-reviewer",
                                "FAILED_NEEDS_HUMAN",
                                "NEEDS_HUMAN",
                                "NEED_INFO",
                                "artifact-review-json",
                                "s3://rd-bot-review/requirement-review/blocker.json",
                                "缺少业务边界和验收命令",
                                List.of("业务边界", "验收命令"),
                                3,
                                true,
                                true,
                                "om-review-blocked"
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("requirementReviewBlockerEvidenceValidated：false"));
        assertTrue(markdown.contains("requirementReviewBlockerDownstreamAgentsDispatched：true"));
        assertTrue(markdown.contains("| 3 | 需求评审 Agent 能阻断不可交付需求 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkRequirementReviewBlockerPassedWithoutDurableReviewArtifactUri() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithRequirementReviewBlocker(
                        new MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence(
                                true,
                                "123456",
                                "stage-reviewer",
                                "FAILED_NEEDS_HUMAN",
                                "NEEDS_HUMAN",
                                "NEED_INFO",
                                "artifact-review-json",
                                "缺少业务边界和验收命令",
                                List.of("业务边界", "验收命令"),
                                3,
                                false,
                                true,
                                "om-review-blocked"
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("requirementReviewBlockerEvidenceValidated：false"));
        assertTrue(markdown.contains("requirementReviewBlockerReviewArtifactId：artifact-review-json"));
        assertTrue(markdown.contains("| 3 | 需求评审 Agent 能阻断不可交付需求 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkRequirementReviewBlockerPassedWithLocalFileReviewArtifactUri() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithRequirementReviewBlocker(
                        new MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence(
                                true,
                                "123456",
                                "stage-reviewer",
                                "FAILED_NEEDS_HUMAN",
                                "NEEDS_HUMAN",
                                "NEED_INFO",
                                "artifact-review-json",
                                "file:///tmp/rd-bot/requirement-review/blocker.json",
                                "缺少业务边界和验收命令",
                                List.of("业务边界", "验收命令"),
                                3,
                                false,
                                true,
                                "om-review-blocked"
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("requirementReviewBlockerEvidenceValidated：false"));
        assertTrue(markdown.contains("requirementReviewBlockerReviewArtifactUri：file:///tmp/rd-bot/requirement-review/blocker.json"));
        assertTrue(markdown.contains("| 3 | 需求评审 Agent 能阻断不可交付需求 | NOT_RUN |"));
    }

    @Test
    void shouldMarkDockerCodingAcceptancePassedWhenDockerEvidenceIsValidated() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDockerCoding(dockerCodingEvidence("123456", FULL_COMMIT_HASH, true));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("dockerCodingEvidenceValidated：true"));
        assertTrue(markdown.contains("dockerCodingTaskId：123456"));
        assertTrue(markdown.contains("dockerCodingStageRunId：stage-coding-agent"));
        assertTrue(markdown.contains("dockerCodingRepositoryUrl：https://github.com/acme/rd-bot-smoke.git"));
        assertTrue(markdown.contains("dockerCodingImage：ghcr.io/acme/rd-bot-executor:20260701"));
        assertTrue(markdown.contains("dockerCodingPatchArtifactId：artifact-patch-diff"));
        assertTrue(markdown.contains("dockerCodingPatchArtifactUri：s3://rd-bot-qa/docker-coding/patch.diff"));
        assertTrue(markdown.contains("dockerCodingValidationCommand：./mvnw test"));
        assertTrue(markdown.contains("dockerCodingTestsRun：276"));
        assertTrue(markdown.contains("dockerCodingRealDockerRun：true"));
        assertTrue(markdown.contains("| 6 | 编码 Agent 在 Docker 中真实改代码并运行测试 | PASSED |"));
    }

    @Test
    void shouldNotMarkDockerCodingAcceptancePassedWhenDockerEvidenceBelongsToAnotherTask()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDockerCoding(dockerCodingEvidence("another-task", FULL_COMMIT_HASH, true));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("dockerCodingEvidenceValidated：false"));
        assertTrue(markdown.contains("dockerCodingTaskId：another-task"));
        assertTrue(markdown.contains("| 6 | 编码 Agent 在 Docker 中真实改代码并运行测试 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkDockerCodingAcceptancePassedWhenDockerRunIsNotReal() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDockerCoding(dockerCodingEvidence("123456", FULL_COMMIT_HASH, false));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("dockerCodingEvidenceValidated：false"));
        assertTrue(markdown.contains("dockerCodingRealDockerRun：false"));
        assertTrue(markdown.contains("| 6 | 编码 Agent 在 Docker 中真实改代码并运行测试 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkDockerCodingAcceptancePassedWhenCommitHashIsShort() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDockerCoding(dockerCodingEvidence("123456", "abc123def456", true));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("dockerCodingEvidenceValidated：false"));
        assertTrue(markdown.contains("dockerCodingCommitHash：abc123def456"));
        assertTrue(markdown.contains("| 6 | 编码 Agent 在 Docker 中真实改代码并运行测试 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkDockerCodingAcceptancePassedWithoutDurableArtifactUris() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDockerCoding(dockerCodingEvidenceWithoutArtifactUris());

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("dockerCodingEvidenceValidated：false"));
        assertTrue(markdown.contains("dockerCodingPatchArtifactId：artifact-patch-diff"));
        assertTrue(markdown.contains("| 6 | 编码 Agent 在 Docker 中真实改代码并运行测试 | NOT_RUN |"));
    }

    @Test
    void shouldMarkQaFailureBlockerAcceptancePassedWhenFailureEvidenceIsValidated() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithQaFailureBlocker(qaFailureBlockerEvidence("123456", false, false, true));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("qaFailureBlockerEvidenceValidated：true"));
        assertTrue(markdown.contains("qaFailureBlockerTaskId：123456"));
        assertTrue(markdown.contains("qaFailureBlockerStageRunId：stage-qa-agent"));
        assertTrue(markdown.contains("qaFailureBlockerTaskStatus：FAILED_NEEDS_HUMAN"));
        assertTrue(markdown.contains("qaFailureBlockerQaStageStatus：FAILED_VALIDATION"));
        assertTrue(markdown.contains("qaFailureBlockerExecutionResultStatus：FAILED"));
        assertTrue(markdown.contains("qaFailureBlockerFailedAcceptanceCount：1"));
        assertTrue(markdown.contains("qaFailureBlockerPrCreated：false"));
        assertTrue(markdown.contains("qaFailureBlockerSuccessReportCreated：false"));
        assertTrue(markdown.contains("qaFailureBlockerBlockedBeforePrCreating：true"));
        assertTrue(markdown.contains("qaFailureBlockerFeishuAlertDelivered：true"));
        assertTrue(markdown.contains("| 7 | QA Agent 逐条验收并阻断失败交付 | PASSED |"));
    }

    @Test
    void shouldNotMarkQaFailureBlockerPassedWhenFailureEvidenceBelongsToAnotherTask() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithQaFailureBlocker(qaFailureBlockerEvidence("another-task", false, false, true));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("qaFailureBlockerEvidenceValidated：false"));
        assertTrue(markdown.contains("qaFailureBlockerTaskId：another-task"));
        assertTrue(markdown.contains("| 7 | QA Agent 逐条验收并阻断失败交付 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkQaFailureBlockerPassedWhenPullRequestWasCreated() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithQaFailureBlocker(qaFailureBlockerEvidence("123456", true, false, true));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("qaFailureBlockerEvidenceValidated：false"));
        assertTrue(markdown.contains("qaFailureBlockerPrCreated：true"));
        assertTrue(markdown.contains("| 7 | QA Agent 逐条验收并阻断失败交付 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkQaFailureBlockerPassedWithoutDurableQaArtifacts() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithQaFailureBlocker(qaFailureBlockerEvidenceWithoutArtifactUris());

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("qaFailureBlockerEvidenceValidated：false"));
        assertTrue(markdown.contains("qaFailureBlockerQaReportArtifactId：artifact-qa-report"));
        assertTrue(markdown.contains("| 7 | QA Agent 逐条验收并阻断失败交付 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkQaFailureBlockerPassedWithLocalFileArtifacts() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithQaFailureBlocker(new MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence(
                        true,
                        "123456",
                        "stage-qa-agent",
                        "FAILED_NEEDS_HUMAN",
                        "FAILED_VALIDATION",
                        "FAILED",
                        "artifact-qa-report",
                        "file:///tmp/rd-bot/qa-failure/qa-report.json",
                        List.of(
                                "s3://rd-bot-qa/qa-failure/logs/acceptance-1.log",
                                "s3://rd-bot-qa/qa-failure/logs/acceptance-2.log",
                                "s3://rd-bot-qa/qa-failure/logs/acceptance-3.log"
                        ),
                        1,
                        3,
                        3,
                        3,
                        false,
                        false,
                        true,
                        true,
                        "om-qa-failed"
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("qaFailureBlockerEvidenceValidated：false"));
        assertTrue(markdown.contains("qaFailureBlockerQaReportArtifactUri：file:///tmp/rd-bot/qa-failure/qa-report.json"));
        assertTrue(markdown.contains("| 7 | QA Agent 逐条验收并阻断失败交付 | NOT_RUN |"));
    }

    @Test
    void shouldMarkDeliveryReviewAcceptancePassedWhenFailureEvidenceIsValidated() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDeliveryReviewFailure(
                        new MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence(
                                true,
                                "123456",
                                "REJECTED",
                                false,
                                "REJECTED",
                                "DELIVERY_REVIEWER",
                                "artifact-delivery-review",
                                "s3://rd-bot-review/delivery-review/rejection.json",
                                "agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT",
                                false,
                                false,
                                false,
                                true,
                                false,
                                true,
                                true,
                                true,
                                "om-delivery-review-failed"
                        ),
                        validGitHubPrRemoteEvidence("123456", false)
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("deliveryReviewFailureEvidenceValidated：true"));
        assertTrue(markdown.contains("deliveryReviewFailureTaskId：123456"));
        assertTrue(markdown.contains("deliveryReviewFailureTaskStatus：REJECTED"));
        assertTrue(markdown.contains("deliveryReviewFailureApproved：false"));
        assertTrue(markdown.contains("deliveryReviewFailureDecision：REJECTED"));
        assertTrue(markdown.contains("deliveryReviewFailureReviewer：DELIVERY_REVIEWER"));
        assertTrue(markdown.contains("deliveryReviewFailurePullRequestPublicationAttempted：false"));
        assertTrue(markdown.contains("deliveryReviewFailurePrCreated：false"));
        assertTrue(markdown.contains("deliveryReviewFailureSuccessReportCreated：false"));
        assertTrue(markdown.contains("deliveryReviewFailureFailureReportCreated：true"));
        assertTrue(markdown.contains("deliveryReviewFailureSuccessDeliveryReportExperienceCreated：false"));
        assertTrue(markdown.contains("deliveryReviewFailureBlockedBeforePrCreating：true"));
        assertTrue(markdown.contains("deliveryReviewFailureStagePullRequestUrlRejected：true"));
        assertTrue(markdown.contains("deliveryReviewFailureFeishuAlertDelivered：true"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | PASSED |"));
    }

    @Test
    void shouldRequireRemotePrBodyEvidenceBeforeMarkingDeliveryReviewAcceptancePassed() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDeliveryReviewFailure(
                        new MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence(
                                true,
                                "123456",
                                "REJECTED",
                                false,
                                "REJECTED",
                                "DELIVERY_REVIEWER",
                                "artifact-delivery-review",
                                "s3://rd-bot-review/delivery-review/rejection.json",
                                "agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT",
                                false,
                                false,
                                false,
                                true,
                                false,
                                true,
                                true,
                                true,
                                "om-delivery-review-failed"
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("deliveryReviewFailureEvidenceValidated：true"));
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：false"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | NOT_RUN |"));
        assertTrue(markdown.contains("GitHub PR 远端反查 JSON sidecar"));
    }

    @Test
    void shouldNotMarkDeliveryReviewFailurePassedWhenFailureEvidenceBelongsToAnotherTask() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDeliveryReviewFailure(
                        new MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence(
                                true,
                                "another-task",
                                "REJECTED",
                                false,
                                "REJECTED",
                                "DELIVERY_REVIEWER",
                                "artifact-delivery-review",
                                "s3://rd-bot-review/delivery-review/rejection.json",
                                "agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT",
                                false,
                                false,
                                false,
                                true,
                                false,
                                true,
                                true,
                                true,
                                "om-delivery-review-failed"
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("deliveryReviewFailureEvidenceValidated：false"));
        assertTrue(markdown.contains("deliveryReviewFailureTaskId：another-task"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkDeliveryReviewFailurePassedWhenPullRequestPublicationWasAttempted() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDeliveryReviewFailure(
                        new MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence(
                                true,
                                "123456",
                                "REJECTED",
                                false,
                                "REJECTED",
                                "DELIVERY_REVIEWER",
                                "artifact-delivery-review",
                                "s3://rd-bot-review/delivery-review/rejection.json",
                                "agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT",
                                true,
                                false,
                                false,
                                true,
                                false,
                                true,
                                true,
                                true,
                                "om-delivery-review-failed"
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("deliveryReviewFailureEvidenceValidated：false"));
        assertTrue(markdown.contains("deliveryReviewFailurePullRequestPublicationAttempted：true"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkDeliveryReviewFailurePassedWithoutDurableReviewArtifactUri() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDeliveryReviewFailure(
                        new MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence(
                                true,
                                "123456",
                                "REJECTED",
                                false,
                                "REJECTED",
                                "DELIVERY_REVIEWER",
                                "artifact-delivery-review",
                                "agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT",
                                false,
                                false,
                                false,
                                true,
                                false,
                                true,
                                true,
                                true,
                                "om-delivery-review-failed"
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("deliveryReviewFailureEvidenceValidated：false"));
        assertTrue(markdown.contains("deliveryReviewFailureReviewArtifactId：artifact-delivery-review"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkDeliveryReviewFailurePassedWithLocalFileReviewArtifactUri() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDeliveryReviewFailure(
                        new MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence(
                                true,
                                "123456",
                                "REJECTED",
                                false,
                                "REJECTED",
                                "DELIVERY_REVIEWER",
                                "artifact-delivery-review",
                                "file:///tmp/rd-bot/delivery-review/rejection.json",
                                "agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT",
                                false,
                                false,
                                false,
                                true,
                                false,
                                true,
                                true,
                                true,
                                "om-delivery-review-failed"
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("deliveryReviewFailureEvidenceValidated：false"));
        assertTrue(markdown.contains("deliveryReviewFailureReviewArtifactUri：file:///tmp/rd-bot/delivery-review/rejection.json"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | NOT_RUN |"));
    }

    @Test
    void shouldMarkObservabilityAcceptancePassedWhenMetricsEvidenceIsValidated() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithObservabilityMetrics(
                        new MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence(
                                true,
                                "123456",
                                "https://rd-bot.example.com/actuator/prometheus",
                                200,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                4,
                                true,
                                true,
                                12,
                                12
                        ),
                        validGitHubPrRemoteEvidence("123456", false)
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("observabilityMetricsEvidenceValidated：true"));
        assertTrue(markdown.contains("observabilityMetricsTaskId：123456"));
        assertTrue(markdown.contains("observabilityMetricsEndpointUrl：https://rd-bot.example.com/actuator/prometheus"));
        assertTrue(markdown.contains("observabilityMetricsHttpStatus：200"));
        assertTrue(markdown.contains("contextBuildLatencyMetricPresent：true"));
        assertTrue(markdown.contains("repairSuccessRateMetricPresent：true"));
        assertTrue(markdown.contains("validationPassRateMetricPresent：true"));
        assertTrue(markdown.contains("prCreationRateMetricPresent：true"));
        assertTrue(markdown.contains("humanInterventionRateMetricPresent：true"));
        assertTrue(markdown.contains("retryRateMetricPresent：true"));
        assertTrue(markdown.contains("meanTimeToRepairMetricPresent：true"));
        assertTrue(markdown.contains("topFailureCategoriesMetricPresent：true"));
        assertTrue(markdown.contains("observabilityStageMetricCount：4"));
        assertTrue(markdown.contains("observabilityAuditTraceQuerySucceeded：true"));
        assertTrue(markdown.contains("observabilityRemotePrTraceValidated：true"));
        assertTrue(markdown.contains("observabilityAuditTraceLinkCount：12"));
        assertTrue(markdown.contains("observabilityTaskBoundAuditTraceLinkCount：12"));
        assertTrue(markdown.contains("| 14 | 指标和审计可观测 | PASSED |"));
    }

    @Test
    void shouldRequireRemotePrBodyEvidenceBeforeMarkingObservabilityAcceptancePassed() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithObservabilityMetrics(
                        new MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence(
                                true,
                                "123456",
                                "https://rd-bot.example.com/actuator/prometheus",
                                200,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                4,
                                true,
                                true,
                                12,
                                12
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("observabilityMetricsEvidenceValidated：true"));
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：false"));
        assertTrue(markdown.contains("| 14 | 指标和审计可观测 | NOT_RUN |"));
        assertTrue(markdown.contains("GitHub PR 远端反查 JSON sidecar"));
    }

    @Test
    void shouldRequireRemotePrBodyEvidenceBeforeMarkingSecretAcceptancePassed() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithGitHubPrRemoteEvidence(
                        MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence.empty()
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：false"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
        assertTrue(markdown.contains("GitHub PR 远端反查 JSON sidecar"));
    }

    @Test
    void shouldRecordRemotePrBodyEvidenceAndAllowSecretAcceptanceWhenValidated() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithGitHubPrRemoteEvidence(validGitHubPrRemoteEvidence("123456", false));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：true"));
        assertTrue(markdown.contains("githubPrRemoteTaskId：123456"));
        assertTrue(markdown.contains("githubPrRemotePullRequestUrl：https://github.com/acme/rd-bot-smoke/pull/7"));
        assertTrue(markdown.contains("githubPrRemoteBodyIncludesDeliveryReview：true"));
        assertTrue(markdown.contains("githubPrRemoteBodyIncludesQaEvidence：true"));
        assertTrue(markdown.contains("githubPrRemoteBodyContainsTaskId：true"));
        assertTrue(markdown.contains("githubPrRemoteBodyContainsArtifactLink：true"));
        assertTrue(markdown.contains("githubPrRemoteSecretScanEvidenceValidated：true"));
        assertTrue(markdown.contains("githubPrRemoteSecretLeakFound：false"));
        assertTrue(markdown.contains("githubPrRemoteSecretScannedValueCount：2"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | PASSED | taskId=123456"));
    }

    @Test
    void shouldNotCountRemotePrBodyEvidenceFromAnotherTask() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithGitHubPrRemoteEvidence(validGitHubPrRemoteEvidence("other-task", false));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：false"));
        assertTrue(markdown.contains("githubPrRemoteTaskId：other-task"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
    }

    @Test
    void shouldNotCountRemotePrBodyEvidenceFromDifferentPullRequest() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithGitHubPrRemoteEvidence(validGitHubPrRemoteEvidence(
                        "123456",
                        "https://github.com/acme/rd-bot-smoke/pull/8",
                        "8",
                        false
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：false"));
        assertTrue(markdown.contains("githubPrRemotePullRequestUrl：https://github.com/acme/rd-bot-smoke/pull/8"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
    }

    @Test
    void shouldNotCountRemotePrBodyEvidenceWhenPullRequestUrlIsNotGithubHttpUrl() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithPullRequestUrlAndGitHubPrRemoteEvidence(
                        "file:///tmp/rd-bot/pull/7",
                        validGitHubPrRemoteEvidence(
                        "123456",
                        "file:///tmp/rd-bot/pull/7",
                        "7",
                        false
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：false"));
        assertTrue(markdown.contains("githubPrRemotePullRequestUrl：file:///tmp/rd-bot/pull/7"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
    }

    @Test
    void shouldNotCountRemotePrBodyEvidenceWhenPullRequestUrlPathDoesNotMatchPullNumber() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithPullRequestUrlAndGitHubPrRemoteEvidence(
                        "https://github.com/acme/rd-bot-smoke/issues/7",
                        validGitHubPrRemoteEvidence(
                        "123456",
                        "https://github.com/acme/rd-bot-smoke/issues/7",
                        "7",
                        false
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：false"));
        assertTrue(markdown.contains("githubPrRemotePullRequestUrl：https://github.com/acme/rd-bot-smoke/issues/7"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
    }

    @Test
    void shouldNotCountRemotePrBodyEvidenceWhenWorkBranchIsNotRequirementTaskBranch() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithGitHubPrRemoteEvidence(validGitHubPrRemoteEvidence(
                        "123456",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        "7",
                        "hotfix/123456",
                        false
                ));

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：false"));
        assertTrue(markdown.contains("githubPrRemoteWorkBranch：hotfix/123456"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkObservabilityAcceptancePassedWhenMetricsEvidenceBelongsToAnotherTask()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithObservabilityMetrics(
                        new MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence(
                                true,
                                "another-task",
                                "https://rd-bot.example.com/actuator/prometheus",
                                200,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                4,
                                true,
                                true,
                                12,
                                12
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("observabilityMetricsEvidenceValidated：false"));
        assertTrue(markdown.contains("observabilityMetricsTaskId：another-task"));
        assertTrue(markdown.contains("| 14 | 指标和审计可观测 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkObservabilityAcceptancePassedWhenMetricsEndpointDidNotReturnOk()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithObservabilityMetrics(
                        new MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence(
                                true,
                                "123456",
                                "https://rd-bot.example.com/actuator/prometheus",
                                500,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                4,
                                true,
                                true,
                                12,
                                12
                        )
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("observabilityMetricsEvidenceValidated：false"));
        assertTrue(markdown.contains("observabilityMetricsHttpStatus：500"));
        assertTrue(markdown.contains("| 14 | 指标和审计可观测 | NOT_RUN |"));
    }

    @Test
    void shouldNotMarkObservabilityAcceptancePassedWhenMetricsEndpointUrlIsNotHttpPrometheusEndpoint()
            throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithObservabilityMetrics(
                        new MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence(
                                true,
                                "123456",
                                "file:///tmp/rd-bot/prometheus.txt",
                                200,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                4,
                                true,
                                true,
                                12,
                                12
                        ),
                        validGitHubPrRemoteEvidence("123456", false)
                );

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("observabilityMetricsEvidenceValidated：false"));
        assertTrue(markdown.contains("observabilityMetricsEndpointUrl：file:///tmp/rd-bot/prometheus.txt"));
        assertTrue(markdown.contains("| 14 | 指标和审计可观测 | NOT_RUN |"));
    }

    @Test
    void shouldMarkFinalProductionAcceptancePassedOnlyWhenAllPriorPointsPassed() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithCompleteProductionAcceptance();

        Path reportPath = report.writePassedMinimumSmoke(evidence);

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("结论：PASSED_FULL_PRODUCTION_ACCEPTANCE"));
        assertTrue(markdown.contains("| 3 | 需求评审 Agent 能阻断不可交付需求 | PASSED |"));
        assertTrue(markdown.contains("| 5 | 多 provider 降级重试真实生效 | PASSED |"));
        assertTrue(markdown.contains("| 6 | 编码 Agent 在 Docker 中真实改代码并运行测试 | PASSED |"));
        assertTrue(markdown.contains("| 7 | QA Agent 逐条验收并阻断失败交付 | PASSED |"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | PASSED |"));
        assertTrue(markdown.contains("| 9 | 状态机可恢复且不会重复派发 | PASSED |"));
        assertTrue(markdown.contains("| 10 | 错误通知真实送达 Feishu | PASSED |"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | PASSED |"));
        assertTrue(markdown.contains("| 14 | 指标和审计可观测 | PASSED |"));
        assertTrue(markdown.contains("| 15 | 生产真实测试结论要求 | PASSED |"));
        assertTrue(markdown.contains("#1-#14 生产验收均已通过"));
    }

    @Test
    void shouldWriteFailedMinimumSmokeReportWithRedactedFailureMessage() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                        "123456",
                        "EXECUTING",
                        "",
                        "0.1.0-smoke",
                        "prod-equivalent-a",
                        "qa-runner",
                        0,
                        0,
                        Map.of("REQUIREMENT_REVIEWER", stage("stage-requirement-reviewer", "SUCCEEDED", "provider-a", true)),
                        1,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        0,
                        List.of("REQUIREMENT_REVIEW"),
                        1,
                        false,
                        "",
                        0,
                        false,
                        0,
                        false,
                        false,
                        0,
                        2,
                        2,
                        "real",
                        "PAT_LOCAL_SMOKE",
                        1,
                        false,
                        0,
                        0,
                        List.of(),
                        0,
                        List.of("secret-value")
                );

        Path reportPath = report.writeFailedMinimumSmoke(
                evidence,
                new AssertionError("provider attempts leaked secret-value")
        );

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("结论：FAILED_MINIMUM_SMOKE"));
        assertTrue(markdown.contains("AssertionError"));
        assertTrue(markdown.contains("[REDACTED]"));
        assertTrue(markdown.contains("| 1 | 需求任务可进入多 Agent 工作流 | NOT_RUN |"));
        assertTrue(markdown.contains("| 10 | 错误通知真实送达 Feishu | NOT_RUN |"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | NOT_RUN |"));
        assertTrue(markdown.contains("| 15 | 生产真实测试结论要求 | FAILED |"));
        assertUsesOnlyFinalAcceptanceStatuses(markdown);
        assertFalse(markdown.contains("secret-value"));

        String json = Files.readString(jsonPath(reportPath));
        assertTrue(json.contains("\"conclusion\":\"FAILED_MINIMUM_SMOKE\""));
        assertTrue(json.contains("\"multiAgentProductionEvidenceValidated\":false"));
        assertTrue(json.contains("\"errorType\":\"AssertionError\""));
        assertTrue(json.contains("[REDACTED]"));
        assertFalse(json.contains("secret-value"));
    }

    @Test
    void shouldPreserveIndependentlyProvenAcceptancePointsInFailedMinimumSmokeReport() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence = minimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                12,
                allSucceededStages(),
                4,
                true,
                true,
                true,
                5,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                1,
                List.of("secret-value")
        );

        Path reportPath = report.writeFailedMinimumSmoke(
                evidence,
                new AssertionError("missing Feishu sidecar secret-value")
        );

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("| 1 | 需求任务可进入多 Agent 工作流 | PASSED | taskId=123456"));
        assertTrue(markdown.contains("| 2 | 角色上下文包真实落库且内容不同 | PASSED | taskId=123456"));
        assertTrue(markdown.contains("| 4 | 方案 Agent 产出可执行开发方案 | PASSED | taskId=123456"));
        assertTrue(markdown.contains("| 10 | 错误通知真实送达 Feishu | NOT_RUN |"));
        assertTrue(markdown.contains("| 11 | Skill 安装和使用受策略控制 | NOT_RUN |"));
        assertTrue(markdown.contains("| 12 | 经验自动沉淀且可被后续 RAG 检索 | PASSED | taskId=123456"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
        assertTrue(markdown.contains("| 15 | 生产真实测试结论要求 | FAILED |"));
        assertFalse(markdown.contains("secret-value"));
    }

    @Test
    void shouldNotPreserveExperienceAcceptanceInFailedReportWithoutFollowUpRetrievalEvidence() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence = withFollowUpTaskId(
                minimumSmokeEvidence(
                        "123456",
                        "COMPLETED",
                        "https://github.com/acme/rd-bot-smoke/pull/7",
                        12,
                        allSucceededStages(),
                        4,
                        true,
                        true,
                        true,
                        5,
                        true,
                        1,
                        true,
                        true,
                        1,
                        2,
                        2,
                        "real",
                        1,
                        List.of("secret-value")
                ),
                ""
        );

        Path reportPath = report.writeFailedMinimumSmoke(
                evidence,
                new AssertionError("failed after partial experience persistence")
        );

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("experienceRetrievalEvidenceValidated：true"));
        assertTrue(markdown.contains("followUpTaskId："));
        assertTrue(markdown.contains("| 12 | 经验自动沉淀且可被后续 RAG 检索 | NOT_RUN |"));
        assertTrue(markdown.contains("follow-up"));
        assertFalse(markdown.contains("secret-value"));
    }

    @Test
    void shouldNotPreserveSecretAcceptanceInFailedReportWithoutRemotePrBodyEvidence() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence = minimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                12,
                allSucceededStages(),
                4,
                true,
                true,
                true,
                5,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                1,
                List.of("secret-value")
        );

        Path reportPath = report.writeFailedMinimumSmoke(
                evidence,
                new AssertionError("failed before remote PR body scan")
        );

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("secretScanEvidenceValidated：true"));
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：false"));
        assertTrue(markdown.contains("| 13 | 密钥和敏感信息不进入产物 | NOT_RUN |"));
        assertTrue(markdown.contains("GitHub PR 远端反查 JSON sidecar"));
        assertFalse(markdown.contains("secret-value"));
    }

    @Test
    void shouldNotPreserveDeliveryReviewAcceptanceInFailedReportWithoutRemotePrBodyEvidence() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithDeliveryReviewFailure(
                        new MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence(
                                true,
                                "123456",
                                "REJECTED",
                                false,
                                "REJECTED",
                                "DELIVERY_REVIEWER",
                                "artifact-delivery-review",
                                "s3://rd-bot-review/delivery-review/rejection.json",
                                "agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT",
                                false,
                                false,
                                false,
                                true,
                                false,
                                true,
                                true,
                                true,
                                "om-delivery-review-failed"
                        )
                );

        Path reportPath = report.writeFailedMinimumSmoke(
                evidence,
                new AssertionError("failed before remote PR delivery review verification")
        );

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("deliveryReviewFailureEvidenceValidated：true"));
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：false"));
        assertTrue(markdown.contains("| 8 | 交付复核通过后才提交为已交付 | NOT_RUN |"));
        assertTrue(markdown.contains("GitHub PR 远端反查 JSON sidecar"));
    }

    @Test
    void shouldNotPreserveObservabilityAcceptanceInFailedReportWithoutRemotePrTraceEvidence() throws Exception {
        MultiAgentProductionAcceptanceReport report = new MultiAgentProductionAcceptanceReport(
                reportRoot,
                fixedClock()
        );
        MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence evidence =
                minimumSmokeEvidenceWithObservabilityMetrics(
                        new MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence(
                                true,
                                "123456",
                                "https://rd-bot.example.com/actuator/prometheus",
                                200,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                4,
                                true,
                                true,
                                12,
                                12
                        )
                );

        Path reportPath = report.writeFailedMinimumSmoke(
                evidence,
                new AssertionError("failed before remote PR observability trace verification")
        );

        String markdown = Files.readString(reportPath);
        assertTrue(markdown.contains("observabilityMetricsEvidenceValidated：true"));
        assertTrue(markdown.contains("githubPrRemoteEvidenceValidated：false"));
        assertTrue(markdown.contains("| 14 | 指标和审计可观测 | NOT_RUN |"));
        assertTrue(markdown.contains("GitHub PR 远端反查 JSON sidecar"));
    }

    private static void assertUsesOnlyFinalAcceptanceStatuses(String markdown) {
        assertFalse(markdown.contains("EVIDENCE_COLLECTED"));
        assertFalse(markdown.contains("PARTIAL_EVIDENCE"));
        assertFalse(markdown.contains("FAILED_OR_NOT_PROVEN"));
    }

    private static Path jsonPath(Path markdownReportPath) {
        String fileName = markdownReportPath.getFileName().toString().replaceFirst("\\.md$", ".json");
        return markdownReportPath.resolveSibling(fileName);
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence withFollowUpTaskId(
            MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence base,
            String followUpTaskId
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                base.taskId(),
                base.taskStatus(),
                base.pullRequestUrl(),
                base.rdBotVersion(),
                base.environmentId(),
                base.executedBy(),
                base.timelineEvents(),
                base.stageEvents(),
                base.stages(),
                base.roleContextPackageCount(),
                base.roleContextDifferent(),
                base.roleContextDistinctCount(),
                base.deliveryReviewApproved(),
                base.pullRequestPublicationSucceeded(),
                base.pullRequestEvidenceValidated(),
                base.pullRequestTargetAllowed(),
                base.pullRequestBodyEvidenceIncluded(),
                base.pullRequestQaAcceptanceResultCount(),
                base.experienceTypes(),
                base.experienceSourceLinkCount(),
                base.experienceHashCount(),
                base.experienceRedactedCount(),
                base.experienceCompleteTypeCount(),
                base.experienceRetrievalEvidenceValidated(),
                followUpTaskId,
                base.retrievedExperienceEvidenceCount(),
                base.solutionPlanEvidenceValidated(),
                base.solutionImplementationStepCount(),
                base.solutionAffectedFileCount(),
                base.solutionAcceptanceMappingCount(),
                base.solutionTestPlanStepCount(),
                base.codingPromptReferencesSolutionPlan(),
                base.qaReportEvidenceValidated(),
                base.qaAcceptanceResultCount(),
                base.qaPassedAcceptanceResultCount(),
                base.qaValidationCommandCount(),
                base.qaValidationLogArtifactCount(),
                base.expectedProviderCount(),
                base.providerSecretEnvCount(),
                base.githubCodePlatformMode(),
                base.githubAuthMode(),
                base.githubCredentialEnvCount(),
                base.secretScanEvidenceValidated(),
                base.secretScannedValueCount(),
                base.secretScannedArtifactPreviewCount(),
                base.secretScannedPullRequestMetadata(),
                base.feishuAlertTypes(),
                base.feishuAlertMessageCount(),
                base.feishuAlertTaskId(),
                base.feishuAlertMetadataComplete(),
                base.workflowRecoveryEvidence(),
                base.skillPolicyEvidence(),
                base.requirementReviewBlockerEvidence(),
                base.dockerCodingEvidence(),
                base.qaFailureBlockerEvidence(),
                base.deliveryReviewFailureEvidence(),
                base.githubPrRemoteEvidence(),
                base.observabilityMetricsEvidence(),
                base.secretNeedles()
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence withSecretNeedles(
            MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence base,
            List<String> secretNeedles
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                base.taskId(),
                base.taskStatus(),
                base.pullRequestUrl(),
                base.rdBotVersion(),
                base.environmentId(),
                base.executedBy(),
                base.timelineEvents(),
                base.stageEvents(),
                base.stages(),
                base.roleContextPackageCount(),
                base.roleContextDifferent(),
                base.roleContextDistinctCount(),
                base.deliveryReviewApproved(),
                base.pullRequestPublicationSucceeded(),
                base.pullRequestEvidenceValidated(),
                base.pullRequestTargetAllowed(),
                base.pullRequestBodyEvidenceIncluded(),
                base.pullRequestQaAcceptanceResultCount(),
                base.experienceTypes(),
                base.experienceSourceLinkCount(),
                base.experienceHashCount(),
                base.experienceRedactedCount(),
                base.experienceCompleteTypeCount(),
                base.experienceRetrievalEvidenceValidated(),
                base.followUpTaskId(),
                base.retrievedExperienceEvidenceCount(),
                base.solutionPlanEvidenceValidated(),
                base.solutionImplementationStepCount(),
                base.solutionAffectedFileCount(),
                base.solutionAcceptanceMappingCount(),
                base.solutionTestPlanStepCount(),
                base.codingPromptReferencesSolutionPlan(),
                base.qaReportEvidenceValidated(),
                base.qaAcceptanceResultCount(),
                base.qaPassedAcceptanceResultCount(),
                base.qaValidationCommandCount(),
                base.qaValidationLogArtifactCount(),
                base.expectedProviderCount(),
                base.providerSecretEnvCount(),
                base.githubCodePlatformMode(),
                base.githubAuthMode(),
                base.githubCredentialEnvCount(),
                base.secretScanEvidenceValidated(),
                base.secretScannedValueCount(),
                base.secretScannedArtifactPreviewCount(),
                base.secretScannedPullRequestMetadata(),
                base.feishuAlertTypes(),
                base.feishuAlertMessageCount(),
                base.feishuAlertTaskId(),
                base.feishuAlertMetadataComplete(),
                base.workflowRecoveryEvidence(),
                base.skillPolicyEvidence(),
                base.requirementReviewBlockerEvidence(),
                base.dockerCodingEvidence(),
                base.qaFailureBlockerEvidence(),
                base.deliveryReviewFailureEvidence(),
                base.githubPrRemoteEvidence(),
                base.observabilityMetricsEvidence(),
                secretNeedles
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence withGithubAuthMode(
            MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence base,
            String githubAuthMode,
            int githubCredentialEnvCount
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                base.taskId(),
                base.taskStatus(),
                base.pullRequestUrl(),
                base.rdBotVersion(),
                base.environmentId(),
                base.executedBy(),
                base.timelineEvents(),
                base.stageEvents(),
                base.stages(),
                base.roleContextPackageCount(),
                base.roleContextDifferent(),
                base.roleContextDistinctCount(),
                base.deliveryReviewApproved(),
                base.pullRequestPublicationSucceeded(),
                base.pullRequestEvidenceValidated(),
                base.pullRequestTargetAllowed(),
                base.pullRequestBodyEvidenceIncluded(),
                base.pullRequestQaAcceptanceResultCount(),
                base.experienceTypes(),
                base.experienceSourceLinkCount(),
                base.experienceHashCount(),
                base.experienceRedactedCount(),
                base.experienceCompleteTypeCount(),
                base.experienceRetrievalEvidenceValidated(),
                base.followUpTaskId(),
                base.retrievedExperienceEvidenceCount(),
                base.solutionPlanEvidenceValidated(),
                base.solutionImplementationStepCount(),
                base.solutionAffectedFileCount(),
                base.solutionAcceptanceMappingCount(),
                base.solutionTestPlanStepCount(),
                base.codingPromptReferencesSolutionPlan(),
                base.qaReportEvidenceValidated(),
                base.qaAcceptanceResultCount(),
                base.qaPassedAcceptanceResultCount(),
                base.qaValidationCommandCount(),
                base.qaValidationLogArtifactCount(),
                base.expectedProviderCount(),
                base.providerSecretEnvCount(),
                base.githubCodePlatformMode(),
                githubAuthMode,
                githubCredentialEnvCount,
                base.secretScanEvidenceValidated(),
                base.secretScannedValueCount(),
                base.secretScannedArtifactPreviewCount(),
                base.secretScannedPullRequestMetadata(),
                base.feishuAlertTypes(),
                base.feishuAlertMessageCount(),
                base.feishuAlertTaskId(),
                base.feishuAlertMetadataComplete(),
                base.workflowRecoveryEvidence(),
                base.skillPolicyEvidence(),
                base.requirementReviewBlockerEvidence(),
                base.dockerCodingEvidence(),
                base.qaFailureBlockerEvidence(),
                base.deliveryReviewFailureEvidence(),
                base.githubPrRemoteEvidence(),
                base.observabilityMetricsEvidence(),
                base.secretNeedles()
        );
    }

    private static MultiAgentProductionAcceptanceReport.StageEvidence stage(
            String status,
            String providerName,
            boolean providerAttemptsRecorded
    ) {
        return stage("stage-" + providerName, status, providerName, providerAttemptsRecorded);
    }

    private static MultiAgentProductionAcceptanceReport.StageEvidence stage(
            String stageRunId,
            String status,
            String providerName,
            boolean providerAttemptsRecorded
    ) {
        return new MultiAgentProductionAcceptanceReport.StageEvidence(
                stageRunId,
                "prompt-" + stageRunId.replace("stage-", ""),
                "result-" + stageRunId.replace("stage-", ""),
                2,
                2,
                status,
                providerName,
                providerAttemptsRecorded,
                providerAttemptsRecorded ? 2 : 0,
                true
        );
    }

    private static MultiAgentProductionAcceptanceReport.StageEvidence stageWithProviderFallback(
            String stageRunId,
            String activeProvider,
            String failedProvider,
            String failedStatus
    ) {
        return new MultiAgentProductionAcceptanceReport.StageEvidence(
                stageRunId,
                "prompt-" + stageRunId.replace("stage-", ""),
                "result-" + stageRunId.replace("stage-", ""),
                2,
                2,
                "SUCCEEDED",
                activeProvider,
                true,
                2,
                true,
                failedProvider,
                failedStatus,
                activeProvider,
                true
        );
    }

    private static Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> allSucceededStages() {
        return Map.of(
                "REQUIREMENT_REVIEWER", stage("stage-requirement-reviewer", "SUCCEEDED", "provider-a", true),
                "SOLUTION_ARCHITECT", stage("stage-solution-architect", "SUCCEEDED", "provider-b", true),
                "CODING_AGENT", stage("stage-coding-agent", "SUCCEEDED", "provider-b", true),
                "QA_AGENT", stage("stage-qa-agent", "SUCCEEDED", "provider-b", true)
        );
    }

    private static Map<String, MultiAgentProductionAcceptanceReport.StageEvidence>
    allSucceededStagesWithProviderAttemptCount(int providerAttemptCount) {
        return Map.of(
                "REQUIREMENT_REVIEWER", stageWithProviderAttemptCount(
                        "stage-requirement-reviewer", "provider-a", providerAttemptCount),
                "SOLUTION_ARCHITECT", stageWithProviderAttemptCount(
                        "stage-solution-architect", "provider-b", providerAttemptCount),
                "CODING_AGENT", stageWithProviderAttemptCount(
                        "stage-coding-agent", "provider-b", providerAttemptCount),
                "QA_AGENT", stageWithProviderAttemptCount(
                        "stage-qa-agent", "provider-b", providerAttemptCount)
        );
    }

    private static MultiAgentProductionAcceptanceReport.StageEvidence stageWithProviderAttemptCount(
            String stageRunId,
            String providerName,
            int providerAttemptCount
    ) {
        return new MultiAgentProductionAcceptanceReport.StageEvidence(
                stageRunId,
                "prompt-" + stageRunId.replace("stage-", ""),
                "result-" + stageRunId.replace("stage-", ""),
                2,
                2,
                "SUCCEEDED",
                providerName,
                providerAttemptCount > 0,
                providerAttemptCount,
                true
        );
    }

    private static Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> allSucceededStagesWithoutStageRunIds() {
        return Map.of(
                "REQUIREMENT_REVIEWER", stage("", "SUCCEEDED", "provider-a", true),
                "SOLUTION_ARCHITECT", stage("", "SUCCEEDED", "provider-b", true),
                "CODING_AGENT", stage("", "SUCCEEDED", "provider-b", true),
                "QA_AGENT", stage("", "SUCCEEDED", "provider-b", true)
        );
    }

    private static Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> allSucceededStagesWithoutArtifacts() {
        return Map.of(
                "REQUIREMENT_REVIEWER", stageWithoutArtifacts("stage-requirement-reviewer", "provider-a"),
                "SOLUTION_ARCHITECT", stageWithoutArtifacts("stage-solution-architect", "provider-b"),
                "CODING_AGENT", stageWithoutArtifacts("stage-coding-agent", "provider-b"),
                "QA_AGENT", stageWithoutArtifacts("stage-qa-agent", "provider-b")
        );
    }

    private static Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> allSucceededStagesWithoutArtifactRows() {
        return Map.of(
                "REQUIREMENT_REVIEWER", stageWithArtifactIdsButNoRows("stage-requirement-reviewer", "provider-a"),
                "SOLUTION_ARCHITECT", stageWithArtifactIdsButNoRows("stage-solution-architect", "provider-b"),
                "CODING_AGENT", stageWithArtifactIdsButNoRows("stage-coding-agent", "provider-b"),
                "QA_AGENT", stageWithArtifactIdsButNoRows("stage-qa-agent", "provider-b")
        );
    }

    private static Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> allSucceededStagesWithoutArtifactPreviews() {
        return Map.of(
                "REQUIREMENT_REVIEWER", stageWithArtifactRowsButNoPreviews("stage-requirement-reviewer", "provider-a"),
                "SOLUTION_ARCHITECT", stageWithArtifactRowsButNoPreviews("stage-solution-architect", "provider-b"),
                "CODING_AGENT", stageWithArtifactRowsButNoPreviews("stage-coding-agent", "provider-b"),
                "QA_AGENT", stageWithArtifactRowsButNoPreviews("stage-qa-agent", "provider-b")
        );
    }

    private static MultiAgentProductionAcceptanceReport.StageEvidence stageWithoutArtifacts(
            String stageRunId,
            String providerName
    ) {
        return new MultiAgentProductionAcceptanceReport.StageEvidence(
                stageRunId,
                "",
                "",
                0,
                0,
                "SUCCEEDED",
                providerName,
                true,
                2,
                true
        );
    }

    private static MultiAgentProductionAcceptanceReport.StageEvidence stageWithArtifactIdsButNoRows(
            String stageRunId,
            String providerName
    ) {
        return new MultiAgentProductionAcceptanceReport.StageEvidence(
                stageRunId,
                "prompt-" + stageRunId.replace("stage-", ""),
                "result-" + stageRunId.replace("stage-", ""),
                0,
                0,
                "SUCCEEDED",
                providerName,
                true,
                2,
                true
        );
    }

    private static MultiAgentProductionAcceptanceReport.StageEvidence stageWithArtifactRowsButNoPreviews(
            String stageRunId,
            String providerName
    ) {
        return new MultiAgentProductionAcceptanceReport.StageEvidence(
                stageRunId,
                "prompt-" + stageRunId.replace("stage-", ""),
                "result-" + stageRunId.replace("stage-", ""),
                2,
                0,
                "SUCCEEDED",
                providerName,
                true,
                2,
                true
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidence(
            String taskId,
            String taskStatus,
            String pullRequestUrl,
            int stageEvents,
            Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages,
            int roleContextPackageCount,
            boolean roleContextDifferent,
            boolean deliveryReviewApproved,
            boolean pullRequestPublicationSucceeded,
            int expectedProviderCount,
            int providerSecretEnvCount,
            String githubCodePlatformMode,
            int githubCredentialEnvCount,
            List<String> secretNeedles
    ) {
        return minimumSmokeEvidence(
                taskId,
                taskStatus,
                pullRequestUrl,
                stageEvents,
                stages,
                roleContextPackageCount,
                roleContextDifferent,
                deliveryReviewApproved,
                pullRequestPublicationSucceeded,
                5,
                true,
                1,
                true,
                true,
                1,
                expectedProviderCount,
                providerSecretEnvCount,
                githubCodePlatformMode,
                githubCredentialEnvCount,
                secretNeedles
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidence(
            String taskId,
            String taskStatus,
            String pullRequestUrl,
            int stageEvents,
            Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages,
            int roleContextPackageCount,
            boolean roleContextDifferent,
            boolean deliveryReviewApproved,
            boolean pullRequestPublicationSucceeded,
            int experienceSourceLinkCount,
            boolean solutionPlanEvidenceValidated,
            int solutionImplementationStepCount,
            boolean codingPromptReferencesSolutionPlan,
            boolean qaReportEvidenceValidated,
            int qaAcceptanceResultCount,
            int expectedProviderCount,
            int providerSecretEnvCount,
            String githubCodePlatformMode,
            int githubCredentialEnvCount,
            List<String> secretNeedles
    ) {
        return minimumSmokeEvidenceWithExperienceStorageCounts(
                taskId,
                taskStatus,
                pullRequestUrl,
                stageEvents,
                stages,
                roleContextPackageCount,
                roleContextDifferent,
                deliveryReviewApproved,
                pullRequestPublicationSucceeded,
                experienceSourceLinkCount,
                experienceSourceLinkCount,
                experienceSourceLinkCount,
                solutionPlanEvidenceValidated,
                solutionImplementationStepCount,
                codingPromptReferencesSolutionPlan,
                qaReportEvidenceValidated,
                qaAcceptanceResultCount,
                expectedProviderCount,
                providerSecretEnvCount,
                githubCodePlatformMode,
                githubCredentialEnvCount,
                secretNeedles
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithExperienceStorageCounts(
            String taskId,
            String taskStatus,
            String pullRequestUrl,
            int stageEvents,
            Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages,
            int roleContextPackageCount,
            boolean roleContextDifferent,
            boolean deliveryReviewApproved,
            boolean pullRequestPublicationSucceeded,
            int experienceSourceLinkCount,
            int experienceHashCount,
            int experienceRedactedCount,
            boolean solutionPlanEvidenceValidated,
            int solutionImplementationStepCount,
            boolean codingPromptReferencesSolutionPlan,
            boolean qaReportEvidenceValidated,
            int qaAcceptanceResultCount,
            int expectedProviderCount,
            int providerSecretEnvCount,
            String githubCodePlatformMode,
            int githubCredentialEnvCount,
            List<String> secretNeedles
    ) {
        return minimumSmokeEvidenceWithRoleContextAndExperienceCounts(
                taskId,
                taskStatus,
                pullRequestUrl,
                stageEvents,
                stages,
                roleContextPackageCount,
                roleContextDifferent,
                defaultRoleContextDistinctCount(roleContextPackageCount, roleContextDifferent),
                deliveryReviewApproved,
                pullRequestPublicationSucceeded,
                experienceSourceLinkCount,
                experienceHashCount,
                experienceRedactedCount,
                solutionPlanEvidenceValidated,
                solutionImplementationStepCount,
                codingPromptReferencesSolutionPlan,
                qaReportEvidenceValidated,
                qaAcceptanceResultCount,
                expectedProviderCount,
                providerSecretEnvCount,
                githubCodePlatformMode,
                githubCredentialEnvCount,
                secretNeedles
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithRoleContextAndExperienceCounts(
            String taskId,
            String taskStatus,
            String pullRequestUrl,
            int stageEvents,
            Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages,
            int roleContextPackageCount,
            boolean roleContextDifferent,
            int roleContextDistinctCount,
            boolean deliveryReviewApproved,
            boolean pullRequestPublicationSucceeded,
            int experienceSourceLinkCount,
            int experienceHashCount,
            int experienceRedactedCount,
            boolean solutionPlanEvidenceValidated,
            int solutionImplementationStepCount,
            boolean codingPromptReferencesSolutionPlan,
            boolean qaReportEvidenceValidated,
            int qaAcceptanceResultCount,
            int expectedProviderCount,
            int providerSecretEnvCount,
            String githubCodePlatformMode,
            int githubCredentialEnvCount,
            List<String> secretNeedles
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                taskId,
                taskStatus,
                pullRequestUrl,
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                stageEvents,
                stages,
                roleContextPackageCount,
                roleContextDifferent,
                roleContextDistinctCount,
                deliveryReviewApproved,
                pullRequestPublicationSucceeded,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                experienceSourceLinkCount,
                experienceHashCount,
                experienceRedactedCount,
                Math.min(experienceSourceLinkCount, Math.min(experienceHashCount, experienceRedactedCount)),
                true,
                "789012",
                4,
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
                "PAT_LOCAL_SMOKE",
                githubCredentialEnvCount,
                true,
                8,
                8,
                true,
                List.of(),
                0,
                "",
                false,
                MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence.empty(),
                MultiAgentProductionAcceptanceReport.SkillPolicyEvidence.empty(),
                MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DockerCodingEvidence.empty(),
                MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence.empty(),
                MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence.empty(),
                MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence.empty(),
                defaultSecretNeedles(secretNeedles)
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithSolutionAndQaEvidenceCounts(
            int solutionAffectedFileCount,
            int solutionAcceptanceMappingCount,
            int solutionTestPlanStepCount,
            int qaPassedAcceptanceResultCount,
            int qaValidationCommandCount,
            int qaValidationLogArtifactCount
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                allSucceededStages(),
                4,
                true,
                4,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                5,
                5,
                5,
                true,
                "789012",
                4,
                true,
                1,
                solutionAffectedFileCount,
                solutionAcceptanceMappingCount,
                solutionTestPlanStepCount,
                true,
                true,
                2,
                qaPassedAcceptanceResultCount,
                qaValidationCommandCount,
                qaValidationLogArtifactCount,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                true,
                List.of(),
                0,
                "",
                false,
                MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence.empty(),
                MultiAgentProductionAcceptanceReport.SkillPolicyEvidence.empty(),
                MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DockerCodingEvidence.empty(),
                MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence.empty(),
                MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence.empty(),
                MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence.empty(),
                defaultSecretNeedles(List.of())
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithExperienceCompleteTypeCount(
            int experienceCompleteTypeCount
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                allSucceededStages(),
                4,
                true,
                4,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                5,
                5,
                experienceCompleteTypeCount,
                true,
                "789012",
                4,
                true,
                1,
                1,
                1,
                1,
                true,
                true,
                2,
                2,
                2,
                2,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                true,
                List.of(),
                0,
                "",
                false,
                MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence.empty(),
                MultiAgentProductionAcceptanceReport.SkillPolicyEvidence.empty(),
                MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DockerCodingEvidence.empty(),
                MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence.empty(),
                MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence.empty(),
                MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence.empty(),
                defaultSecretNeedles(List.of())
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithSecretScannedPullRequestMetadata(
            boolean secretScannedPullRequestMetadata
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                allSucceededStages(),
                4,
                true,
                4,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                5,
                5,
                5,
                true,
                "789012",
                4,
                true,
                1,
                1,
                1,
                1,
                true,
                true,
                2,
                2,
                2,
                2,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                secretScannedPullRequestMetadata,
                List.of(),
                0,
                "",
                false,
                MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence.empty(),
                MultiAgentProductionAcceptanceReport.SkillPolicyEvidence.empty(),
                MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DockerCodingEvidence.empty(),
                MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence.empty(),
                MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence.empty(),
                MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence.empty(),
                defaultSecretNeedles(List.of())
        );
    }

    private static List<String> defaultSecretNeedles(List<String> secretNeedles) {
        if (secretNeedles == null || secretNeedles.isEmpty()) {
            return List.of("configured-secret-needle");
        }
        return secretNeedles;
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithFeishuAlerts(
            List<String> deliveredAlertTypes,
            int alertMessageCount
    ) {
        return minimumSmokeEvidenceWithFeishuAlerts(
                deliveredAlertTypes,
                alertMessageCount,
                "123456"
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithFeishuAlerts(
            List<String> deliveredAlertTypes,
            int alertMessageCount,
            String feishuAlertTaskId
    ) {
        return minimumSmokeEvidenceWithStagesAndFeishuAlerts(
                allSucceededStages(),
                deliveredAlertTypes,
                alertMessageCount,
                feishuAlertTaskId
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithStagesAndFeishuAlerts(
            Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages,
            List<String> deliveredAlertTypes,
            int alertMessageCount
    ) {
        return minimumSmokeEvidenceWithStagesAndFeishuAlerts(
                stages,
                deliveredAlertTypes,
                alertMessageCount,
                "123456",
                true
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithStagesAndFeishuAlerts(
            Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages,
            List<String> deliveredAlertTypes,
            int alertMessageCount,
            String feishuAlertTaskId
    ) {
        return minimumSmokeEvidenceWithStagesAndFeishuAlerts(
                stages,
                deliveredAlertTypes,
                alertMessageCount,
                feishuAlertTaskId,
                true
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithStagesAndFeishuAlerts(
            Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages,
            List<String> deliveredAlertTypes,
            int alertMessageCount,
            boolean alertMetadataComplete
    ) {
        return minimumSmokeEvidenceWithStagesAndFeishuAlerts(
                stages,
                deliveredAlertTypes,
                alertMessageCount,
                "123456",
                alertMetadataComplete
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithStagesAndFeishuAlerts(
            Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages,
            List<String> deliveredAlertTypes,
            int alertMessageCount,
            String feishuAlertTaskId,
            boolean alertMetadataComplete
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                stages,
                4,
                true,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                true,
                "789012",
                4,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                deliveredAlertTypes,
                alertMessageCount,
                feishuAlertTaskId,
                alertMetadataComplete,
                defaultSecretNeedles(List.of())
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithWorkflowRecovery(
            MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence recoveryEvidence
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                allSucceededStages(),
                4,
                true,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                true,
                "789012",
                4,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                List.of(),
                0,
                false,
                recoveryEvidence,
                defaultSecretNeedles(List.of())
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithSkillPolicy(
            MultiAgentProductionAcceptanceReport.SkillPolicyEvidence skillPolicyEvidence
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                allSucceededStages(),
                4,
                true,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                true,
                "789012",
                4,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                List.of(),
                0,
                MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence.empty(),
                skillPolicyEvidence,
                defaultSecretNeedles(List.of())
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithRequirementReviewBlocker(
            MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence blockerEvidence
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                allSucceededStages(),
                4,
                true,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                true,
                "789012",
                4,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                List.of(),
                0,
                MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence.empty(),
                MultiAgentProductionAcceptanceReport.SkillPolicyEvidence.empty(),
                blockerEvidence,
                MultiAgentProductionAcceptanceReport.DockerCodingEvidence.empty(),
                defaultSecretNeedles(List.of())
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithDockerCoding(
            MultiAgentProductionAcceptanceReport.DockerCodingEvidence dockerCodingEvidence
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                allSucceededStages(),
                4,
                true,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                true,
                "789012",
                4,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                List.of(),
                0,
                MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence.empty(),
                MultiAgentProductionAcceptanceReport.SkillPolicyEvidence.empty(),
                MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence.empty(),
                dockerCodingEvidence,
                defaultSecretNeedles(List.of())
        );
    }

    private static MultiAgentProductionAcceptanceReport.DockerCodingEvidence dockerCodingEvidence(
            String taskId,
            String commitHash,
            boolean realDockerRun
    ) {
        return new MultiAgentProductionAcceptanceReport.DockerCodingEvidence(
                true,
                taskId,
                "stage-coding-agent",
                "https://github.com/acme/rd-bot-smoke.git",
                "ghcr.io/acme/rd-bot-executor:20260701",
                "docker-container-123",
                "/workspace/rd-bot",
                commitHash,
                "artifact-patch-diff",
                "artifact-result-json",
                "artifact-test-log",
                "artifact-docker-metadata",
                "s3://rd-bot-qa/docker-coding/patch.diff",
                "s3://rd-bot-qa/docker-coding/result.json",
                "s3://rd-bot-qa/docker-coding/test.log",
                "s3://rd-bot-qa/docker-coding/docker-metadata.json",
                2,
                "./mvnw test",
                0,
                276,
                0,
                true,
                true,
                realDockerRun
        );
    }

    private static MultiAgentProductionAcceptanceReport.DockerCodingEvidence dockerCodingEvidenceWithoutArtifactUris() {
        return new MultiAgentProductionAcceptanceReport.DockerCodingEvidence(
                true,
                "123456",
                "stage-coding-agent",
                "https://github.com/acme/rd-bot-smoke.git",
                "ghcr.io/acme/rd-bot-executor:20260701",
                "docker-container-123",
                "/workspace/rd-bot",
                FULL_COMMIT_HASH,
                "artifact-patch-diff",
                "artifact-result-json",
                "artifact-test-log",
                "artifact-docker-metadata",
                2,
                "./mvnw test",
                0,
                276,
                0,
                true,
                true,
                true
        );
    }

    private static MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence qaFailureBlockerEvidence(
            String taskId,
            boolean prCreated,
            boolean successReportCreated,
            boolean blockedBeforePrCreating
    ) {
        return new MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence(
                true,
                taskId,
                "stage-qa-agent",
                "FAILED_NEEDS_HUMAN",
                "FAILED_VALIDATION",
                "FAILED",
                "artifact-qa-report",
                "s3://rd-bot-qa/qa-failure/qa-report.json",
                List.of(
                        "s3://rd-bot-qa/qa-failure/logs/acceptance-1.log",
                        "s3://rd-bot-qa/qa-failure/logs/acceptance-2.log",
                        "s3://rd-bot-qa/qa-failure/logs/acceptance-3.log"
                ),
                1,
                3,
                3,
                3,
                prCreated,
                successReportCreated,
                blockedBeforePrCreating,
                true,
                "om-qa-failed"
        );
    }

    private static MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence qaFailureBlockerEvidenceWithoutArtifactUris() {
        return new MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence(
                true,
                "123456",
                "stage-qa-agent",
                "FAILED_NEEDS_HUMAN",
                "FAILED_VALIDATION",
                "FAILED",
                "artifact-qa-report",
                1,
                3,
                3,
                3,
                false,
                false,
                true,
                true,
                "om-qa-failed"
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithQaFailureBlocker(
            MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence qaFailureBlockerEvidence
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                allSucceededStages(),
                4,
                true,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                true,
                "789012",
                4,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                List.of(),
                0,
                MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence.empty(),
                MultiAgentProductionAcceptanceReport.SkillPolicyEvidence.empty(),
                MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DockerCodingEvidence.empty(),
                qaFailureBlockerEvidence,
                defaultSecretNeedles(List.of())
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithDeliveryReviewFailure(
            MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence deliveryReviewFailureEvidence
    ) {
        return minimumSmokeEvidenceWithDeliveryReviewFailure(
                deliveryReviewFailureEvidence,
                MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence.empty()
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithDeliveryReviewFailure(
            MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence deliveryReviewFailureEvidence,
            MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence githubPrRemoteEvidence
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                allSucceededStages(),
                4,
                true,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                true,
                "789012",
                4,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                List.of(),
                0,
                "",
                false,
                MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence.empty(),
                MultiAgentProductionAcceptanceReport.SkillPolicyEvidence.empty(),
                MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DockerCodingEvidence.empty(),
                MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence.empty(),
                deliveryReviewFailureEvidence,
                githubPrRemoteEvidence,
                MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence.empty(),
                defaultSecretNeedles(List.of())
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithObservabilityMetrics(
            MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence observabilityMetricsEvidence
    ) {
        return minimumSmokeEvidenceWithObservabilityMetrics(
                observabilityMetricsEvidence,
                MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence.empty()
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithObservabilityMetrics(
            MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence observabilityMetricsEvidence,
            MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence githubPrRemoteEvidence
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                allSucceededStages(),
                4,
                true,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                true,
                "789012",
                4,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                List.of(),
                0,
                "",
                false,
                MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence.empty(),
                MultiAgentProductionAcceptanceReport.SkillPolicyEvidence.empty(),
                MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DockerCodingEvidence.empty(),
                MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence.empty(),
                githubPrRemoteEvidence,
                observabilityMetricsEvidence,
                defaultSecretNeedles(List.of())
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithGitHubPrRemoteEvidence(
            MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence githubPrRemoteEvidence
    ) {
        return minimumSmokeEvidenceWithPullRequestUrlAndGitHubPrRemoteEvidence(
                "https://github.com/acme/rd-bot-smoke/pull/7",
                githubPrRemoteEvidence
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithPullRequestUrlAndGitHubPrRemoteEvidence(
            String pullRequestUrl,
            MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence githubPrRemoteEvidence
    ) {
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                pullRequestUrl,
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                allSucceededStages(),
                4,
                true,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                true,
                "789012",
                4,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                List.of(),
                0,
                "",
                false,
                MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence.empty(),
                MultiAgentProductionAcceptanceReport.SkillPolicyEvidence.empty(),
                MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DockerCodingEvidence.empty(),
                MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence.empty(),
                MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence.empty(),
                githubPrRemoteEvidence,
                MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence.empty(),
                defaultSecretNeedles(List.of())
        );
    }

    private static MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence validGitHubPrRemoteEvidence(
            String taskId,
            boolean secretLeakFound
    ) {
        return validGitHubPrRemoteEvidence(
                taskId,
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "7",
                secretLeakFound
        );
    }

    private static MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence validGitHubPrRemoteEvidence(
            String taskId,
            String pullRequestUrl,
            String pullRequestNumber,
            boolean secretLeakFound
    ) {
        return validGitHubPrRemoteEvidence(
                taskId,
                pullRequestUrl,
                pullRequestNumber,
                "requirement/123456",
                secretLeakFound
        );
    }

    private static MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence validGitHubPrRemoteEvidence(
            String taskId,
            String pullRequestUrl,
            String pullRequestNumber,
            String workBranch,
            boolean secretLeakFound
    ) {
        return new MultiAgentProductionAcceptanceReport.GitHubPrRemoteEvidence(
                true,
                taskId,
                pullRequestUrl,
                pullRequestNumber,
                "main",
                workBranch,
                true,
                true,
                true,
                true,
                true,
                secretLeakFound,
                2
        );
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence minimumSmokeEvidenceWithCompleteProductionAcceptance() {
        Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stages = new java.util.LinkedHashMap<>(
                allSucceededStages()
        );
        stages.put("CODING_AGENT", stageWithProviderFallback(
                "stage-coding-agent",
                "provider-b",
                "provider-a",
                "FAILED_VALIDATION"
        ));
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                "123456",
                "COMPLETED",
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "0.1.0-smoke",
                "prod-equivalent-a",
                "qa-runner",
                14,
                12,
                stages,
                4,
                true,
                true,
                true,
                true,
                true,
                true,
                1,
                List.of(
                        "REQUIREMENT_REVIEW",
                        "TECHNICAL_DESIGN",
                        "CODE_CHANGE",
                        "QA_REPORT",
                        "DELIVERY_REPORT"
                ),
                5,
                true,
                "789012",
                4,
                true,
                1,
                true,
                true,
                1,
                2,
                2,
                "real",
                "PAT_LOCAL_SMOKE",
                1,
                true,
                8,
                8,
                List.of(
                        "STAGE_FAILED_RETRYABLE",
                        "STAGE_FAILED_NEEDS_HUMAN",
                        "PROVIDER_FALLBACK",
                        "QA_FAILED",
                        "DELIVERY_REVIEW_FAILED",
                        "PR_PUBLICATION_FAILED"
                ),
                6,
                "123456",
                true,
                new MultiAgentProductionAcceptanceReport.WorkflowRecoveryEvidence(
                        true,
                        "123456",
                        2,
                        4,
                        5,
                        14,
                        0,
                        1,
                        2,
                        true,
                        List.of(
                                "EXECUTING",
                                "VALIDATING",
                                "PR_CREATING",
                                "COMMITTED",
                                "REPORTING",
                                "COMPLETED"
                        ),
                        "s3://rd-bot-qa/startup.log",
                        "s3://rd-bot-qa/recovery-snapshots.json"
                ),
                new MultiAgentProductionAcceptanceReport.SkillPolicyEvidence(
                        true,
                        "123456",
                        "stage-skill-smoke",
                        "qa-real-runner",
                        "v1",
                        "QA_AGENT",
                        "REQUIREMENT_REVIEWER",
                        "CODING_AGENT",
                        "/opt/rd-bot/skills/qa-real-runner/v1",
                        "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        true,
                        true,
                        true,
                        true,
                        1
                ),
                new MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence(
                        true,
                        "123456",
                        "stage-reviewer",
                        "FAILED_NEEDS_HUMAN",
                        "NEEDS_HUMAN",
                        "NEED_INFO",
                        "artifact-review-json",
                        "s3://rd-bot-review/requirement-review/blocker.json",
                        "缺少业务边界和验收命令",
                        List.of("业务边界", "验收命令"),
                        3,
                        false,
                        true,
                        "om-review-blocked"
                ),
                dockerCodingEvidence("123456", FULL_COMMIT_HASH, true),
                qaFailureBlockerEvidence("123456", false, false, true),
                new MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence(
                        true,
                        "123456",
                        "REJECTED",
                        false,
                        "REJECTED",
                        "DELIVERY_REVIEWER",
                        "artifact-delivery-review",
                        "s3://rd-bot-review/delivery-review/rejection.json",
                        "agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT",
                        false,
                        false,
                        false,
                        true,
                        false,
                        true,
                        true,
                        true,
                        "om-delivery-review-failed"
                ),
                validGitHubPrRemoteEvidence("123456", false),
                new MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence(
                        true,
                        "123456",
                        "https://rd-bot.example.com/actuator/prometheus",
                        200,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        4,
                        true,
                        true,
                        12,
                        12
                ),
                defaultSecretNeedles(List.of())
        );
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-01T08:00:00Z"), ZoneOffset.UTC);
    }

    private static int defaultRoleContextDistinctCount(int roleContextPackageCount, boolean roleContextDifferent) {
        return roleContextDifferent ? Math.max(roleContextPackageCount, 0) : 0;
    }

    private static int defaultEvidenceCount(boolean evidenceValidated, int fallbackCount) {
        return evidenceValidated ? Math.max(fallbackCount, 0) : 0;
    }
}
