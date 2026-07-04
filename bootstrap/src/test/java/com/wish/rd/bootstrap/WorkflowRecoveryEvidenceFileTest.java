package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRecoveryEvidenceFileTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidatedWorkflowRecoveryEvidenceForTheSameProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("workflow-recovery.json");
        Files.writeString(evidenceJson, """
                {
                  "workflowRecoveryEvidenceValidated": true,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "prod-equivalent-a",
                  "executedBy": "qa-runner",
                  "recoveryTaskId": "123456",
                  "taskId": "123456",
                  "stageRunCountBeforeRestart": 2,
                  "stageRunCountAfterRestart": 4,
                  "stageEventCountBeforeRestart": 5,
                  "stageEventCountAfterRestart": 14,
                  "duplicateSuccessfulStageCount": 0,
                  "retryAttemptCount": 1,
                  "retainedRetryArtifactCount": 2,
                  "deliveryReviewApprovedBeforePrCreating": true,
                  "timelineStatuses": [
                    "EXECUTING",
                    "VALIDATING",
                    "PR_CREATING",
                    "COMMITTED",
                    "REPORTING",
                    "COMPLETED"
                  ],
                  "startupLogEvidenceUri": "s3://rd-bot-qa/startup.log",
                  "databaseSnapshotEvidenceUri": "s3://rd-bot-qa/recovery-snapshots.json"
                }
                """);
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        WorkflowRecoveryEvidenceFile evidence = WorkflowRecoveryEvidenceFile.from(profile);

        assertTrue(evidence.validated());
        assertEquals("123456", evidence.taskId());
        assertEquals(2, evidence.stageRunCountBeforeRestart());
        assertEquals(4, evidence.stageRunCountAfterRestart());
        assertEquals(5, evidence.stageEventCountBeforeRestart());
        assertEquals(14, evidence.stageEventCountAfterRestart());
        assertEquals(0, evidence.duplicateSuccessfulStageCount());
        assertEquals(1, evidence.retryAttemptCount());
        assertEquals(2, evidence.retainedRetryArtifactCount());
        assertTrue(evidence.deliveryReviewApprovedBeforePrCreating());
        assertEquals(List.of(
                "EXECUTING",
                "VALIDATING",
                "PR_CREATING",
                "COMMITTED",
                "REPORTING",
                "COMPLETED"
        ), evidence.timelineStatuses());
    }

    @Test
    void shouldLoadRecoveryEvidenceWithoutRetryWhenRestartProvesIdempotentDispatch() throws Exception {
        Path evidenceJson = tempDir.resolve("workflow-recovery.json");
        Files.writeString(evidenceJson, validEvidenceJson()
                .replace("\"retryAttemptCount\": 1", "\"retryAttemptCount\": 0")
                .replace("\"retainedRetryArtifactCount\": 2", "\"retainedRetryArtifactCount\": 0"));
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        WorkflowRecoveryEvidenceFile evidence = WorkflowRecoveryEvidenceFile.from(profile);

        assertTrue(evidence.validated());
        assertEquals(0, evidence.retryAttemptCount());
        assertEquals(0, evidence.retainedRetryArtifactCount());
        assertEquals(0, evidence.duplicateSuccessfulStageCount());
    }

    @Test
    void shouldIgnoreRecoveryEvidenceFromDifferentProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("workflow-recovery.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace("prod-equivalent-a", "another-env"));

        WorkflowRecoveryEvidenceFile evidence = WorkflowRecoveryEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
        assertEquals("", evidence.taskId());
    }

    @Test
    void shouldRejectRecoveryEvidenceWithDuplicateSuccessfulStageRuns() throws Exception {
        Path evidenceJson = tempDir.resolve("workflow-recovery.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"duplicateSuccessfulStageCount\": 0",
                "\"duplicateSuccessfulStageCount\": 1"
        ));

        WorkflowRecoveryEvidenceFile evidence = WorkflowRecoveryEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRecoveryEvidenceWithoutRealBeforeRestartSnapshots() throws Exception {
        Path evidenceJson = tempDir.resolve("workflow-recovery.json");
        Files.writeString(evidenceJson, validEvidenceJson()
                .replace("\"stageRunCountBeforeRestart\": 2", "\"stageRunCountBeforeRestart\": 0")
                .replace("\"stageEventCountBeforeRestart\": 5", "\"stageEventCountBeforeRestart\": 0"));

        WorkflowRecoveryEvidenceFile evidence = WorkflowRecoveryEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRecoveryEvidenceWhenCriticalStatusesFirstAppearOutOfOrder() throws Exception {
        Path evidenceJson = tempDir.resolve("workflow-recovery.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                """
                    "EXECUTING",
                    "VALIDATING",
                    "PR_CREATING",
                    "COMMITTED",
                    "REPORTING",
                    "COMPLETED"
                """,
                """
                    "PR_CREATING",
                    "EXECUTING",
                    "VALIDATING",
                    "PR_CREATING",
                    "COMMITTED",
                    "REPORTING",
                    "COMPLETED"
                """
        ));

        WorkflowRecoveryEvidenceFile evidence = WorkflowRecoveryEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRecoveryEvidenceWhenRecoveryTaskIdDoesNotMatchTaskId() throws Exception {
        Path evidenceJson = tempDir.resolve("workflow-recovery.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"recoveryTaskId\": \"123456\"",
                "\"recoveryTaskId\": \"another-task\""
        ));

        WorkflowRecoveryEvidenceFile evidence = WorkflowRecoveryEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRecoveryEvidenceWithoutDurableEvidenceUris() throws Exception {
        Path evidenceJson = tempDir.resolve("workflow-recovery.json");
        Files.writeString(evidenceJson, validEvidenceJson()
                .replace("\"startupLogEvidenceUri\": \"s3://rd-bot-qa/startup.log\"",
                        "\"startupLogEvidenceUri\": \"mock://rd-bot-qa/startup.log\"")
                .replace("\"databaseSnapshotEvidenceUri\": \"s3://rd-bot-qa/recovery-snapshots.json\"",
                        "\"databaseSnapshotEvidenceUri\": \"recovery-snapshots.json\""));

        WorkflowRecoveryEvidenceFile evidence = WorkflowRecoveryEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRecoveryEvidenceWithLocalFileEvidenceUris() throws Exception {
        Path evidenceJson = tempDir.resolve("workflow-recovery.json");
        Files.writeString(evidenceJson, validEvidenceJson()
                .replace("\"startupLogEvidenceUri\": \"s3://rd-bot-qa/startup.log\"",
                        "\"startupLogEvidenceUri\": \"file:///tmp/rd-bot/startup.log\"")
                .replace("\"databaseSnapshotEvidenceUri\": \"s3://rd-bot-qa/recovery-snapshots.json\"",
                        "\"databaseSnapshotEvidenceUri\": \"file:///tmp/rd-bot/recovery-snapshots.json\""));

        WorkflowRecoveryEvidenceFile evidence = WorkflowRecoveryEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    private static String validEvidenceJson() {
        return """
                {
                  "workflowRecoveryEvidenceValidated": true,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "prod-equivalent-a",
                  "executedBy": "qa-runner",
                  "recoveryTaskId": "123456",
                  "taskId": "123456",
                  "stageRunCountBeforeRestart": 2,
                  "stageRunCountAfterRestart": 4,
                  "stageEventCountBeforeRestart": 5,
                  "stageEventCountAfterRestart": 14,
                  "duplicateSuccessfulStageCount": 0,
                  "retryAttemptCount": 1,
                  "retainedRetryArtifactCount": 2,
                  "deliveryReviewApprovedBeforePrCreating": true,
                  "timelineStatuses": [
                    "EXECUTING",
                    "VALIDATING",
                    "PR_CREATING",
                    "COMMITTED",
                    "REPORTING",
                    "COMPLETED"
                  ],
                  "startupLogEvidenceUri": "s3://rd-bot-qa/startup.log",
                  "databaseSnapshotEvidenceUri": "s3://rd-bot-qa/recovery-snapshots.json"
                }
                """;
    }

    private static MultiAgentProductionAcceptanceProfile profile(Path recoveryEvidenceJson) {
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
