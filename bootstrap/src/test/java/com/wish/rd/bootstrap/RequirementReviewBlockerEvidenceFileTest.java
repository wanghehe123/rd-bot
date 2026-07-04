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

class RequirementReviewBlockerEvidenceFileTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidatedRequirementReviewBlockerEvidenceForTheSameProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("requirement-review-blocker.json");
        Files.writeString(evidenceJson, validEvidenceJson());
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        RequirementReviewBlockerEvidenceFile evidence = RequirementReviewBlockerEvidenceFile.from(profile);

        assertTrue(evidence.validated());
        assertEquals("task-review-blocked", evidence.taskId());
        assertEquals("stage-reviewer", evidence.stageRunId());
        assertEquals("FAILED_NEEDS_HUMAN", evidence.taskStatus());
        assertEquals("NEEDS_HUMAN", evidence.executionResultStatus());
        assertEquals("NEED_INFO", evidence.reviewDecision());
        assertEquals("s3://rd-bot-review/requirement-review/blocker.json", evidence.reviewArtifactUri());
        assertEquals(3, evidence.pendingDownstreamRoleCount());
        assertEquals(List.of("业务边界", "验收命令"), evidence.missingInformation());
        assertTrue(evidence.feishuAlertDelivered());
    }

    @Test
    void shouldLoadRejectedRequirementReviewBlockerEvidenceWhenReviewerBlockedDownstreamAgents() throws Exception {
        Path evidenceJson = tempDir.resolve("requirement-review-blocker.json");
        Files.writeString(evidenceJson, validEvidenceJson()
                .replace("\"taskStatus\": \"FAILED_NEEDS_HUMAN\"", "\"taskStatus\": \"REJECTED\"")
                .replace("\"executionResultStatus\": \"NEEDS_HUMAN\"", "\"executionResultStatus\": \"FAILED\""));
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        RequirementReviewBlockerEvidenceFile evidence = RequirementReviewBlockerEvidenceFile.from(profile);

        assertTrue(evidence.validated());
        assertEquals("REJECTED", evidence.taskStatus());
        assertEquals("FAILED", evidence.executionResultStatus());
        assertEquals("NEED_INFO", evidence.reviewDecision());
        assertEquals(3, evidence.pendingDownstreamRoleCount());
    }

    @Test
    void shouldIgnoreRequirementReviewBlockerEvidenceFromDifferentProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("requirement-review-blocker.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace("prod-equivalent-a", "another-env"));

        RequirementReviewBlockerEvidenceFile evidence = RequirementReviewBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
        assertEquals("", evidence.taskId());
    }

    @Test
    void shouldRejectRequirementReviewBlockerEvidenceWhenDownstreamAgentsWereDispatched() throws Exception {
        Path evidenceJson = tempDir.resolve("requirement-review-blocker.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"downstreamAgentsDispatched\": false",
                "\"downstreamAgentsDispatched\": true"
        ));

        RequirementReviewBlockerEvidenceFile evidence = RequirementReviewBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRequirementReviewBlockerEvidenceWhenFeishuAlertTypeIsWrong() throws Exception {
        Path evidenceJson = tempDir.resolve("requirement-review-blocker.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"feishuAlertType\": \"STAGE_FAILED_NEEDS_HUMAN\"",
                "\"feishuAlertType\": \"QA_FAILED\""
        ));

        RequirementReviewBlockerEvidenceFile evidence = RequirementReviewBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRequirementReviewBlockerEvidenceFromNonReviewerRole() throws Exception {
        Path evidenceJson = tempDir.resolve("requirement-review-blocker.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"role\": \"REQUIREMENT_REVIEWER\"",
                "\"role\": \"CODING_AGENT\""
        ));

        RequirementReviewBlockerEvidenceFile evidence = RequirementReviewBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRequirementReviewBlockerEvidenceWithoutDurableReviewArtifactUri() throws Exception {
        Path evidenceJson = tempDir.resolve("requirement-review-blocker.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"reviewArtifactUri\": \"s3://rd-bot-review/requirement-review/blocker.json\"",
                "\"reviewArtifactUri\": \"qa-runs/requirement-review/blocker.json\""
        ));

        RequirementReviewBlockerEvidenceFile evidence = RequirementReviewBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRequirementReviewBlockerEvidenceWithLocalFileReviewArtifactUri() throws Exception {
        Path evidenceJson = tempDir.resolve("requirement-review-blocker.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"reviewArtifactUri\": \"s3://rd-bot-review/requirement-review/blocker.json\"",
                "\"reviewArtifactUri\": \"file:///tmp/rd-bot/requirement-review/blocker.json\""
        ));

        RequirementReviewBlockerEvidenceFile evidence = RequirementReviewBlockerEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    private static String validEvidenceJson() {
        return """
                {
                  "requirementReviewBlockerEvidenceValidated": true,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "prod-equivalent-a",
                  "executedBy": "qa-runner",
                  "taskId": "task-review-blocked",
                  "stageRunId": "stage-reviewer",
                  "role": "REQUIREMENT_REVIEWER",
                  "taskStatus": "FAILED_NEEDS_HUMAN",
                  "executionResultStatus": "NEEDS_HUMAN",
                  "reviewDecision": "NEED_INFO",
                  "reviewArtifactId": "artifact-review-json",
                  "reviewArtifactUri": "s3://rd-bot-review/requirement-review/blocker.json",
                  "errorMessage": "缺少业务边界和验收命令",
                  "missingInformation": ["业务边界", "验收命令"],
                  "pendingDownstreamRoles": ["SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"],
                  "downstreamAgentsDispatched": false,
                  "feishuAlertDelivered": true,
                  "feishuAlertType": "STAGE_FAILED_NEEDS_HUMAN",
                  "feishuAlertMessageId": "om-review-blocked"
                }
                """;
    }

    private static MultiAgentProductionAcceptanceProfile profile(Path requirementReviewEvidenceJson) {
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
                entry("rd.multi-agent.smoke.requirement-review-evidence-json", requirementReviewEvidenceJson.toString())
        ), Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }
}
