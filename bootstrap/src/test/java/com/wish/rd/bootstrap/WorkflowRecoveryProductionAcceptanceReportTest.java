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

class WorkflowRecoveryProductionAcceptanceReportTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRequireRealWorkflowRecoveryProperties() {
        List<String> missing = WorkflowRecoveryProductionAcceptanceProfile.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.workflow.recovery.smoke.production-evidence"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.base-url"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.postgres-url"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.task-id"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.startup-log-evidence-uri"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.database-snapshot-evidence-uri"));
    }

    @Test
    void shouldRejectMockOrLocalRecoveryEvidenceUris() {
        Map<String, String> properties = validProperties(
                "mock://rd-bot/recovery/startup.log",
                "file:///tmp/rd-bot/recovery/database.json"
        );

        List<String> missing = WorkflowRecoveryProductionAcceptanceProfile.missingRequiredProperties(properties);

        assertTrue(missing.contains("rd.workflow.recovery.smoke.startup-log-evidence-uri=http(s)|s3|rd-artifact"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.database-snapshot-evidence-uri=http(s)|s3|rd-artifact"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowRecoveryProductionAcceptanceProfile.from(properties));
    }

    @Test
    void shouldBuildProfileWithoutLeakingSecretsInDescription() {
        WorkflowRecoveryProductionAcceptanceProfile profile =
                WorkflowRecoveryProductionAcceptanceProfile.from(validProperties());

        assertEquals("123456", profile.taskId());
        assertEquals(2, profile.stageRunCountBeforeRestart());
        assertEquals(5, profile.stageEventCountBeforeRestart());
        assertEquals(1, profile.retryAttemptCount());
        assertEquals(2, profile.retainedRetryArtifactCount());
        assertEquals(List.of("postgres-secret"), profile.secretScanNeedles());
        assertFalse(profile.describeMissingRequirements().contains("postgres-secret"));
    }

    @Test
    void shouldWritePassedReportAsMultiAgentCompatibleRecoverySidecar() throws Exception {
        WorkflowRecoveryProductionAcceptanceProfile profile =
                WorkflowRecoveryProductionAcceptanceProfile.from(validProperties());
        WorkflowRecoveryProductionAcceptanceReport report = new WorkflowRecoveryProductionAcceptanceReport(
                tempDir,
                Clock.fixed(Instant.parse("2026-07-03T11:30:00Z"), ZoneOffset.UTC)
        );
        WorkflowRecoveryProductionAcceptanceReport.WorkflowRecoverySmokeEvidence evidence =
                new WorkflowRecoveryProductionAcceptanceReport.WorkflowRecoverySmokeEvidence(
                        profile,
                        4,
                        14,
                        0,
                        true,
                        List.of("EXECUTING", "VALIDATING", "PR_CREATING", "COMMITTED", "REPORTING", "COMPLETED")
                );

        Path markdown = report.writePassed(evidence);
        Path json = markdown.resolveSibling(markdown.getFileName().toString().replace(".md", ".json"));

        assertTrue(Files.readString(markdown).contains("PASSED_WORKFLOW_RECOVERY_SMOKE"));
        assertTrue(Files.readString(markdown).contains("| 9 | 状态机可恢复且不会重复派发 | PASSED |"));
        assertTrue(Files.isRegularFile(json));
        WorkflowRecoveryEvidenceFile loaded = WorkflowRecoveryEvidenceFile.from(multiAgentProfile(json));
        assertTrue(loaded.validated());
        assertEquals("123456", loaded.taskId());
        assertEquals(4, loaded.stageRunCountAfterRestart());
        assertEquals(14, loaded.stageEventCountAfterRestart());
    }

    @Test
    void shouldWriteSkippedReportWithConcreteWorkflowRecoveryCommand() throws Exception {
        WorkflowRecoveryProductionAcceptanceReport report = new WorkflowRecoveryProductionAcceptanceReport(
                tempDir,
                Clock.fixed(Instant.parse("2026-07-03T11:31:00Z"), ZoneOffset.UTC)
        );

        Path markdown = report.writeSkipped(List.of("rd.workflow.recovery.smoke.base-url"));
        String content = Files.readString(markdown);

        assertTrue(content.contains("WorkflowRecoveryRealSmokeTest"));
        assertTrue(content.contains("-Drd.workflow.recovery.smoke.production-evidence=true"));
        assertTrue(content.contains("-Drd.workflow.recovery.smoke.base-url=<rd-bot-base-url>"));
        assertTrue(content.contains("| 9 | 状态机可恢复且不会重复派发 | NOT_RUN |"));
    }

    private static Map<String, String> validProperties() {
        return validProperties(
                "s3://rd-bot-qa/recovery/startup.log",
                "s3://rd-bot-qa/recovery/database-snapshot.json"
        );
    }

    private static Map<String, String> validProperties(
            String startupLogEvidenceUri,
            String databaseSnapshotEvidenceUri
    ) {
        return Map.ofEntries(
                entry("rd.workflow.recovery.smoke.production-evidence", "true"),
                entry("rd.workflow.recovery.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.workflow.recovery.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.workflow.recovery.smoke.executed-by", "qa-runner"),
                entry("rd.workflow.recovery.smoke.base-url", "http://127.0.0.1:8080"),
                entry("rd.workflow.recovery.smoke.postgres-url", "jdbc:postgresql://127.0.0.1:5432/rd_bot"),
                entry("rd.workflow.recovery.smoke.postgres-user", "rd_bot"),
                entry("rd.workflow.recovery.smoke.postgres-password", "postgres-secret"),
                entry("rd.workflow.recovery.smoke.task-id", "123456"),
                entry("rd.workflow.recovery.smoke.stage-run-count-before-restart", "2"),
                entry("rd.workflow.recovery.smoke.stage-event-count-before-restart", "5"),
                entry("rd.workflow.recovery.smoke.retry-attempt-count", "1"),
                entry("rd.workflow.recovery.smoke.retained-retry-artifact-count", "2"),
                entry("rd.workflow.recovery.smoke.startup-log-evidence-uri", startupLogEvidenceUri),
                entry("rd.workflow.recovery.smoke.database-snapshot-evidence-uri", databaseSnapshotEvidenceUri),
                entry("rd.workflow.recovery.smoke.secret-scan-needles", "postgres-secret")
        );
    }

    private static MultiAgentProductionAcceptanceProfile multiAgentProfile(Path recoveryEvidenceJson) {
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
                entry("rd.multi-agent.smoke.recovery-evidence-json", recoveryEvidenceJson.toString())
        ), Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }
}
