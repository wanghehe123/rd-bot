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

class QaFailureBlockerEvidenceFileTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidatedQaFailureBlockerEvidenceFromSameProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("qa-failure-blocker-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                false,
                false,
                true
        ));

        QaFailureBlockerEvidenceFile evidence = QaFailureBlockerEvidenceFile.from(profile(evidenceJson));

        assertTrue(evidence.validated());
        assertEquals("task-qa-failed", evidence.taskId());
        assertEquals("stage-qa-agent", evidence.stageRunId());
        assertEquals("FAILED_NEEDS_HUMAN", evidence.taskStatus());
        assertEquals("FAILED_NEEDS_HUMAN", evidence.qaStageStatus());
        assertEquals("FAILED", evidence.executionResultStatus());
        assertEquals("artifact-qa-report", evidence.qaReportArtifactId());
        assertEquals(1, evidence.failedAcceptanceCount());
        assertEquals(3, evidence.acceptanceResultCount());
        assertEquals(3, evidence.validationCommandCount());
        assertEquals(3, evidence.validationLogArtifactCount());
        assertFalse(evidence.prCreated());
        assertFalse(evidence.successReportCreated());
        assertTrue(evidence.blockedBeforePrCreating());
        assertTrue(evidence.feishuAlertDelivered());
        assertEquals("om-qa-failed", evidence.feishuAlertMessageId());
        assertTrue(evidence.toReportEvidence().validated());
    }

    @Test
    void shouldRejectQaFailureBlockerEvidenceFromDifferentEnvironment() throws Exception {
        Path evidenceJson = tempDir.resolve("qa-failure-blocker-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "other-env",
                false,
                false,
                true
        ));

        QaFailureBlockerEvidenceFile evidence = QaFailureBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
        assertFalse(evidence.toReportEvidence().validated());
    }

    @Test
    void shouldRejectQaFailureBlockerEvidenceWhenDeliveryWasNotBlocked() throws Exception {
        Path evidenceJson = tempDir.resolve("qa-failure-blocker-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                true,
                true,
                false
        ));

        QaFailureBlockerEvidenceFile evidence = QaFailureBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectQaFailureBlockerEvidenceWhenFeishuAlertTypeIsWrong() throws Exception {
        Path evidenceJson = tempDir.resolve("qa-failure-blocker-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                false,
                false,
                true
        ).replace(
                "\"feishuAlertType\": \"QA_FAILED\"",
                "\"feishuAlertType\": \"DELIVERY_REVIEW_FAILED\""
        ));

        QaFailureBlockerEvidenceFile evidence = QaFailureBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectQaFailureBlockerEvidenceFromNonQaRole() throws Exception {
        Path evidenceJson = tempDir.resolve("qa-failure-blocker-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                false,
                false,
                true
        ).replace(
                "\"role\": \"QA_AGENT\"",
                "\"role\": \"CODING_AGENT\""
        ));

        QaFailureBlockerEvidenceFile evidence = QaFailureBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectQaFailureBlockerEvidenceWithoutDurableQaArtifacts() throws Exception {
        Path evidenceJson = tempDir.resolve("qa-failure-blocker-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                false,
                false,
                true
        ).replace(
                "\"qaReportArtifactUri\": \"s3://rd-bot-qa/qa-failure/qa-report.json\"",
                "\"qaReportArtifactUri\": \"qa-runs/qa-failure/qa-report.json\""
        ));

        QaFailureBlockerEvidenceFile evidence = QaFailureBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectQaFailureBlockerEvidenceWithLocalFileArtifacts() throws Exception {
        Path evidenceJson = tempDir.resolve("qa-failure-blocker-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                false,
                false,
                true
        ).replace(
                "\"qaReportArtifactUri\": \"s3://rd-bot-qa/qa-failure/qa-report.json\"",
                "\"qaReportArtifactUri\": \"file:///tmp/rd-bot/qa-failure/qa-report.json\""
        ));

        QaFailureBlockerEvidenceFile evidence = QaFailureBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    private static String validEvidenceJson(
            String environmentId,
            boolean prCreated,
            boolean successReportCreated,
            boolean blockedBeforePrCreating
    ) {
        return """
                {
                  "qaFailureBlockerEvidenceValidated": true,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "%s",
                  "executedBy": "qa-runner",
                  "taskId": "task-qa-failed",
                  "stageRunId": "stage-qa-agent",
                  "role": "QA_AGENT",
                  "taskStatus": "FAILED_NEEDS_HUMAN",
                  "qaStageStatus": "FAILED_NEEDS_HUMAN",
                  "executionResultStatus": "FAILED",
                  "qaReportArtifactId": "artifact-qa-report",
                  "qaReportArtifactUri": "s3://rd-bot-qa/qa-failure/qa-report.json",
                  "validationLogArtifactUris": [
                    "s3://rd-bot-qa/qa-failure/logs/acceptance-1.log",
                    "s3://rd-bot-qa/qa-failure/logs/acceptance-2.log",
                    "s3://rd-bot-qa/qa-failure/logs/acceptance-3.log"
                  ],
                  "failedAcceptanceCount": 1,
                  "acceptanceResultCount": 3,
                  "validationCommandCount": 3,
                  "validationLogArtifactCount": 3,
                  "prCreated": %s,
                  "successReportCreated": %s,
                  "blockedBeforePrCreating": %s,
                  "feishuAlertDelivered": true,
                  "feishuAlertType": "QA_FAILED",
                  "feishuAlertMessageId": "om-qa-failed"
                }
                """.formatted(environmentId, prCreated, successReportCreated, blockedBeforePrCreating);
    }

    private static MultiAgentProductionAcceptanceProfile profile(Path qaFailureEvidenceJson) {
        return MultiAgentProductionAcceptanceProfile.from(Map.ofEntries(
                entry("rd.multi-agent.smoke.production-evidence", "true"),
                entry("rd.multi-agent.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.multi-agent.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.multi-agent.smoke.executed-by", "qa-runner"),
                entry("rd.multi-agent.smoke.base-url", "http://127.0.0.1:8080"),
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
                entry("rd.multi-agent.smoke.qa-failure-evidence-json", qaFailureEvidenceJson.toString())
        ), Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }
}
