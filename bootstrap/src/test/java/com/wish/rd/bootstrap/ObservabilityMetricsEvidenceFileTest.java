package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityMetricsEvidenceFileTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidatedObservabilityMetricsEvidenceFromSameProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("observability-metrics-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                200,
                true
        ));

        ObservabilityMetricsEvidenceFile evidence = ObservabilityMetricsEvidenceFile.from(profile(evidenceJson));

        assertTrue(evidence.validated());
        assertEquals("123456", evidence.taskId());
        assertEquals("https://rd-bot.example.com/actuator/prometheus", evidence.metricsEndpointUrl());
        assertEquals(200, evidence.metricsHttpStatus());
        assertTrue(evidence.contextBuildLatencyMetricPresent());
        assertTrue(evidence.repairSuccessRateMetricPresent());
        assertTrue(evidence.validationPassRateMetricPresent());
        assertTrue(evidence.prCreationRateMetricPresent());
        assertTrue(evidence.humanInterventionRateMetricPresent());
        assertTrue(evidence.retryRateMetricPresent());
        assertTrue(evidence.meanTimeToRepairMetricPresent());
        assertTrue(evidence.topFailureCategoriesMetricPresent());
        assertEquals(4, evidence.stageMetricCount());
        assertTrue(evidence.auditTraceQuerySucceeded());
        assertTrue(evidence.remotePrTraceValidated());
        assertEquals(12, evidence.auditTraceLinkCount());
        assertTrue(evidence.toReportEvidence().validated());
    }

    @Test
    void shouldRejectObservabilityMetricsEvidenceFromDifferentEnvironment() throws Exception {
        Path evidenceJson = tempDir.resolve("observability-metrics-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "other-env",
                200,
                true
        ));

        ObservabilityMetricsEvidenceFile evidence = ObservabilityMetricsEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
        assertFalse(evidence.toReportEvidence().validated());
    }

    @Test
    void shouldRejectObservabilityMetricsEvidenceWhenRequiredMetricIsMissing() throws Exception {
        Path evidenceJson = tempDir.resolve("observability-metrics-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                503,
                false
        ));

        ObservabilityMetricsEvidenceFile evidence = ObservabilityMetricsEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectObservabilityMetricsEvidenceFromDifferentBaseUrl() throws Exception {
        Path evidenceJson = tempDir.resolve("observability-metrics-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                200,
                true
        ).replace(
                "\"metricsEndpointUrl\": \"https://rd-bot.example.com/actuator/prometheus\"",
                "\"metricsEndpointUrl\": \"https://other-rd-bot.example.com/actuator/prometheus\""
        ));

        ObservabilityMetricsEvidenceFile evidence = ObservabilityMetricsEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectObservabilityMetricsEvidenceWhenAuditLinksAreNotBoundToTask() throws Exception {
        Path evidenceJson = tempDir.resolve("observability-metrics-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                200,
                true
        ).replace(
                "\"taskBoundAuditTraceLinkCount\": 12",
                "\"taskBoundAuditTraceLinkCount\": 0"
        ));

        ObservabilityMetricsEvidenceFile evidence = ObservabilityMetricsEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectObservabilityMetricsEvidenceWhenObservabilityTaskIdDoesNotMatchTaskId() throws Exception {
        Path evidenceJson = tempDir.resolve("observability-metrics-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                200,
                true
        ).replace(
                "\"observabilityTaskId\": \"123456\"",
                "\"observabilityTaskId\": \"other-task\""
        ));

        ObservabilityMetricsEvidenceFile evidence = ObservabilityMetricsEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    private static String validEvidenceJson(
            String environmentId,
            int metricsHttpStatus,
            boolean prCreationRateMetricPresent
    ) {
        return """
                {
                  "observabilityMetricsEvidenceValidated": true,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "%s",
                  "executedBy": "qa-runner",
                  "observabilityTaskId": "123456",
                  "taskId": "123456",
                  "metricsEndpointUrl": "https://rd-bot.example.com/actuator/prometheus",
                  "metricsHttpStatus": %d,
                  "contextBuildLatencyMetricPresent": true,
                  "repairSuccessRateMetricPresent": true,
                  "validationPassRateMetricPresent": true,
                  "prCreationRateMetricPresent": %s,
                  "humanInterventionRateMetricPresent": true,
                  "retryRateMetricPresent": true,
                  "meanTimeToRepairMetricPresent": true,
                  "topFailureCategoriesMetricPresent": true,
                  "stageMetricCount": 4,
                  "auditTraceQuerySucceeded": true,
                  "remotePrTraceValidated": true,
                  "auditTraceLinkCount": 12,
                  "taskBoundAuditTraceLinkCount": 12
                }
                """.formatted(environmentId, metricsHttpStatus, prCreationRateMetricPresent);
    }

    private static MultiAgentProductionAcceptanceProfile profile(Path observabilityMetricsEvidenceJson) {
        return MultiAgentProductionAcceptanceProfile.from(Map.ofEntries(
                entry("rd.multi-agent.smoke.production-evidence", "true"),
                entry("rd.multi-agent.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.multi-agent.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.multi-agent.smoke.executed-by", "qa-runner"),
                entry("rd.multi-agent.smoke.base-url", "https://rd-bot.example.com"),
                entry("rd.multi-agent.smoke.postgres-url", "jdbc:postgresql://127.0.0.1:5432/rd_bot"),
                entry("rd.multi-agent.smoke.postgres-user", "rd_bot"),
                entry("rd.multi-agent.smoke.postgres-password", "secret"),
                entry("rd.multi-agent.smoke.repository-url", "https://github.com/acme/rd-bot-smoke.git"),
                entry("rd.multi-agent.smoke.repo-owner", "acme"),
                entry("rd.multi-agent.smoke.repo-name", "rd-bot-smoke"),
                entry("rd.multi-agent.smoke.expected-provider-count", "2"),
                entry("rd.multi-agent.smoke.provider-secret-env-names", "LONGCAT_API_KEY,ANTHROPIC_API_KEY"),
                entry("rd.multi-agent.smoke.github-code-platform-mode", "real"),
                entry("rd.multi-agent.smoke.github-auth-mode", "PAT_LOCAL_SMOKE"),
                entry("rd.multi-agent.smoke.github-credential-env-names", "GITHUB_PAT"),
                entry("rd.multi-agent.smoke.secret-scan-needles", "postgres-secret,github-secret"),
                entry("rd.multi-agent.smoke.observability-metrics-evidence-json",
                        observabilityMetricsEvidenceJson.toString())
        ), Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }
}
