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

class GitHubPrRemoteEvidenceFileTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidatedRemotePrEvidenceFromSameProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("github-pr-remote-evidence-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                "123456",
                false
        ));

        GitHubPrRemoteEvidenceFile evidence = GitHubPrRemoteEvidenceFile.from(profile(evidenceJson));

        assertTrue(evidence.validated());
        assertEquals("123456", evidence.taskId());
        assertEquals("https://github.com/acme/rd-bot-smoke/pull/7", evidence.pullRequestUrl());
        assertTrue(evidence.pullRequestBodyIncludesDeliveryReview());
        assertTrue(evidence.pullRequestBodyIncludesQaEvidence());
        assertTrue(evidence.pullRequestBodyContainsTaskId());
        assertTrue(evidence.pullRequestBodyContainsArtifactLink());
        assertTrue(evidence.secretScanEvidenceValidated());
        assertFalse(evidence.secretLeakFound());
        assertEquals(2, evidence.secretScannedValueCount());
        assertTrue(evidence.toReportEvidence().validated());
    }

    @Test
    void shouldRejectRemotePrEvidenceWithoutExactTaskIdTokenMarker() throws Exception {
        Path evidenceJson = tempDir.resolve("github-pr-remote-evidence-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                "123456",
                false
        ).replace("\"pullRequestBodyContainsExactTaskId\": true,\n", ""));

        GitHubPrRemoteEvidenceFile evidence = GitHubPrRemoteEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRemotePrEvidenceFromDifferentEnvironment() throws Exception {
        Path evidenceJson = tempDir.resolve("github-pr-remote-evidence-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "other-env",
                "123456",
                false
        ));

        GitHubPrRemoteEvidenceFile evidence = GitHubPrRemoteEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
        assertFalse(evidence.toReportEvidence().validated());
    }

    @Test
    void shouldRejectRemotePrEvidenceFromDifferentTask() throws Exception {
        Path evidenceJson = tempDir.resolve("github-pr-remote-evidence-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                "other-task",
                false
        ));

        GitHubPrRemoteEvidenceFile evidence = GitHubPrRemoteEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRemotePrEvidenceWhenSecretLeakWasDetected() throws Exception {
        Path evidenceJson = tempDir.resolve("github-pr-remote-evidence-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                "123456",
                true
        ));

        GitHubPrRemoteEvidenceFile evidence = GitHubPrRemoteEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRemotePrEvidenceWhenPullRequestUrlIsNotHttp() throws Exception {
        Path evidenceJson = tempDir.resolve("github-pr-remote-evidence-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                "123456",
                false,
                "ftp://github.com/acme/rd-bot-smoke/pull/7"
        ));

        GitHubPrRemoteEvidenceFile evidence = GitHubPrRemoteEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRemotePrEvidenceWhenPullRequestUrlHostDoesNotMatchRepository() throws Exception {
        Path evidenceJson = tempDir.resolve("github-pr-remote-evidence-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                "123456",
                false,
                "https://evil.example/acme/rd-bot-smoke/pull/7"
        ));

        GitHubPrRemoteEvidenceFile evidence = GitHubPrRemoteEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRemotePrEvidenceWhenPullRequestUrlDoesNotEndWithNumberSegment() throws Exception {
        Path evidenceJson = tempDir.resolve("github-pr-remote-evidence-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                "123456",
                false,
                "https://github.com/acme/rd-bot-smoke/pull/not-a-pr/7"
        ));

        GitHubPrRemoteEvidenceFile evidence = GitHubPrRemoteEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectRemotePrEvidenceWhenWorkBranchIsNotRequirementTaskBranch() throws Exception {
        Path evidenceJson = tempDir.resolve("github-pr-remote-evidence-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                "123456",
                false,
                "https://github.com/acme/rd-bot-smoke/pull/7",
                "hotfix/123456"
        ));

        GitHubPrRemoteEvidenceFile evidence = GitHubPrRemoteEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    private static String validEvidenceJson(
            String environmentId,
            String taskId,
            boolean secretLeakFound
    ) {
        return validEvidenceJson(
                environmentId,
                taskId,
                secretLeakFound,
                "https://github.com/acme/rd-bot-smoke/pull/7"
        );
    }

    private static String validEvidenceJson(
            String environmentId,
            String taskId,
            boolean secretLeakFound,
            String pullRequestUrl
    ) {
        return validEvidenceJson(
                environmentId,
                taskId,
                secretLeakFound,
                pullRequestUrl,
                "requirement/123456"
        );
    }

    private static String validEvidenceJson(
            String environmentId,
            String taskId,
            boolean secretLeakFound,
            String pullRequestUrl,
            String workBranch
    ) {
        return """
                {
                  "githubPrRemoteEvidenceValidated": true,
                  "remotePrTraceValidated": true,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "%s",
                  "executedBy": "qa-runner",
                  "taskId": "%s",
                  "repoOwner": "acme",
                  "repoName": "rd-bot-smoke",
                  "baseBranch": "main",
                  "workBranch": "%s",
                  "pullRequestUrl": "%s",
                  "pullRequestNumber": "7",
                  "pullRequestBodyLength": 256,
                  "pullRequestBodyIncludesDeliveryReview": true,
                  "pullRequestBodyIncludesQaEvidence": true,
                  "pullRequestBodyContainsTaskId": true,
                  "pullRequestBodyContainsExactTaskId": true,
                  "pullRequestBodyContainsArtifactLink": true,
                  "secretScanEvidenceValidated": true,
                  "secretLeakFound": %s,
                  "secretScannedValueCount": 2
                }
                """.formatted(environmentId, taskId, workBranch, pullRequestUrl, secretLeakFound);
    }

    private static MultiAgentProductionAcceptanceProfile profile(Path githubPrRemoteEvidenceJson) {
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
                entry("rd.multi-agent.smoke.github-pr-remote-evidence-json",
                        githubPrRemoteEvidenceJson.toString())
        ), Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }
}
