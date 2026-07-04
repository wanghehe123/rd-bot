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

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaFailureBlockerProductionAcceptanceReportTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRequireRealQaFailureBlockerProperties() {
        List<String> missing = QaFailureBlockerProductionAcceptanceProfile.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.qa-failure.smoke.production-evidence"));
        assertTrue(missing.contains("rd.qa-failure.smoke.base-url"));
        assertTrue(missing.contains("rd.qa-failure.smoke.postgres-url"));
        assertTrue(missing.contains("rd.qa-failure.smoke.task-id"));
        assertTrue(missing.contains("rd.qa-failure.smoke.qa-report-artifact-uri"));
        assertTrue(missing.contains("rd.qa-failure.smoke.validation-log-artifact-uris"));
        assertTrue(missing.contains("rd.qa-failure.smoke.feishu-alert-message-id"));
    }

    @Test
    void shouldRejectMockOrLocalQaArtifactUris() {
        Map<String, String> properties = validProperties(
                "mock://rd-bot/qa/report.json",
                "s3://rd-bot-qa/qa-failure/logs/acceptance-1.log"
        );

        List<String> missing = QaFailureBlockerProductionAcceptanceProfile.missingRequiredProperties(properties);

        assertTrue(missing.contains("rd.qa-failure.smoke.qa-report-artifact-uri=http(s)|s3|rd-artifact"));
        assertThrows(IllegalArgumentException.class,
                () -> QaFailureBlockerProductionAcceptanceProfile.from(properties));
    }

    @Test
    void shouldRejectDuplicateOrMockValidationLogArtifactUris() {
        Map<String, String> properties = validProperties(
                "s3://rd-bot-qa/qa-failure/qa-report.json",
                "mock://rd-bot/qa/log-1.log,mock://rd-bot/qa/log-1.log"
        );

        List<String> missing = QaFailureBlockerProductionAcceptanceProfile.missingRequiredProperties(properties);

        assertTrue(missing.contains("rd.qa-failure.smoke.validation-log-artifact-uris=http(s)|s3|rd-artifact"));
        assertThrows(IllegalArgumentException.class,
                () -> QaFailureBlockerProductionAcceptanceProfile.from(properties));
    }

    @Test
    void shouldBuildProfileWithoutLeakingSecretsInDescription() {
        QaFailureBlockerProductionAcceptanceProfile profile =
                QaFailureBlockerProductionAcceptanceProfile.from(validProperties());

        assertEquals("task-qa-failed", profile.taskId());
        assertEquals("s3://rd-bot-qa/qa-failure/qa-report.json", profile.qaReportArtifactUri());
        assertEquals(List.of(
                "s3://rd-bot-qa/qa-failure/logs/acceptance-1.log",
                "s3://rd-bot-qa/qa-failure/logs/acceptance-2.log"
        ), profile.validationLogArtifactUris());
        assertEquals("om-qa-failed", profile.feishuAlertMessageId());
        assertEquals(List.of("postgres-secret"), profile.secretScanNeedles());
        assertFalse(profile.describeMissingRequirements().contains("postgres-secret"));
    }

    @Test
    void shouldWritePassedReportAsMultiAgentCompatibleQaFailureSidecar() throws Exception {
        QaFailureBlockerProductionAcceptanceProfile profile =
                QaFailureBlockerProductionAcceptanceProfile.from(validProperties());
        QaFailureBlockerProductionAcceptanceReport report = new QaFailureBlockerProductionAcceptanceReport(
                tempDir,
                Clock.fixed(Instant.parse("2026-07-03T12:10:00Z"), ZoneOffset.UTC)
        );
        QaFailureBlockerProductionAcceptanceReport.QaFailureSmokeEvidence evidence =
                new QaFailureBlockerProductionAcceptanceReport.QaFailureSmokeEvidence(
                        profile,
                        "stage-qa-agent",
                        "FAILED_NEEDS_HUMAN",
                        "FAILED_VALIDATION",
                        "FAILED",
                        "artifact-qa-report",
                        1,
                        2,
                        2,
                        false,
                        false,
                        true
                );

        Path markdown = report.writePassed(evidence);
        Path json = markdown.resolveSibling(markdown.getFileName().toString().replace(".md", ".json"));

        assertTrue(Files.readString(markdown).contains("PASSED_QA_FAILURE_BLOCKER_SMOKE"));
        assertTrue(Files.readString(markdown).contains("| 7 | QA Agent 逐条验收并阻断失败交付 | PASSED |"));
        assertTrue(Files.isRegularFile(json));
        QaFailureBlockerEvidenceFile loaded = QaFailureBlockerEvidenceFile.from(multiAgentProfile(json));
        assertTrue(loaded.validated());
        assertEquals("task-qa-failed", loaded.taskId());
        assertEquals("stage-qa-agent", loaded.stageRunId());
        assertEquals(1, loaded.failedAcceptanceCount());
        assertEquals(2, loaded.acceptanceResultCount());
    }

    @Test
    void shouldWriteSkippedReportWithConcreteQaFailureCommand() throws Exception {
        QaFailureBlockerProductionAcceptanceReport report = new QaFailureBlockerProductionAcceptanceReport(
                tempDir,
                Clock.fixed(Instant.parse("2026-07-03T12:11:00Z"), ZoneOffset.UTC)
        );

        Path markdown = report.writeSkipped(List.of("rd.qa-failure.smoke.base-url"));
        String content = Files.readString(markdown);

        assertTrue(content.contains("QaFailureBlockerRealSmokeTest"));
        assertTrue(content.contains("-Drd.qa-failure.smoke.production-evidence=true"));
        assertTrue(content.contains("-Drd.qa-failure.smoke.task-id=<rd-bot-task-id>"));
        assertTrue(content.contains("| 7 | QA Agent 逐条验收并阻断失败交付 | NOT_RUN |"));
    }

    private static Map<String, String> validProperties() {
        return validProperties(
                "s3://rd-bot-qa/qa-failure/qa-report.json",
                "s3://rd-bot-qa/qa-failure/logs/acceptance-1.log,"
                        + "s3://rd-bot-qa/qa-failure/logs/acceptance-2.log"
        );
    }

    private static Map<String, String> validProperties(
            String qaReportArtifactUri,
            String validationLogArtifactUris
    ) {
        return Map.ofEntries(
                entry("rd.qa-failure.smoke.production-evidence", "true"),
                entry("rd.qa-failure.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.qa-failure.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.qa-failure.smoke.executed-by", "qa-runner"),
                entry("rd.qa-failure.smoke.base-url", "http://127.0.0.1:8080"),
                entry("rd.qa-failure.smoke.postgres-url", "jdbc:postgresql://127.0.0.1:5432/rd_bot"),
                entry("rd.qa-failure.smoke.postgres-user", "rd_bot"),
                entry("rd.qa-failure.smoke.postgres-password", "postgres-secret"),
                entry("rd.qa-failure.smoke.task-id", "task-qa-failed"),
                entry("rd.qa-failure.smoke.qa-report-artifact-uri", qaReportArtifactUri),
                entry("rd.qa-failure.smoke.validation-log-artifact-uris", validationLogArtifactUris),
                entry("rd.qa-failure.smoke.feishu-alert-message-id", "om-qa-failed"),
                entry("rd.qa-failure.smoke.secret-scan-needles", "postgres-secret")
        );
    }

    private static MultiAgentProductionAcceptanceProfile multiAgentProfile(Path qaFailureEvidenceJson) {
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
