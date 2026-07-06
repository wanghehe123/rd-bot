package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;
import com.wish.rd.exec.repair.result.AgentRoleResultValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多 Agent 需求交付生产真实冒烟测试：默认跳过，显式开启才执行。
 *
 * <p>该测试不使用 mock。开启后会请求真实 RD-Bot HTTP 服务，并查询真实 PostgreSQL
 * 阶段运行表，验证生产链路是否真的生成四个角色阶段。
 *
 * <p>执行示例：
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=MultiAgentRequirementDeliveryRealSmokeTest \
 *   -Drd.integration.multi-agent.enabled=true \
 *   -Drd.multi-agent.smoke.production-evidence=true \
 *   -Drd.multi-agent.smoke.rd-bot-version=0.1.0-smoke \
 *   -Drd.multi-agent.smoke.environment-id=prod-equivalent-a \
 *   -Drd.multi-agent.smoke.executed-by=qa-runner \
 *   -Drd.multi-agent.smoke.base-url=http://127.0.0.1:8080 \
 *   -Drd.multi-agent.smoke.postgres-url=jdbc:postgresql://127.0.0.1:5432/rd_bot \
 *   -Drd.multi-agent.smoke.postgres-user=rd_bot \
 *   -Drd.multi-agent.smoke.postgres-password=$RD_BOT_POSTGRES_PASSWORD \
 *   -Drd.multi-agent.smoke.repository-url=https://github.com/acme/rd-bot-smoke.git \
 *   -Drd.multi-agent.smoke.repo-owner=acme \
 *   -Drd.multi-agent.smoke.repo-name=rd-bot-smoke \
 *   -Drd.multi-agent.smoke.task-id=7478000000000000000 \
 *   -Drd.multi-agent.smoke.expected-provider-count=2 \
 *   -Drd.multi-agent.smoke.provider-secret-env-names=LONGCAT_API_KEY,ANTHROPIC_API_KEY \
 *   -Drd.multi-agent.smoke.github-code-platform-mode=real \
 *   -Drd.multi-agent.smoke.github-auth-mode=GH_CLI_LOCAL_SMOKE \
 *   -Drd.multi-agent.smoke.provider-preflight-evidence-json=/path/to/provider-preflight-production-acceptance.json \
 *   -Drd.multi-agent.smoke.feishu-alert-evidence-json=/path/to/feishu-alert-production-acceptance.json \
 *   -Drd.multi-agent.smoke.recovery-evidence-json=/path/to/workflow-recovery-production-acceptance.json \
 *   -Drd.multi-agent.smoke.skill-policy-evidence-json=/path/to/skill-production-acceptance.json \
 *   -Drd.multi-agent.smoke.requirement-review-evidence-json=/path/to/requirement-review-blocker-production-acceptance.json \
 *   -Drd.multi-agent.smoke.docker-coding-evidence-json=/path/to/docker-coding-production-acceptance.json \
 *   -Drd.multi-agent.smoke.qa-failure-evidence-json=/path/to/qa-failure-blocker-production-acceptance.json \
 *   -Drd.multi-agent.smoke.delivery-review-failure-evidence-json=/path/to/delivery-review-failure-production-acceptance.json \
 *   -Drd.multi-agent.smoke.github-pr-remote-evidence-json=/path/to/github-pr-remote-production-acceptance.json \
 *   -Drd.multi-agent.smoke.observability-metrics-evidence-json=/path/to/observability-metrics-production-acceptance.json \
 *   test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.multi-agent.enabled", matches = "true")
class MultiAgentRequirementDeliveryRealSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> EXPECTED_ROLES = List.of(
            "REQUIREMENT_REVIEWER",
            "SOLUTION_ARCHITECT",
            "CODING_AGENT",
            "QA_AGENT"
    );
    private static final List<String> REQUIRED_EXPERIENCE_TYPES = List.of(
            "REQUIREMENT_REVIEW",
            "TECHNICAL_DESIGN",
            "CODE_CHANGE",
            "QA_REPORT",
            "DELIVERY_REPORT"
    );
    private static final AgentRoleResultValidator ROLE_RESULT_VALIDATOR = new AgentRoleResultValidator();

    @Test
    void createsRealRequirementAndPersistsAuditableMultiAgentDelivery() throws Exception {
        MultiAgentProductionAcceptanceReport report = MultiAgentProductionAcceptanceReport.fromSystemProperties();
        Map<String, String> properties = productionAcceptanceProperties();
        List<String> missing = MultiAgentProductionAcceptanceProfile.missingRequiredProperties(properties);
        if (!missing.isEmpty()) {
            Path skippedReport = report.writeSkipped(missing);
            ProductionSmokePreconditions.requireReady("multi-agent", missing, skippedReport);
        }
        MultiAgentProductionAcceptanceProfile profile = MultiAgentProductionAcceptanceProfile.from(properties);
        ProviderPreflightEvidenceFile providerPreflightEvidence = ProviderPreflightEvidenceFile.from(profile);
        if (!providerPreflightEvidence.validated()) {
            List<String> providerPreflightMissing = List.of(
                    "rd.multi-agent.smoke.provider-preflight-evidence-json=PASSED ProviderPreflightRealSmokeTest JSON "
                            + "for the same rdBotVersion/environmentId/executedBy and expectedProviderCount"
            );
            Path skippedReport = report.writeSkipped(providerPreflightMissing);
            ProductionSmokePreconditions.requireReady("multi-agent", providerPreflightMissing, skippedReport);
        }
        FeishuAlertEvidenceFile feishuAlertEvidence = FeishuAlertEvidenceFile.from(profile);
        WorkflowRecoveryEvidenceFile recoveryEvidence = WorkflowRecoveryEvidenceFile.from(profile);
        SkillPolicyEvidenceFile skillPolicyEvidence = SkillPolicyEvidenceFile.from(profile);
        RequirementReviewBlockerEvidenceFile requirementReviewEvidence =
                RequirementReviewBlockerEvidenceFile.from(profile);
        DockerCodingEvidenceFile dockerCodingEvidence = DockerCodingEvidenceFile.from(profile);
        QaFailureBlockerEvidenceFile qaFailureEvidence = QaFailureBlockerEvidenceFile.from(profile);
        DeliveryReviewFailureEvidenceFile deliveryReviewFailureEvidence =
                DeliveryReviewFailureEvidenceFile.from(profile);
        GitHubPrRemoteEvidenceFile githubPrRemoteEvidence = GitHubPrRemoteEvidenceFile.from(profile);
        ObservabilityMetricsEvidenceFile observabilityMetricsEvidence =
                ObservabilityMetricsEvidenceFile.from(profile);

        String taskId = "";
        JsonNode detail = OBJECT_MAPPER.createObjectNode();
        JsonNode timeline = OBJECT_MAPPER.createArrayNode();
        Map<String, StageSnapshot> stages = Map.of();
        Map<String, String> contextPackages = Map.of();
        int roleContextDistinctCount = 0;
        List<String> experienceTypes = List.of();
        int experienceSourceLinkCount = 0;
        int experienceHashCount = 0;
        int experienceRedactedCount = 0;
        int experienceCompleteTypeCount = 0;
        int stageEventCount = 0;
        SolutionPlanEvidence solutionPlanEvidence = SolutionPlanEvidence.empty();
        QaReportEvidence qaReportEvidence = QaReportEvidence.empty();
        SecretScanEvidence secretScanEvidence = SecretScanEvidence.empty();
        ExperienceRetrievalEvidence experienceRetrievalEvidence = ExperienceRetrievalEvidence.empty();
        try {
            taskId = resolveAcceptanceTaskId(profile);

            detail = waitForCompletedTask(profile, taskId);
            assertEquals(taskId, detail.path("taskId").asText(), "detail endpoint should return the created task");
            assertEquals("COMPLETED", detail.path("status").asText(),
                    "production delivery smoke must finish with a completed audited task");
            assertFalse(detail.path("pullRequestUrl").asText("").isBlank(),
                    "completed requirement delivery must expose a real pull request URL");
            JsonNode executionResult = executionResultJson(detail);
            assertTrue(executionResult.path("deliveryReview").path("approved").asBoolean(false),
                    "completed requirement delivery must include approved delivery review evidence");
            assertEquals("DELIVERY_REVIEWER",
                    executionResult.path("deliveryReview").path("reviewer").asText(""),
                    "delivery review evidence must identify the control-plane reviewer");
            assertTrue(executionResult.path("pullRequestPublication").path("success").asBoolean(false),
                    "completed requirement delivery must create the pull request after delivery review");
            assertEquals(detail.path("pullRequestUrl").asText(""),
                    executionResult.path("pullRequestPublication").path("pullRequestUrl").asText(""),
                    "published pull request must match final task pullRequestUrl");

            try (Connection connection = DriverManager.getConnection(
                    profile.postgresUrl(), profile.postgresUser(), profile.postgresPassword())) {
                stages = stageSnapshots(connection, taskId);
                assertEquals(EXPECTED_ROLES, List.copyOf(stages.keySet()),
                        "production workflow must persist the four default agent roles");
                stages.forEach((role, stage) -> {
                    assertEquals("SUCCEEDED", stage.status(), "stage must succeed in production smoke: " + role);
                    assertFalse(stage.stageRunId().isBlank(), "stage must expose persisted stageRunId: " + role);
                    assertFalse(stage.contextPackageId().isBlank(), "stage must bind role context: " + role);
                    assertFalse(stage.promptArtifactId().isBlank(), "stage must bind prompt artifact: " + role);
                    assertFalse(stage.resultArtifactId().isBlank(), "stage must bind result artifact: " + role);
                    assertTrue(stage.stageArtifactCount() >= 2,
                            "stage must persist prompt and result artifacts: " + role);
                    assertTrue(stage.stageArtifactPreviewCount() >= 2,
                            "stage artifacts must include auditable prompt and result previews: " + role);
                    assertFalse(stage.providerName().isBlank(), "stage must record final provider: " + role);
                    assertFalse("[]".equals(stage.providerAttemptsJson()),
                            "stage must record provider attempts: " + role);
                    int providerAttemptCount = ProviderAttemptEvidence.count(stage.providerAttemptsJson());
                    assertTrue(providerAttemptCount > 0,
                            "stage must record at least one real provider attempt: role=" + role
                                    + ", actual=" + providerAttemptCount);
                });
                stageEventCount = stageEventCount(connection, taskId);
                assertTrue(stageEventCount >= EXPECTED_ROLES.size(),
                        "production workflow must persist auditable stage events");

                contextPackages = contextPackages(connection, taskId);
                assertEquals(EXPECTED_ROLES, List.copyOf(contextPackages.keySet()),
                        "all roles must persist role context packages");
                roleContextDistinctCount = Math.toIntExact(contextPackages.values().stream().distinct().count());
                assertTrue(roleContextDistinctCount >= EXPECTED_ROLES.size(),
                        "role context evidence must be unique for every role rather than only partially distinct: "
                                + roleContextDistinctCount);

                experienceTypes = experienceTypes(connection, taskId);
                assertTrue(experienceTypes.containsAll(REQUIRED_EXPERIENCE_TYPES),
                        "production delivery must persist all reusable experience types: " + experienceTypes);
                experienceSourceLinkCount = experienceSourceLinkCount(connection, taskId);
                assertTrue(experienceSourceLinkCount >= REQUIRED_EXPERIENCE_TYPES.size(),
                        "production delivery must link every required experience type to source artifacts: "
                                + experienceSourceLinkCount);
                experienceHashCount = experienceHashCount(connection, taskId);
                assertTrue(experienceHashCount >= REQUIRED_EXPERIENCE_TYPES.size(),
                        "production delivery must store content_hash for every required experience type: "
                                + experienceHashCount);
                experienceRedactedCount = experienceRedactedCount(connection, taskId);
                assertTrue(experienceRedactedCount >= REQUIRED_EXPERIENCE_TYPES.size(),
                        "production delivery must mark every required experience type as redacted: "
                                + experienceRedactedCount);
                experienceCompleteTypeCount = experienceCompleteTypeCount(connection, taskId);
                assertTrue(experienceCompleteTypeCount >= REQUIRED_EXPERIENCE_TYPES.size(),
                        "production delivery must store source artifact, content_hash and redaction for every "
                                + "required experience type: " + experienceCompleteTypeCount);
                solutionPlanEvidence = solutionPlanEvidence(
                        connection,
                        stages.get("SOLUTION_ARCHITECT"),
                        stages.get("CODING_AGENT")
                );
                assertTrue(solutionPlanEvidence.validated(),
                        "production solution plan artifact must pass role protocol validation: "
                                + solutionPlanEvidence.errorMessage());
                assertTrue(solutionPlanEvidence.implementationStepCount() > 0,
                        "production solution plan must include implementationSteps");
                assertTrue(solutionPlanEvidence.affectedFileCount() > 0,
                        "production solution plan must include affectedFiles");
                assertTrue(solutionPlanEvidence.acceptanceMappingCount() > 0,
                        "production solution plan must include acceptanceMapping");
                assertTrue(solutionPlanEvidence.testPlanStepCount() > 0,
                        "production solution plan must include testPlan");
                assertTrue(solutionPlanEvidence.codingPromptReferencesSolutionPlan(),
                        "coding agent prompt must include solution architect upstream result");
                qaReportEvidence = qaReportEvidence(connection, stages.get("QA_AGENT"));
                assertTrue(qaReportEvidence.validated(),
                        "production QA result artifact must pass role protocol validation: "
                                + qaReportEvidence.errorMessage());
                assertTrue(qaReportEvidence.acceptanceResultCount() > 0,
                        "production QA result artifact must include acceptanceResults");
                assertEquals(qaReportEvidence.acceptanceResultCount(), qaReportEvidence.passedAcceptanceResultCount(),
                        "production QA result artifact must mark every acceptanceResult as PASSED");
                assertTrue(qaReportEvidence.validationCommandCount() >= qaReportEvidence.acceptanceResultCount(),
                        "production QA result artifact must bind every acceptanceResult to a real command");
                assertTrue(qaReportEvidence.validationLogArtifactCount() >= qaReportEvidence.acceptanceResultCount(),
                        "production QA result artifact must bind every acceptanceResult to a log artifact");

                List<String> artifactPreviews = artifactPreviews(connection, taskId);
                assertTrue(artifactPreviews.size() >= EXPECTED_ROLES.size() * 2,
                        "secret scan must cover prompt and result previews for every role");
                String pullRequestMetadataJson = pullRequestMetadataJson(detail);
                assertFalse(pullRequestMetadataJson.isBlank(),
                        "secret scan must include pullRequestPublication.metadataJson");
                List<String> secretScanValues = new ArrayList<>();
                secretScanValues.add(detail.toString());
                secretScanValues.add(pullRequestMetadataJson);
                secretScanValues.addAll(contextPackages.values());
                secretScanValues.add(solutionPlanEvidence.contentPreview());
                secretScanValues.add(solutionPlanEvidence.codingPromptPreview());
                secretScanValues.add(qaReportEvidence.contentPreview());
                secretScanValues.addAll(artifactPreviews);
                secretScanValues.add(stages.values().stream()
                        .map(StageSnapshot::providerAttemptsJson)
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse(""));
                assertNoSecretNeedles(profile, secretScanValues);
                secretScanEvidence = new SecretScanEvidence(true, secretScanValues.size(), artifactPreviews.size(), true);
            }

            timeline = getJson(profile.baseUrl(), "/admin/rd-tasks/" + taskId + "/timeline", profile.requestTimeout());
            assertTrue(timeline.isArray(), "timeline endpoint must return an array");
            assertTrue(timeline.size() > 0, "timeline must not be empty");
            List<String> statuses = new ArrayList<>();
            timeline.forEach(event -> statuses.add(event.path("status").asText("")));
            assertTrue(statuses.containsAll(List.of("VALIDATING", "PR_CREATING", "REPORTING", "COMPLETED")),
                    "timeline must include validation, PR creation, reporting and completion states: " + statuses);
            assertTrue(statuses.indexOf("EXECUTING") < statuses.indexOf("VALIDATING"),
                    "validation must happen after agent execution: " + statuses);
            assertTrue(statuses.indexOf("VALIDATING") < statuses.indexOf("PR_CREATING"),
                    "PR creation must happen after delivery review validation: " + statuses);
            assertTrue(statuses.indexOf("PR_CREATING") < statuses.indexOf("COMMITTED"),
                    "task must not be committed before PR creation starts: " + statuses);
            assertTrue(statuses.indexOf("COMMITTED") < statuses.indexOf("REPORTING"),
                    "reporting must happen after PR URL is recorded: " + statuses);
            assertTrue(statuses.indexOf("REPORTING") < statuses.indexOf("COMPLETED"),
                    "completion must happen after reporting: " + statuses);

            JsonNode followUpCreated = postJson(
                    profile.baseUrl(),
                    "/admin/rd-tasks/requirements",
                    followUpRequirementBody(profile),
                    profile.requestTimeout()
            );
            String followUpTaskId = followUpCreated.path("taskId").asText("");
            assertFalse(followUpTaskId.isBlank(), "follow-up requirement taskId must not be blank");
            JsonNode followUpDetail = waitForCompletedTask(profile, followUpTaskId);
            assertEquals("COMPLETED", followUpDetail.path("status").asText(""),
                    "follow-up requirement must complete so its role contexts can prove experience retrieval");
            try (Connection connection = DriverManager.getConnection(
                    profile.postgresUrl(), profile.postgresUser(), profile.postgresPassword())) {
                int retrievedExperienceEvidenceCount = contextExperienceEvidenceCount(connection, followUpTaskId);
                assertTrue(retrievedExperienceEvidenceCount > 0,
                        "follow-up role context must include reusable experience evidence from previous delivery");
                experienceRetrievalEvidence = new ExperienceRetrievalEvidence(
                        true,
                        followUpTaskId,
                        retrievedExperienceEvidenceCount
                );
            }
            Path reportPath = report.writePassedMinimumSmoke(smokeEvidence(
                    profile,
                    taskId,
                    detail,
                    timeline,
                    stages,
                    contextPackages,
                    roleContextDistinctCount,
                    experienceTypes,
                    experienceSourceLinkCount,
                    experienceHashCount,
                    experienceRedactedCount,
                    experienceCompleteTypeCount,
                    solutionPlanEvidence,
                    qaReportEvidence,
                    secretScanEvidence,
                    experienceRetrievalEvidence,
                    feishuAlertEvidence,
                    recoveryEvidence,
                    skillPolicyEvidence,
                    requirementReviewEvidence,
                    dockerCodingEvidence,
                    qaFailureEvidence,
                    deliveryReviewFailureEvidence,
                    githubPrRemoteEvidence,
                    observabilityMetricsEvidence,
                    stageEventCount
            ));
            System.out.println("[smoke] multi-agent taskId=" + taskId
                    + " status=" + detail.path("status").asText("")
                    + " timelineEvents=" + timeline.size()
                    + " report=" + reportPath.toAbsolutePath());
        } catch (Throwable failure) {
            try {
                Path reportPath = report.writeFailedMinimumSmoke(smokeEvidence(
                        profile,
                        taskId,
                        detail,
                        timeline,
                        stages,
                        contextPackages,
                        roleContextDistinctCount,
                        experienceTypes,
                        experienceSourceLinkCount,
                        experienceHashCount,
                        experienceRedactedCount,
                        experienceCompleteTypeCount,
                        solutionPlanEvidence,
                        qaReportEvidence,
                        secretScanEvidence,
                        experienceRetrievalEvidence,
                        feishuAlertEvidence,
                        recoveryEvidence,
                        skillPolicyEvidence,
                        requirementReviewEvidence,
                        dockerCodingEvidence,
                        qaFailureEvidence,
                        deliveryReviewFailureEvidence,
                        githubPrRemoteEvidence,
                        observabilityMetricsEvidence,
                        stageEventCount
                ), failure);
                System.out.println("[smoke] multi-agent failure report=" + reportPath.toAbsolutePath());
            } catch (IOException reportFailure) {
                failure.addSuppressed(reportFailure);
            }
            throw failure;
        }
    }

    private static Map<String, String> productionAcceptanceProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        MultiAgentProductionAcceptanceProfile.requiredPropertyNames()
                .forEach(name -> properties.put(name, System.getProperty(name, "")));
        properties.put("rd.multi-agent.smoke.base-branch", System.getProperty(
                "rd.multi-agent.smoke.base-branch", "main"));
        properties.put("rd.multi-agent.smoke.auto-execute", System.getProperty(
                "rd.multi-agent.smoke.auto-execute", "true"));
        properties.put("rd.multi-agent.smoke.task-id", System.getProperty(
                "rd.multi-agent.smoke.task-id", ""));
        properties.put("rd.multi-agent.smoke.provider-secret-env-names", System.getProperty(
                "rd.multi-agent.smoke.provider-secret-env-names", ""));
        properties.put("rd.multi-agent.smoke.github-credential-env-names", System.getProperty(
                "rd.multi-agent.smoke.github-credential-env-names", ""));
        properties.put("rd.multi-agent.smoke.request-timeout-seconds", System.getProperty(
                "rd.multi-agent.smoke.request-timeout-seconds", ""));
        properties.put("rd.multi-agent.smoke.completion-timeout-seconds", System.getProperty(
                "rd.multi-agent.smoke.completion-timeout-seconds", ""));
        properties.put("rd.multi-agent.smoke.poll-interval-seconds", System.getProperty(
                "rd.multi-agent.smoke.poll-interval-seconds", ""));
        properties.put("rd.multi-agent.smoke.provider-preflight-evidence-json", System.getProperty(
                "rd.multi-agent.smoke.provider-preflight-evidence-json", ""));
        properties.put("rd.multi-agent.smoke.feishu-alert-evidence-json", System.getProperty(
                "rd.multi-agent.smoke.feishu-alert-evidence-json", ""));
        properties.put("rd.multi-agent.smoke.recovery-evidence-json", System.getProperty(
                "rd.multi-agent.smoke.recovery-evidence-json", ""));
        properties.put("rd.multi-agent.smoke.skill-policy-evidence-json", System.getProperty(
                "rd.multi-agent.smoke.skill-policy-evidence-json", ""));
        properties.put("rd.multi-agent.smoke.requirement-review-evidence-json", System.getProperty(
                "rd.multi-agent.smoke.requirement-review-evidence-json", ""));
        properties.put("rd.multi-agent.smoke.docker-coding-evidence-json", System.getProperty(
                "rd.multi-agent.smoke.docker-coding-evidence-json", ""));
        properties.put("rd.multi-agent.smoke.qa-failure-evidence-json", System.getProperty(
                "rd.multi-agent.smoke.qa-failure-evidence-json", ""));
        properties.put("rd.multi-agent.smoke.delivery-review-failure-evidence-json", System.getProperty(
                "rd.multi-agent.smoke.delivery-review-failure-evidence-json", ""));
        properties.put("rd.multi-agent.smoke.github-pr-remote-evidence-json", System.getProperty(
                "rd.multi-agent.smoke.github-pr-remote-evidence-json", ""));
        properties.put("rd.multi-agent.smoke.observability-metrics-evidence-json", System.getProperty(
                "rd.multi-agent.smoke.observability-metrics-evidence-json", ""));
        properties.put("rd.multi-agent.smoke.secret-scan-needles", System.getProperty(
                "rd.multi-agent.smoke.secret-scan-needles", ""));
        return properties;
    }

    private static String resolveAcceptanceTaskId(MultiAgentProductionAcceptanceProfile profile) throws Exception {
        String configuredTaskId = profile.taskId();
        if (!configuredTaskId.isBlank()) {
            return configuredTaskId;
        }
        JsonNode created = postJson(
                profile.baseUrl(),
                "/admin/rd-tasks/requirements",
                requirementBody(profile),
                profile.requestTimeout()
        );
        String createdTaskId = created.path("taskId").asText("");
        assertFalse(createdTaskId.isBlank(), "created requirement taskId must not be blank");
        return createdTaskId;
    }

    private static MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence smokeEvidence(
            MultiAgentProductionAcceptanceProfile profile,
            String taskId,
            JsonNode detail,
            JsonNode timeline,
            Map<String, StageSnapshot> stages,
            Map<String, String> contextPackages,
            int roleContextDistinctCount,
            List<String> experienceTypes,
            int experienceSourceLinkCount,
            int experienceHashCount,
            int experienceRedactedCount,
            int experienceCompleteTypeCount,
            SolutionPlanEvidence solutionPlanEvidence,
            QaReportEvidence qaReportEvidence,
            SecretScanEvidence secretScanEvidence,
            ExperienceRetrievalEvidence experienceRetrievalEvidence,
            FeishuAlertEvidenceFile feishuAlertEvidence,
            WorkflowRecoveryEvidenceFile recoveryEvidence,
            SkillPolicyEvidenceFile skillPolicyEvidence,
            RequirementReviewBlockerEvidenceFile requirementReviewEvidence,
            DockerCodingEvidenceFile dockerCodingEvidence,
            QaFailureBlockerEvidenceFile qaFailureEvidence,
            DeliveryReviewFailureEvidenceFile deliveryReviewFailureEvidence,
            GitHubPrRemoteEvidenceFile githubPrRemoteEvidence,
            ObservabilityMetricsEvidenceFile observabilityMetricsEvidence,
            int stageEventCount
    ) {
        Map<String, MultiAgentProductionAcceptanceReport.StageEvidence> stageEvidence = new LinkedHashMap<>();
        stages.forEach((role, stage) -> {
            ProviderAttemptEvidence.FallbackEvidence fallback = ProviderAttemptEvidence.fallback(
                    stage.providerAttemptsJson()
            );
            stageEvidence.put(role, new MultiAgentProductionAcceptanceReport.StageEvidence(
                    stage.stageRunId(),
                    stage.promptArtifactId(),
                    stage.resultArtifactId(),
                    stage.stageArtifactCount(),
                    stage.stageArtifactPreviewCount(),
                    stage.status(),
                    stage.providerName(),
                    !"[]".equals(stage.providerAttemptsJson()),
                    ProviderAttemptEvidence.count(stage.providerAttemptsJson()),
                    fallback.detected(),
                    fallback.failedProvider(),
                    fallback.failedStatus(),
                    fallback.activeProvider(),
                    !stage.contextPackageId().isBlank()
            ));
        });
        PullRequestPublicationEvidence pullRequestEvidence =
                PullRequestPublicationEvidence.from(detail, profile, taskId);
        return new MultiAgentProductionAcceptanceReport.MinimumSmokeEvidence(
                taskId,
                detail.path("status").asText(""),
                detail.path("pullRequestUrl").asText(""),
                profile.rdBotVersion(),
                profile.environmentId(),
                profile.executedBy(),
                timeline.isArray() ? timeline.size() : 0,
                stageEventCount,
                stageEvidence,
                contextPackages.size(),
                contextPackages.values().stream().distinct().count() > 1,
                roleContextDistinctCount,
                deliveryReviewApproved(detail),
                pullRequestPublicationSucceeded(detail),
                pullRequestEvidence.validated(),
                pullRequestEvidence.targetAllowed(),
                pullRequestEvidence.bodyEvidenceIncluded(),
                pullRequestEvidence.qaAcceptanceResultCount(),
                experienceTypes,
                experienceSourceLinkCount,
                experienceHashCount,
                experienceRedactedCount,
                experienceCompleteTypeCount,
                experienceRetrievalEvidence.validated(),
                experienceRetrievalEvidence.followUpTaskId(),
                experienceRetrievalEvidence.retrievedExperienceEvidenceCount(),
                solutionPlanEvidence.validated(),
                solutionPlanEvidence.implementationStepCount(),
                solutionPlanEvidence.affectedFileCount(),
                solutionPlanEvidence.acceptanceMappingCount(),
                solutionPlanEvidence.testPlanStepCount(),
                solutionPlanEvidence.codingPromptReferencesSolutionPlan(),
                qaReportEvidence.validated(),
                qaReportEvidence.acceptanceResultCount(),
                qaReportEvidence.passedAcceptanceResultCount(),
                qaReportEvidence.validationCommandCount(),
                qaReportEvidence.validationLogArtifactCount(),
                profile.expectedProviderCount(),
                profile.providerSecretEnvNames().size(),
                profile.githubCodePlatformMode(),
                profile.githubAuthMode(),
                profile.githubCredentialEnvNames().size(),
                secretScanEvidence.validated(),
                secretScanEvidence.scannedValueCount(),
                secretScanEvidence.scannedArtifactPreviewCount(),
                secretScanEvidence.pullRequestMetadataScanned(),
                feishuAlertEvidence.alertTypes(),
                feishuAlertEvidence.messageCount(),
                feishuAlertEvidence.taskId(),
                feishuAlertEvidence.metadataComplete(),
                recoveryEvidence.toReportEvidence(),
                skillPolicyEvidence.toReportEvidence(),
                requirementReviewEvidence.toReportEvidence(),
                dockerCodingEvidence.toReportEvidence(),
                qaFailureEvidence.toReportEvidence(),
                deliveryReviewFailureEvidence.toReportEvidence(),
                githubPrRemoteEvidence.toReportEvidence(),
                observabilityMetricsEvidence.toReportEvidence(),
                profile.secretScanNeedles()
        );
    }

    private static int stageEventCount(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total
                FROM rd_agent_stage_events
                WHERE task_id = ?
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("total");
                }
                return 0;
            }
        }
    }

    private static JsonNode executionResultJson(JsonNode detail) throws IOException {
        JsonNode value = detail.path("executionResultJson");
        if (value.isObject()) {
            return value;
        }
        String raw = value.asText("{}");
        if (raw.isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        return OBJECT_MAPPER.readTree(raw);
    }

    private static boolean deliveryReviewApproved(JsonNode detail) {
        try {
            return executionResultJson(detail).path("deliveryReview").path("approved").asBoolean(false);
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean pullRequestPublicationSucceeded(JsonNode detail) {
        try {
            return executionResultJson(detail).path("pullRequestPublication").path("success").asBoolean(false);
        } catch (IOException exception) {
            return false;
        }
    }

    private static Map<String, StageSnapshot> stageSnapshots(Connection connection, String taskId) throws Exception {
        Map<String, StageSnapshot> snapshots = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    r.id,
                    r.role,
                    r.status,
                    r.provider_name,
                    r.provider_attempts_json,
                    r.context_package_id,
                    r.prompt_artifact_id,
                    r.result_artifact_id,
                    (
                        SELECT COUNT(*)
                        FROM rd_agent_stage_artifacts artifact
                        WHERE artifact.stage_run_id = r.id
                    ) AS stage_artifact_count,
                    (
                        SELECT COUNT(*)
                        FROM rd_agent_stage_artifacts artifact
                        WHERE artifact.stage_run_id = r.id
                          AND artifact.content_preview <> ''
                    ) AS stage_artifact_preview_count
                FROM rd_agent_stage_runs r
                WHERE r.task_id = ?
                ORDER BY CASE r.role
                    WHEN 'REQUIREMENT_REVIEWER' THEN 1
                    WHEN 'SOLUTION_ARCHITECT' THEN 2
                    WHEN 'CODING_AGENT' THEN 3
                    WHEN 'QA_AGENT' THEN 4
                    ELSE 99
                END
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    snapshots.put(resultSet.getString("role"), new StageSnapshot(
                            resultSet.getString("id"),
                            resultSet.getString("status"),
                            resultSet.getString("provider_name"),
                            resultSet.getString("provider_attempts_json"),
                            resultSet.getString("context_package_id"),
                            resultSet.getString("prompt_artifact_id"),
                            resultSet.getString("result_artifact_id"),
                            resultSet.getInt("stage_artifact_count"),
                            resultSet.getInt("stage_artifact_preview_count")
                    ));
                }
            }
        }
        return snapshots;
    }

    private static Map<String, String> contextPackages(Connection connection, String taskId) throws Exception {
        Map<String, String> contexts = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role, evidence_json::text
                FROM rd_role_context_packages
                WHERE task_id = ?
                ORDER BY CASE role
                    WHEN 'REQUIREMENT_REVIEWER' THEN 1
                    WHEN 'SOLUTION_ARCHITECT' THEN 2
                    WHEN 'CODING_AGENT' THEN 3
                    WHEN 'QA_AGENT' THEN 4
                    ELSE 99
                END
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    contexts.put(resultSet.getString("role"), resultSet.getString("evidence_json"));
                }
            }
        }
        return contexts;
    }

    private static List<String> experienceTypes(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT experience_type
                FROM rd_experience_entries
                WHERE task_id = ?
                ORDER BY created_at, id
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.ArrayList<String> types = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    types.add(resultSet.getString("experience_type"));
                }
                return List.copyOf(types);
            }
        }
    }

    private static int experienceSourceLinkCount(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total
                FROM rd_experience_entries
                WHERE task_id = ?
                  AND source_artifact_id IS NOT NULL
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("total");
                }
                return 0;
            }
        }
    }

    private static int experienceHashCount(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total
                FROM rd_experience_entries
                WHERE task_id = ?
                  AND content_hash IS NOT NULL
                  AND content_hash <> ''
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("total");
                }
                return 0;
            }
        }
    }

    private static int experienceRedactedCount(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total
                FROM rd_experience_entries
                WHERE task_id = ?
                  AND redacted = TRUE
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("total");
                }
                return 0;
            }
        }
    }

    private static int experienceCompleteTypeCount(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(DISTINCT experience_type) AS total
                FROM rd_experience_entries
                WHERE task_id = ?
                  AND experience_type IN ('REQUIREMENT_REVIEW', 'TECHNICAL_DESIGN', 'CODE_CHANGE',
                                          'QA_REPORT', 'DELIVERY_REPORT')
                  AND source_artifact_id IS NOT NULL
                  AND content_hash IS NOT NULL
                  AND content_hash <> ''
                  AND redacted = TRUE
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("total");
                }
                return 0;
            }
        }
    }

    private static int contextExperienceEvidenceCount(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total
                FROM rd_role_context_packages context_package,
                     LATERAL jsonb_array_elements(context_package.evidence_json) evidence
                WHERE context_package.task_id = ?
                  AND evidence ->> 'sourceUri' LIKE 'rd-experience://%'
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("total");
                }
                return 0;
            }
        }
    }

    private static SolutionPlanEvidence solutionPlanEvidence(
            Connection connection,
            StageSnapshot solutionStage,
            StageSnapshot codingStage
    ) throws Exception {
        if (solutionStage == null || solutionStage.resultArtifactId().isBlank()) {
            return new SolutionPlanEvidence(
                    false,
                    0,
                    0,
                    0,
                    0,
                    false,
                    "solution stage result artifact is missing",
                    "",
                    ""
            );
        }
        String contentPreview = artifactPreview(connection, solutionStage.resultArtifactId());
        String codingPromptPreview = codingStage == null || codingStage.promptArtifactId().isBlank()
                ? ""
                : artifactPreview(connection, codingStage.promptArtifactId());
        AgentRoleResultValidation validation = ROLE_RESULT_VALIDATOR.validate("SOLUTION_ARCHITECT", contentPreview);
        return new SolutionPlanEvidence(
                validation.valid(),
                solutionImplementationStepCount(contentPreview),
                jsonArraySize(contentPreview, "affectedFiles"),
                jsonArraySize(contentPreview, "acceptanceMapping"),
                jsonArraySize(contentPreview, "testPlan"),
                codingPromptPreview.contains("SOLUTION_ARCHITECT"),
                String.join(", ", validation.errors()),
                contentPreview,
                codingPromptPreview
        );
    }

    private static QaReportEvidence qaReportEvidence(Connection connection, StageSnapshot qaStage) throws Exception {
        if (qaStage == null || qaStage.resultArtifactId().isBlank()) {
            return new QaReportEvidence(false, 0, 0, 0, 0, "QA stage result artifact is missing", "");
        }
        String contentPreview = artifactPreview(connection, qaStage.resultArtifactId());
        AgentRoleResultValidation validation = ROLE_RESULT_VALIDATOR.validate("QA_AGENT", contentPreview);
        return new QaReportEvidence(
                validation.valid(),
                qaAcceptanceResultCount(contentPreview),
                qaPassedAcceptanceResultCount(contentPreview),
                qaValidationCommandCount(contentPreview),
                qaValidationLogArtifactCount(contentPreview),
                String.join(", ", validation.errors()),
                contentPreview
        );
    }

    private static String artifactPreview(Connection connection, String artifactId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT content_preview
                FROM rd_agent_stage_artifacts
                WHERE id = ?
                """)) {
            statement.setLong(1, Long.parseLong(artifactId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String value = resultSet.getString("content_preview");
                    return value == null ? "" : value;
                }
                return "";
            }
        }
    }

    private static List<String> artifactPreviews(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT content_preview
                FROM rd_agent_stage_artifacts
                WHERE task_id = ?
                ORDER BY created_at, id
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> previews = new ArrayList<>();
                while (resultSet.next()) {
                    String value = resultSet.getString("content_preview");
                    previews.add(value == null ? "" : value);
                }
                return List.copyOf(previews);
            }
        }
    }

    private static int solutionImplementationStepCount(String contentPreview) {
        return jsonArraySize(contentPreview, "implementationSteps");
    }

    private static int jsonArraySize(String contentPreview, String fieldName) {
        try {
            JsonNode array = OBJECT_MAPPER
                    .readTree(contentPreview == null ? "" : contentPreview)
                    .path(fieldName == null ? "" : fieldName);
            return array.isArray() ? array.size() : 0;
        } catch (IOException exception) {
            return 0;
        }
    }

    private static int qaAcceptanceResultCount(String contentPreview) {
        return jsonArraySize(contentPreview, "acceptanceResults");
    }

    private static int qaPassedAcceptanceResultCount(String contentPreview) {
        return qaAcceptanceResultFieldCount(contentPreview, "status", "PASSED");
    }

    private static int qaValidationCommandCount(String contentPreview) {
        return qaAcceptanceResultFieldCount(contentPreview, "command", "");
    }

    private static int qaValidationLogArtifactCount(String contentPreview) {
        return qaAcceptanceResultFieldCount(contentPreview, "logArtifactId", "");
    }

    private static String pullRequestMetadataJson(JsonNode detail) throws IOException {
        JsonNode executionResult = executionResultJson(detail);
        JsonNode metadata = executionResult.path("pullRequestPublication").path("metadataJson");
        if (metadata.isObject()) {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        }
        return metadata.asText("").strip();
    }

    private static int qaAcceptanceResultFieldCount(String contentPreview, String fieldName, String expectedValue) {
        try {
            JsonNode acceptanceResults = OBJECT_MAPPER
                    .readTree(contentPreview == null ? "" : contentPreview)
                    .path("acceptanceResults");
            if (!acceptanceResults.isArray()) {
                return 0;
            }
            int count = 0;
            for (JsonNode acceptanceResult : acceptanceResults) {
                String value = acceptanceResult.path(fieldName == null ? "" : fieldName).asText("");
                if (expectedValue == null || expectedValue.isBlank()) {
                    if (!value.isBlank()) {
                        count++;
                    }
                } else if (expectedValue.equalsIgnoreCase(value.strip())) {
                    count++;
                }
            }
            return count;
        } catch (IOException exception) {
            return 0;
        }
    }

    private static String requirementBody(MultiAgentProductionAcceptanceProfile profile) throws IOException {
        return requirementBody(
                profile,
                "Add RD-Bot production smoke marker",
                "在目标仓库交付一个可审查、低风险的生产 smoke 标记文档。",
                """
                        请在目标仓库新增或更新 `docs/rd-bot-production-smoke.md`。

                        文档必须包含：
                        - 一级标题 `# RD-Bot Production Smoke`
                        - 标记文本 `multi-agent-production-smoke`
                        - 交付说明：本文件用于验证 RD-Bot 多角色编排能完成真实仓库变更、测试和 PR 产物。
                        - 验收清单：需求评审、方案、编码、QA 四个角色均应由 RD-Bot 外层编排留痕。

                        约束：
                        - 不修改身份校验、安全控制、业务逻辑文件。
                        - 不写入真实凭据明文或本次验收的敏感针值。
                        - QA 必须运行真实 shell 命令验证文档存在和标记文本存在。
                        """
        );
    }

    private static String followUpRequirementBody(MultiAgentProductionAcceptanceProfile profile) throws IOException {
        return requirementBody(
                profile,
                "Add RD-Bot production smoke follow-up marker",
                "复用上一条生产 smoke 交付经验，补充一个 follow-up 标记文档。",
                """
                        请在目标仓库新增或更新 `docs/rd-bot-production-smoke-follow-up.md`。

                        文档必须包含：
                        - 一级标题 `# RD-Bot Production Smoke Follow-up`
                        - 标记文本 `multi-agent-production-smoke-follow-up`
                        - 说明本次交付复用上一条 RD-Bot smoke 任务沉淀的经验。

                        约束：
                        - 不修改身份校验、安全控制、业务逻辑文件。
                        - 不写入真实凭据明文或本次验收的敏感针值。
                        - QA 必须运行真实 shell 命令验证文档存在和标记文本存在。
                        """
        );
    }

    private static String requirementBody(
            MultiAgentProductionAcceptanceProfile profile,
            String title,
            String expectedResult,
            String requirementContent
    ) throws IOException {
        boolean followUp = title.toLowerCase(java.util.Locale.ROOT).contains("follow-up");
        String smokeDocumentPath = followUp
                ? "docs/rd-bot-production-smoke-follow-up.md"
                : "docs/rd-bot-production-smoke.md";
        String smokeMarker = followUp
                ? "multi-agent-production-smoke-follow-up"
                : "multi-agent-production-smoke";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("priority", "P2");
        body.put("repositoryUrl", profile.repositoryUrl());
        body.put("repoOwner", profile.repoOwner());
        body.put("repoName", profile.repoName());
        body.put("baseBranch", profile.baseBranch());
        body.put("expectedResult", expectedResult);
        body.put("acceptanceCriteria", List.of(
                "`" + smokeDocumentPath + "` 必须存在且非空",
                "文档必须包含 `" + smokeMarker + "` 标记文本",
                "QA 必须运行 `test -s " + smokeDocumentPath + "` 和 `grep -q \""
                        + smokeMarker + "\" " + smokeDocumentPath + "` 等真实命令",
                "产物不得包含真实凭据明文或本次验收敏感针值"
        ));
        body.put("autoExecute", profile.autoExecute());
        body.put("materials", List.of(
                Map.of(
                        "materialType", "REQUIREMENT_DOC",
                        "sourceType", "MANUAL_TEXT",
                        "title", "真实生产 smoke 可交付需求",
                        "content", requirementContent,
                        "mimeType", "text/markdown"
                ),
                Map.of(
                        "materialType", "TECHNICAL_DESIGN",
                        "sourceType", "MANUAL_TEXT",
                        "title", "架构方案约束",
                        "content", "方案必须说明影响文件、实现步骤、验收映射、测试命令和回滚策略；本次只允许文档级改动。",
                        "mimeType", "text/markdown"
                ),
                Map.of(
                        "materialType", "TEST_LOG",
                        "sourceType", "MANUAL_TEXT",
                        "title", "QA 测试要求",
                        "content", """
                                QA 必须运行真实 shell 命令，逐条记录验收标准、命令、状态和日志引用。
                                建议命令：
                                - test -s %s
                                - grep -q "%s" %s
                                """.formatted(smokeDocumentPath, smokeMarker, smokeDocumentPath),
                        "mimeType", "text/markdown"
                )
        ));
        return OBJECT_MAPPER.writeValueAsString(body);
    }

    private static void assertNoSecretNeedles(
            MultiAgentProductionAcceptanceProfile profile,
            List<String> values
    ) {
        for (String needle : profile.secretScanNeedles()) {
            for (String value : values) {
                assertFalse(value.contains(needle), "production artifact leaked configured secret needle");
            }
        }
    }

    private static JsonNode waitForCompletedTask(
            MultiAgentProductionAcceptanceProfile profile,
            String taskId
    ) throws Exception {
        long deadline = System.nanoTime() + profile.completionTimeout().toNanos();
        JsonNode detail = OBJECT_MAPPER.createObjectNode();
        while (System.nanoTime() <= deadline) {
            detail = getJson(profile.baseUrl(), "/admin/rd-tasks/" + taskId, profile.requestTimeout());
            String status = detail.path("status").asText("");
            if ("COMPLETED".equals(status) || terminalFailureStatus(status)) {
                return detail;
            }
            Thread.sleep(profile.pollInterval().toMillis());
        }
        throw new AssertionError("requirement task did not reach COMPLETED before timeout: taskId="
                + taskId
                + ", timeoutSeconds=" + profile.completionTimeout().toSeconds()
                + ", lastStatus=" + detail.path("status").asText(""));
    }

    private static boolean terminalFailureStatus(String status) {
        return "REJECTED".equals(status)
                || "FAILED_RETRYABLE".equals(status)
                || "FAILED_NEEDS_HUMAN".equals(status)
                || "CANCELLED".equals(status)
                || "DEAD_LETTERED".equals(status);
    }

    private static JsonNode postJson(String baseUrl, String path, String body, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(baseUrl, path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "POST " + path + " failed with status " + response.statusCode() + ": " + response.body());
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static JsonNode getJson(String baseUrl, String path, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(baseUrl, path))
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "GET " + path + " failed with status " + response.statusCode() + ": " + response.body());
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static URI uri(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + path);
    }

    private record StageSnapshot(
            String stageRunId,
            String status,
            String providerName,
            String providerAttemptsJson,
            String contextPackageId,
            String promptArtifactId,
            String resultArtifactId,
            int stageArtifactCount,
            int stageArtifactPreviewCount
    ) {
        private StageSnapshot {
            stageRunId = stageRunId == null ? "" : stageRunId.strip();
            status = status == null ? "" : status.strip();
            providerName = providerName == null ? "" : providerName.strip();
            providerAttemptsJson = providerAttemptsJson == null || providerAttemptsJson.isBlank()
                    ? "[]"
                    : providerAttemptsJson.strip();
            contextPackageId = contextPackageId == null ? "" : contextPackageId.strip();
            promptArtifactId = promptArtifactId == null ? "" : promptArtifactId.strip();
            resultArtifactId = resultArtifactId == null ? "" : resultArtifactId.strip();
            stageArtifactCount = Math.max(stageArtifactCount, 0);
            stageArtifactPreviewCount = Math.max(stageArtifactPreviewCount, 0);
        }
    }

    private record QaReportEvidence(
            boolean validated,
            int acceptanceResultCount,
            int passedAcceptanceResultCount,
            int validationCommandCount,
            int validationLogArtifactCount,
            String errorMessage,
            String contentPreview
    ) {
        private QaReportEvidence {
            acceptanceResultCount = Math.max(acceptanceResultCount, 0);
            passedAcceptanceResultCount = Math.max(passedAcceptanceResultCount, 0);
            validationCommandCount = Math.max(validationCommandCount, 0);
            validationLogArtifactCount = Math.max(validationLogArtifactCount, 0);
            errorMessage = errorMessage == null ? "" : errorMessage.strip();
            contentPreview = contentPreview == null ? "" : contentPreview;
        }

        private static QaReportEvidence empty() {
            return new QaReportEvidence(false, 0, 0, 0, 0, "QA report evidence was not collected", "");
        }
    }

    private record SolutionPlanEvidence(
            boolean validated,
            int implementationStepCount,
            int affectedFileCount,
            int acceptanceMappingCount,
            int testPlanStepCount,
            boolean codingPromptReferencesSolutionPlan,
            String errorMessage,
            String contentPreview,
            String codingPromptPreview
    ) {
        private SolutionPlanEvidence {
            implementationStepCount = Math.max(implementationStepCount, 0);
            affectedFileCount = Math.max(affectedFileCount, 0);
            acceptanceMappingCount = Math.max(acceptanceMappingCount, 0);
            testPlanStepCount = Math.max(testPlanStepCount, 0);
            errorMessage = errorMessage == null ? "" : errorMessage.strip();
            contentPreview = contentPreview == null ? "" : contentPreview;
            codingPromptPreview = codingPromptPreview == null ? "" : codingPromptPreview;
        }

        private static SolutionPlanEvidence empty() {
            return new SolutionPlanEvidence(
                    false,
                    0,
                    0,
                    0,
                    0,
                    false,
                    "solution plan evidence was not collected",
                    "",
                    ""
            );
        }
    }

    private record SecretScanEvidence(
            boolean validated,
            int scannedValueCount,
            int scannedArtifactPreviewCount,
            boolean pullRequestMetadataScanned
    ) {
        private SecretScanEvidence {
            scannedValueCount = Math.max(scannedValueCount, 0);
            scannedArtifactPreviewCount = Math.max(scannedArtifactPreviewCount, 0);
        }

        private static SecretScanEvidence empty() {
            return new SecretScanEvidence(false, 0, 0, false);
        }
    }

    private record ExperienceRetrievalEvidence(
            boolean validated,
            String followUpTaskId,
            int retrievedExperienceEvidenceCount
    ) {
        private ExperienceRetrievalEvidence {
            followUpTaskId = followUpTaskId == null ? "" : followUpTaskId.strip();
            retrievedExperienceEvidenceCount = Math.max(retrievedExperienceEvidenceCount, 0);
        }

        private static ExperienceRetrievalEvidence empty() {
            return new ExperienceRetrievalEvidence(false, "", 0);
        }
    }
}
