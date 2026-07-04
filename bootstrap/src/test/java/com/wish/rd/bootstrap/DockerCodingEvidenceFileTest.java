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

class DockerCodingEvidenceFileTest {

    private static final String FULL_COMMIT_HASH = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidatedDockerCodingEvidenceFromSameProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("docker-coding-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                0,
                true
        ));

        DockerCodingEvidenceFile evidence = DockerCodingEvidenceFile.from(profile(evidenceJson));

        assertTrue(evidence.validated());
        assertEquals("task-docker-coding", evidence.taskId());
        assertEquals("stage-coding-agent", evidence.stageRunId());
        assertEquals("https://github.com/acme/rd-bot-smoke.git", evidence.repositoryUrl());
        assertEquals("ghcr.io/acme/rd-bot-executor:20260701", evidence.dockerImage());
        assertEquals("docker-container-123", evidence.containerId());
        assertEquals(FULL_COMMIT_HASH, evidence.commitHash());
        assertEquals("artifact-patch-diff", evidence.patchArtifactId());
        assertEquals("artifact-test-log", evidence.testLogArtifactId());
        assertEquals(2, evidence.changedFileCount());
        assertEquals("./mvnw test", evidence.validationCommand());
        assertEquals(276, evidence.testsRun());
        assertTrue(evidence.toReportEvidence().validated());
    }

    @Test
    void shouldRejectDockerCodingEvidenceFromDifferentEnvironment() throws Exception {
        Path evidenceJson = tempDir.resolve("docker-coding-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "other-env",
                0,
                true
        ));

        DockerCodingEvidenceFile evidence = DockerCodingEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
        assertFalse(evidence.toReportEvidence().validated());
    }

    @Test
    void shouldRejectDockerCodingEvidenceFromDifferentRepository() throws Exception {
        Path evidenceJson = tempDir.resolve("docker-coding-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                0,
                true
        ).replace(
                "\"repositoryUrl\": \"https://github.com/acme/rd-bot-smoke.git\"",
                "\"repositoryUrl\": \"https://github.com/other/project.git\""
        ));

        DockerCodingEvidenceFile evidence = DockerCodingEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectDockerCodingEvidenceWithShortCommitHash() throws Exception {
        Path evidenceJson = tempDir.resolve("docker-coding-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                0,
                true
        ).replace(
                "\"commitHash\": \"" + FULL_COMMIT_HASH + "\"",
                "\"commitHash\": \"abc123def456\""
        ));

        DockerCodingEvidenceFile evidence = DockerCodingEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectDockerCodingEvidenceWithoutPassingRealValidation() throws Exception {
        Path evidenceJson = tempDir.resolve("docker-coding-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                1,
                false
        ));

        DockerCodingEvidenceFile evidence = DockerCodingEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectDockerCodingEvidenceWhenArtifactIdsAreReused() throws Exception {
        Path evidenceJson = tempDir.resolve("docker-coding-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                0,
                true
        ).replace(
                "\"resultArtifactId\": \"artifact-result-json\"",
                "\"resultArtifactId\": \"artifact-patch-diff\""
        ));

        DockerCodingEvidenceFile evidence = DockerCodingEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectDockerCodingEvidenceWithoutDurableArtifactUris() throws Exception {
        Path evidenceJson = tempDir.resolve("docker-coding-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                0,
                true
        ).replace(
                "\"patchArtifactUri\": \"s3://rd-bot-qa/docker-coding/patch.diff\"",
                "\"patchArtifactUri\": \"qa-runs/docker-coding/patch.diff\""
        ));

        DockerCodingEvidenceFile evidence = DockerCodingEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectDockerCodingEvidenceWithLocalFileArtifactUris() throws Exception {
        Path evidenceJson = tempDir.resolve("docker-coding-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                0,
                true
        ).replace(
                "\"patchArtifactUri\": \"s3://rd-bot-qa/docker-coding/patch.diff\"",
                "\"patchArtifactUri\": \"file:///tmp/rd-bot/docker-coding/patch.diff\""
        ));

        DockerCodingEvidenceFile evidence = DockerCodingEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    private static String validEvidenceJson(
            String environmentId,
            int validationExitCode,
            boolean resultJsonValidated
    ) {
        return """
                {
                  "dockerCodingEvidenceValidated": true,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "%s",
                  "executedBy": "qa-runner",
                  "repositoryUrl": "https://github.com/acme/rd-bot-smoke.git",
                  "taskId": "task-docker-coding",
                  "stageRunId": "stage-coding-agent",
                  "role": "CODING_AGENT",
                  "dockerImage": "ghcr.io/acme/rd-bot-executor:20260701",
                  "containerId": "docker-container-123",
                  "workspacePath": "/workspace/rd-bot",
                  "commitHash": "%s",
                  "patchArtifactId": "artifact-patch-diff",
                  "resultArtifactId": "artifact-result-json",
                  "testLogArtifactId": "artifact-test-log",
                  "dockerMetadataArtifactId": "artifact-docker-metadata",
                  "patchArtifactUri": "s3://rd-bot-qa/docker-coding/patch.diff",
                  "resultArtifactUri": "s3://rd-bot-qa/docker-coding/result.json",
                  "testLogArtifactUri": "s3://rd-bot-qa/docker-coding/test.log",
                  "dockerMetadataArtifactUri": "s3://rd-bot-qa/docker-coding/docker-metadata.json",
                  "changedFileCount": 2,
                  "validationCommand": "./mvnw test",
                  "validationExitCode": %d,
                  "testsRun": 276,
                  "testsFailed": 0,
                  "patchNonEmpty": true,
                  "resultJsonValidated": %s,
                  "realDockerRun": true
                }
                """.formatted(environmentId, FULL_COMMIT_HASH, validationExitCode, resultJsonValidated);
    }

    private static MultiAgentProductionAcceptanceProfile profile(Path dockerCodingEvidenceJson) {
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
                entry("rd.multi-agent.smoke.docker-coding-evidence-json", dockerCodingEvidenceJson.toString())
        ), Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }
}
