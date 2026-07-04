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

class DockerCodingProductionAcceptanceReportTest {

    private static final String FULL_COMMIT_HASH = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path tempDir;

    @Test
    void shouldRequireRealDockerCodingProperties() {
        List<String> missing = DockerCodingProductionAcceptanceProfile.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.docker-coding.smoke.production-evidence"));
        assertTrue(missing.contains("rd.docker-coding.smoke.base-url"));
        assertTrue(missing.contains("rd.docker-coding.smoke.postgres-url"));
        assertTrue(missing.contains("rd.docker-coding.smoke.repository-url"));
        assertTrue(missing.contains("rd.docker-coding.smoke.task-id"));
        assertTrue(missing.contains("rd.docker-coding.smoke.patch-artifact-uri"));
        assertTrue(missing.contains("rd.docker-coding.smoke.result-artifact-uri"));
        assertTrue(missing.contains("rd.docker-coding.smoke.test-log-artifact-uri"));
        assertTrue(missing.contains("rd.docker-coding.smoke.docker-metadata-artifact-uri"));
    }

    @Test
    void shouldRejectMockOrReusedDockerArtifactUris() {
        Map<String, String> properties = validProperties(
                "mock://rd-bot/docker/patch.diff",
                "s3://rd-bot-docker/result.json",
                "s3://rd-bot-docker/test.log",
                "s3://rd-bot-docker/docker-metadata.json"
        );

        List<String> missing = DockerCodingProductionAcceptanceProfile.missingRequiredProperties(properties);

        assertTrue(missing.contains("rd.docker-coding.smoke.patch-artifact-uri=http(s)|s3|rd-artifact"));
        assertThrows(IllegalArgumentException.class,
                () -> DockerCodingProductionAcceptanceProfile.from(properties));
    }

    @Test
    void shouldBuildProfileWithoutLeakingSecretsInDescription() {
        DockerCodingProductionAcceptanceProfile profile =
                DockerCodingProductionAcceptanceProfile.from(validProperties());

        assertEquals("task-docker-coding", profile.taskId());
        assertEquals("https://github.com/acme/rd-bot-smoke.git", profile.repositoryUrl());
        assertEquals("s3://rd-bot-docker/patch.diff", profile.patchArtifactUri());
        assertEquals(List.of("postgres-secret"), profile.secretScanNeedles());
        assertFalse(profile.describeMissingRequirements().contains("postgres-secret"));
    }

    @Test
    void shouldWritePassedReportAsMultiAgentCompatibleDockerCodingSidecar() throws Exception {
        DockerCodingProductionAcceptanceProfile profile =
                DockerCodingProductionAcceptanceProfile.from(validProperties());
        DockerCodingProductionAcceptanceReport report = new DockerCodingProductionAcceptanceReport(
                tempDir,
                Clock.fixed(Instant.parse("2026-07-04T02:20:00Z"), ZoneOffset.UTC)
        );
        DockerCodingProductionAcceptanceReport.DockerCodingSmokeEvidence evidence =
                new DockerCodingProductionAcceptanceReport.DockerCodingSmokeEvidence(
                        profile,
                        "stage-coding-agent",
                        "SUCCEEDED",
                        "ghcr.io/acme/rd-bot-executor:20260704",
                        "docker-container-123",
                        "/workspace/rd-bot",
                        FULL_COMMIT_HASH,
                        "artifact-patch-diff",
                        "artifact-result-json",
                        "artifact-test-log",
                        "artifact-docker-metadata",
                        2,
                        "./mvnw test",
                        0,
                        276,
                        0,
                        true,
                        true,
                        true
                );

        Path markdown = report.writePassed(evidence);
        Path json = markdown.resolveSibling(markdown.getFileName().toString().replace(".md", ".json"));

        assertTrue(Files.readString(markdown).contains("PASSED_DOCKER_CODING_SMOKE"));
        assertTrue(Files.readString(markdown).contains("| 6 | 编码 Agent 在 Docker 中真实改代码并运行测试 | PASSED |"));
        assertTrue(Files.isRegularFile(json));
        DockerCodingEvidenceFile loaded = DockerCodingEvidenceFile.from(multiAgentProfile(json));
        assertTrue(loaded.validated());
        assertEquals("task-docker-coding", loaded.taskId());
        assertEquals("stage-coding-agent", loaded.stageRunId());
        assertEquals(276, loaded.testsRun());
        assertTrue(loaded.realDockerRun());
    }

    @Test
    void shouldWriteSkippedReportWithConcreteDockerCodingCommand() throws Exception {
        DockerCodingProductionAcceptanceReport report = new DockerCodingProductionAcceptanceReport(
                tempDir,
                Clock.fixed(Instant.parse("2026-07-04T02:21:00Z"), ZoneOffset.UTC)
        );

        Path markdown = report.writeSkipped(List.of("rd.docker-coding.smoke.base-url"));
        String content = Files.readString(markdown);

        assertTrue(content.contains("DockerCodingRealSmokeTest"));
        assertTrue(content.contains("-Drd.docker-coding.smoke.production-evidence=true"));
        assertTrue(content.contains("-Drd.docker-coding.smoke.task-id=<rd-bot-task-id>"));
        assertTrue(content.contains("| 6 | 编码 Agent 在 Docker 中真实改代码并运行测试 | NOT_RUN |"));
    }

    private static Map<String, String> validProperties() {
        return validProperties(
                "s3://rd-bot-docker/patch.diff",
                "s3://rd-bot-docker/result.json",
                "s3://rd-bot-docker/test.log",
                "s3://rd-bot-docker/docker-metadata.json"
        );
    }

    private static Map<String, String> validProperties(
            String patchArtifactUri,
            String resultArtifactUri,
            String testLogArtifactUri,
            String dockerMetadataArtifactUri
    ) {
        return Map.ofEntries(
                entry("rd.docker-coding.smoke.production-evidence", "true"),
                entry("rd.docker-coding.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.docker-coding.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.docker-coding.smoke.executed-by", "qa-runner"),
                entry("rd.docker-coding.smoke.base-url", "http://127.0.0.1:8080"),
                entry("rd.docker-coding.smoke.postgres-url", "jdbc:postgresql://127.0.0.1:5432/rd_bot"),
                entry("rd.docker-coding.smoke.postgres-user", "rd_bot"),
                entry("rd.docker-coding.smoke.postgres-password", "postgres-secret"),
                entry("rd.docker-coding.smoke.repository-url", "https://github.com/acme/rd-bot-smoke.git"),
                entry("rd.docker-coding.smoke.task-id", "task-docker-coding"),
                entry("rd.docker-coding.smoke.patch-artifact-uri", patchArtifactUri),
                entry("rd.docker-coding.smoke.result-artifact-uri", resultArtifactUri),
                entry("rd.docker-coding.smoke.test-log-artifact-uri", testLogArtifactUri),
                entry("rd.docker-coding.smoke.docker-metadata-artifact-uri", dockerMetadataArtifactUri),
                entry("rd.docker-coding.smoke.secret-scan-needles", "postgres-secret")
        );
    }

    private static MultiAgentProductionAcceptanceProfile multiAgentProfile(Path dockerCodingEvidenceJson) {
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
