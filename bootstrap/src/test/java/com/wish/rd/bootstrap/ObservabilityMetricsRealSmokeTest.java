package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observability metrics production smoke. Disabled by default and validates an existing real task.
 *
 * <p>Example:
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=ObservabilityMetricsRealSmokeTest \
 *   -Drd.integration.observability-metrics.enabled=true \
 *   -Drd.observability-metrics.smoke.production-evidence=true \
 *   -Drd.observability-metrics.smoke.rd-bot-version=0.1.0-smoke \
 *   -Drd.observability-metrics.smoke.environment-id=prod-equivalent-a \
 *   -Drd.observability-metrics.smoke.executed-by=qa-runner \
 *   -Drd.observability-metrics.smoke.base-url=http://127.0.0.1:8080 \
 *   -Drd.observability-metrics.smoke.postgres-url=jdbc:postgresql://127.0.0.1:5432/rd_bot \
 *   -Drd.observability-metrics.smoke.postgres-user=rd_bot \
 *   -Drd.observability-metrics.smoke.postgres-password=$RD_BOT_POSTGRES_PASSWORD \
 *   -Drd.observability-metrics.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
 *   -Drd.observability-metrics.smoke.github-pr-remote-evidence-json="$GITHUB_PR_REMOTE_EVIDENCE_JSON" \
 *   -Drd.observability-metrics.smoke.secret-scan-needles=<secret-scan-needles> \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.observability-metrics.enabled", matches = "true")
class ObservabilityMetricsRealSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void validatesObservabilityMetricsAgainstRealHttpPostgresAndRemotePrTrace() throws Exception {
        ObservabilityMetricsProductionAcceptanceReport report =
                ObservabilityMetricsProductionAcceptanceReport.fromSystemProperties();
        Map<String, String> properties = ObservabilityMetricsProductionAcceptanceProfile.systemProperties();
        List<String> missing = missingRequiredProperties(properties);
        if (!missing.isEmpty()) {
            java.nio.file.Path skippedReport = report.writeSkipped(missing);
            ProductionSmokePreconditions.requireReady("observability-metrics", missing, skippedReport);
        }
        ObservabilityMetricsProductionAcceptanceProfile profile =
                ObservabilityMetricsProductionAcceptanceProfile.from(properties);
        ObservabilityMetricsProductionAcceptanceReport.ObservabilityMetricsSmokeEvidence evidence =
                initialEvidence(profile);
        try {
            JsonNode detail = getJson(profile.baseUrl(), "/admin/rd-tasks/" + profile.taskId(), requestTimeout());
            assertEquals(profile.taskId(), detail.path("taskId").asText(""),
                    "Observability metrics smoke must validate the configured taskId");

            TextHttpResponse metricsResponse = getText(profile.metricsEndpointUrl(), requestTimeout());
            assertEquals(200, metricsResponse.statusCode(), "metrics endpoint must return HTTP 200");
            MetricsEvidence metrics = metricsEvidence(metricsResponse.body());
            RemotePrEvidence remotePrEvidence = remotePrEvidence(profile);
            AuditTraceEvidence auditTrace;
            try (Connection connection = DriverManager.getConnection(
                    profile.postgresUrl(),
                    profile.postgresUser(),
                    profile.postgresPassword()
            )) {
                auditTrace = auditTraceEvidence(connection, profile.taskId(), remotePrEvidence.validated());
            }
            evidence = new ObservabilityMetricsProductionAcceptanceReport.ObservabilityMetricsSmokeEvidence(
                    profile,
                    metricsResponse.statusCode(),
                    metrics.contextBuildLatencyMetricPresent(),
                    metrics.repairSuccessRateMetricPresent(),
                    metrics.validationPassRateMetricPresent(),
                    metrics.prCreationRateMetricPresent(),
                    metrics.humanInterventionRateMetricPresent(),
                    metrics.retryRateMetricPresent(),
                    metrics.meanTimeToRepairMetricPresent(),
                    metrics.topFailureCategoriesMetricPresent(),
                    metrics.stageMetricCount(),
                    auditTrace.querySucceeded(),
                    remotePrEvidence.validated(),
                    auditTrace.auditTraceLinkCount(),
                    auditTrace.taskBoundAuditTraceLinkCount()
            );
            assertNoSecretNeedles(profile, List.of(detail.toString(), metricsResponse.body()));
            Path reportPath = report.writePassed(evidence);
            System.out.println("[smoke] observability-metrics taskId=" + profile.taskId()
                    + " stageMetricCount=" + metrics.stageMetricCount()
                    + " auditTraceLinkCount=" + auditTrace.auditTraceLinkCount()
                    + " report=" + reportPath.toAbsolutePath());
        } catch (Throwable failure) {
            Path reportPath = report.writeFailed(evidence, failure);
            System.out.println("[smoke] observability-metrics failure report=" + reportPath.toAbsolutePath());
            throw failure;
        }
    }

    static List<String> missingRequiredProperties(Map<String, String> properties) {
        return ObservabilityMetricsProductionAcceptanceProfile.missingRequiredProperties(properties);
    }

    private static ObservabilityMetricsProductionAcceptanceReport.ObservabilityMetricsSmokeEvidence initialEvidence(
            ObservabilityMetricsProductionAcceptanceProfile profile
    ) {
        return new ObservabilityMetricsProductionAcceptanceReport.ObservabilityMetricsSmokeEvidence(
                profile,
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

    private static MetricsEvidence metricsEvidence(String metricsBody) {
        MetricsEvidence evidence = new MetricsEvidence(
                metricPresent(metricsBody, "rd_bot_context_build_latency", "context_build_latency",
                        "contextBuildLatency"),
                metricPresent(metricsBody, "rd_bot_repair_success_rate", "repair_success_rate",
                        "repairSuccessRate"),
                metricPresent(metricsBody, "rd_bot_validation_pass_rate", "validation_pass_rate",
                        "validationPassRate"),
                metricPresent(metricsBody, "rd_bot_pr_creation_rate", "pr_creation_rate", "prCreationRate"),
                metricPresent(metricsBody, "rd_bot_human_intervention_rate", "human_intervention_rate",
                        "humanInterventionRate"),
                metricPresent(metricsBody, "rd_bot_retry_rate", "retry_rate", "retryRate"),
                metricPresent(metricsBody, "rd_bot_mean_time_to_repair", "mean_time_to_repair",
                        "meanTimeToRepair"),
                metricPresent(metricsBody, "rd_bot_top_failure_categories", "top_failure_categories",
                        "topFailureCategories", "failure_category"),
                stageMetricCount(metricsBody)
        );
        assertTrue(evidence.contextBuildLatencyMetricPresent(), "context build latency metric must be present");
        assertTrue(evidence.repairSuccessRateMetricPresent(), "repair success rate metric must be present");
        assertTrue(evidence.validationPassRateMetricPresent(), "validation pass rate metric must be present");
        assertTrue(evidence.prCreationRateMetricPresent(), "PR creation rate metric must be present");
        assertTrue(evidence.humanInterventionRateMetricPresent(), "human intervention rate metric must be present");
        assertTrue(evidence.retryRateMetricPresent(), "retry rate metric must be present");
        assertTrue(evidence.meanTimeToRepairMetricPresent(), "mean time to repair metric must be present");
        assertTrue(evidence.topFailureCategoriesMetricPresent(), "top failure categories metric must be present");
        assertTrue(evidence.stageMetricCount() >= 4, "metrics must expose at least four role/stage series");
        return evidence;
    }

    private static RemotePrEvidence remotePrEvidence(ObservabilityMetricsProductionAcceptanceProfile profile)
            throws Exception {
        Path path = Path.of(profile.githubPrRemoteEvidenceJson()).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(path), "GitHub PR remote evidence JSON must be a regular file");
        JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
        assertTrue(root.path("githubPrRemoteEvidenceValidated").asBoolean(false),
                "remote PR evidence must be validated");
        assertTrue(root.path("remotePrTraceValidated").asBoolean(false),
                "remote PR trace must be validated");
        assertEquals(profile.rdBotVersion(), root.path("rdBotVersion").asText(""),
                "remote PR evidence rdBotVersion must match");
        assertEquals(profile.environmentId(), root.path("environmentId").asText(""),
                "remote PR evidence environmentId must match");
        assertEquals(profile.executedBy(), root.path("executedBy").asText(""),
                "remote PR evidence executedBy must match");
        assertEquals(profile.taskId(), root.path("taskId").asText(""),
                "remote PR evidence taskId must match");
        assertEquals("requirement/" + profile.taskId(), root.path("workBranch").asText(""),
                "remote PR evidence workBranch must bind to taskId");
        assertTrue(root.path("pullRequestBodyContainsTaskId").asBoolean(false),
                "remote PR body must contain taskId");
        assertTrue(root.path("pullRequestBodyContainsExactTaskId").asBoolean(false),
                "remote PR body must contain exact taskId token");
        assertTrue(root.path("pullRequestBodyContainsArtifactLink").asBoolean(false),
                "remote PR body must contain artifact link");
        assertTrue(root.path("secretScanEvidenceValidated").asBoolean(false),
                "remote PR secret scan evidence must be validated");
        assertFalse(root.path("secretLeakFound").asBoolean(true),
                "remote PR evidence must not leak configured secrets");
        assertTrue(root.path("secretScannedValueCount").asInt(0) > 0,
                "remote PR evidence must scan at least one secret value");
        return new RemotePrEvidence(true);
    }

    private static AuditTraceEvidence auditTraceEvidence(
            Connection connection,
            String taskId,
            boolean remotePrTraceValidated
    ) throws Exception {
        long numericTaskId = Long.parseLong(taskId);
        int stageRunCount = count(connection, "SELECT COUNT(*) FROM rd_agent_stage_runs WHERE task_id = ?",
                numericTaskId);
        int stageEventCount = count(connection, "SELECT COUNT(*) FROM rd_agent_stage_events WHERE task_id = ?",
                numericTaskId);
        int roleContextPackageCount = count(
                connection,
                "SELECT COUNT(*) FROM rd_role_context_packages WHERE task_id = ?",
                numericTaskId
        );
        int artifactLinkCount = count(
                connection,
                "SELECT COUNT(*) FROM rd_agent_stage_artifacts WHERE task_id = ?",
                numericTaskId
        );
        int experienceLinkCount = count(
                connection,
                "SELECT COUNT(*) FROM rd_experience_entries WHERE task_id = ? AND source_artifact_id IS NOT NULL",
                numericTaskId
        );
        int auditTraceLinkCount = stageRunCount
                + stageEventCount
                + roleContextPackageCount
                + artifactLinkCount
                + experienceLinkCount
                + (remotePrTraceValidated ? 1 : 0);
        boolean querySucceeded = stageRunCount >= 4
                && stageEventCount >= 4
                && roleContextPackageCount >= 4
                && artifactLinkCount >= 8
                && experienceLinkCount > 0
                && remotePrTraceValidated
                && auditTraceLinkCount >= 8;
        assertTrue(querySucceeded, "audit trace query must prove task-bound stage/context/artifact/experience links");
        return new AuditTraceEvidence(querySucceeded, auditTraceLinkCount, auditTraceLinkCount);
    }

    private static int count(Connection connection, String sql, long taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "count query must return a row");
                return resultSet.getInt(1);
            }
        }
    }

    private static JsonNode getJson(String baseUrl, String path, Duration timeout) throws Exception {
        TextHttpResponse response = getText(uri(baseUrl, path).toString(), timeout);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "GET " + path + " failed with status " + response.statusCode() + ": " + response.body());
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static TextHttpResponse getText(String url, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new TextHttpResponse(response.statusCode(), response.body());
    }

    private static URI uri(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + path);
    }

    private static Duration requestTimeout() {
        String value = System.getProperty("rd.observability-metrics.smoke.request-timeout-seconds", "30");
        try {
            return Duration.ofSeconds(Math.max(1, Long.parseLong(value.strip())));
        } catch (NumberFormatException exception) {
            return Duration.ofSeconds(30);
        }
    }

    private static boolean metricPresent(String body, String... aliases) {
        String safeBody = safe(body);
        return Arrays.stream(aliases == null ? new String[0] : aliases)
                .map(ObservabilityMetricsRealSmokeTest::safe)
                .filter(alias -> !alias.isBlank())
                .anyMatch(safeBody::contains);
    }

    private static int stageMetricCount(String body) {
        return (int) safe(body).lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .filter(line -> metricLineContains(line, "rd_bot_agent_stage", "agent_stage", "rd_agent_stage",
                        "workflow_stage"))
                .count();
    }

    private static boolean metricLineContains(String line, String... aliases) {
        return Arrays.stream(aliases)
                .anyMatch(alias -> line.contains(alias));
    }

    private static void assertNoSecretNeedles(
            ObservabilityMetricsProductionAcceptanceProfile profile,
            List<String> values
    ) {
        for (String needle : profile.secretScanNeedles()) {
            String normalized = safe(needle);
            if (normalized.isBlank()) {
                continue;
            }
            for (String value : values) {
                assertFalse(value != null && value.contains(normalized),
                        "Observability metrics evidence must not contain configured secret needle");
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record TextHttpResponse(int statusCode, String body) {
        TextHttpResponse {
            statusCode = Math.max(statusCode, 0);
            body = safe(body);
        }
    }

    private record MetricsEvidence(
            boolean contextBuildLatencyMetricPresent,
            boolean repairSuccessRateMetricPresent,
            boolean validationPassRateMetricPresent,
            boolean prCreationRateMetricPresent,
            boolean humanInterventionRateMetricPresent,
            boolean retryRateMetricPresent,
            boolean meanTimeToRepairMetricPresent,
            boolean topFailureCategoriesMetricPresent,
            int stageMetricCount
    ) {
        MetricsEvidence {
            stageMetricCount = Math.max(stageMetricCount, 0);
        }
    }

    private record RemotePrEvidence(boolean validated) {
    }

    private record AuditTraceEvidence(
            boolean querySucceeded,
            int auditTraceLinkCount,
            int taskBoundAuditTraceLinkCount
    ) {
        AuditTraceEvidence {
            auditTraceLinkCount = Math.max(auditTraceLinkCount, 0);
            taskBoundAuditTraceLinkCount = Math.max(taskBoundAuditTraceLinkCount, 0);
        }
    }
}
