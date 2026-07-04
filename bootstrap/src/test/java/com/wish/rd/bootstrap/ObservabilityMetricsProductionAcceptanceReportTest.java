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

class ObservabilityMetricsProductionAcceptanceReportTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRequireRealObservabilityMetricsProperties() {
        List<String> missing = ObservabilityMetricsProductionAcceptanceProfile.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.observability-metrics.smoke.production-evidence"));
        assertTrue(missing.contains("rd.observability-metrics.smoke.base-url"));
        assertTrue(missing.contains("rd.observability-metrics.smoke.postgres-url"));
        assertTrue(missing.contains("rd.observability-metrics.smoke.task-id"));
        assertTrue(missing.contains("rd.observability-metrics.smoke.github-pr-remote-evidence-json"));
        assertTrue(missing.contains("rd.observability-metrics.smoke.secret-scan-needles"));
    }

    @Test
    void shouldRejectMockRemotePrEvidenceJson() {
        Map<String, String> properties = validProperties("mock://github-pr-remote-evidence.json");

        List<String> missing = ObservabilityMetricsProductionAcceptanceProfile.missingRequiredProperties(properties);

        assertTrue(missing.contains("rd.observability-metrics.smoke.github-pr-remote-evidence-json=<file>"));
        assertThrows(IllegalArgumentException.class,
                () -> ObservabilityMetricsProductionAcceptanceProfile.from(properties));
    }

    @Test
    void shouldBuildProfileWithoutLeakingSecretsInDescription() {
        ObservabilityMetricsProductionAcceptanceProfile profile =
                ObservabilityMetricsProductionAcceptanceProfile.from(validProperties());

        assertEquals("task-observability", profile.taskId());
        assertEquals("https://rd-bot.example.com/actuator/prometheus", profile.metricsEndpointUrl());
        assertEquals(List.of("postgres-secret"), profile.secretScanNeedles());
        assertFalse(profile.describeMissingRequirements().contains("postgres-secret"));
    }

    @Test
    void shouldWritePassedReportAsMultiAgentCompatibleObservabilitySidecar() throws Exception {
        ObservabilityMetricsProductionAcceptanceProfile profile =
                ObservabilityMetricsProductionAcceptanceProfile.from(validProperties());
        ObservabilityMetricsProductionAcceptanceReport report = new ObservabilityMetricsProductionAcceptanceReport(
                tempDir,
                Clock.fixed(Instant.parse("2026-07-04T03:10:00Z"), ZoneOffset.UTC)
        );
        ObservabilityMetricsProductionAcceptanceReport.ObservabilityMetricsSmokeEvidence evidence =
                new ObservabilityMetricsProductionAcceptanceReport.ObservabilityMetricsSmokeEvidence(
                        profile,
                        200,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        4,
                        true,
                        true,
                        12,
                        12
                );

        Path markdown = report.writePassed(evidence);
        Path json = markdown.resolveSibling(markdown.getFileName().toString().replace(".md", ".json"));

        assertTrue(Files.readString(markdown).contains("PASSED_OBSERVABILITY_METRICS_SMOKE"));
        assertTrue(Files.readString(markdown).contains("| 14 | 指标和审计可观测 | PASSED |"));
        assertTrue(Files.isRegularFile(json));
        ObservabilityMetricsEvidenceFile loaded = ObservabilityMetricsEvidenceFile.from(multiAgentProfile(json));
        assertTrue(loaded.validated());
        assertEquals("task-observability", loaded.taskId());
        assertEquals("https://rd-bot.example.com/actuator/prometheus", loaded.metricsEndpointUrl());
        assertEquals(12, loaded.taskBoundAuditTraceLinkCount());
    }

    @Test
    void shouldWriteSkippedReportWithConcreteObservabilityMetricsCommand() throws Exception {
        ObservabilityMetricsProductionAcceptanceReport report = new ObservabilityMetricsProductionAcceptanceReport(
                tempDir,
                Clock.fixed(Instant.parse("2026-07-04T03:11:00Z"), ZoneOffset.UTC)
        );

        Path markdown = report.writeSkipped(List.of("rd.observability-metrics.smoke.base-url"));
        String content = Files.readString(markdown);

        assertTrue(content.contains("ObservabilityMetricsRealSmokeTest"));
        assertTrue(content.contains("-Drd.integration.observability-metrics.enabled=true"));
        assertTrue(content.contains("-Drd.observability-metrics.smoke.production-evidence=true"));
        assertTrue(content.contains("-Drd.observability-metrics.smoke.task-id=<same-main-task-id>"));
        assertTrue(content.contains("| 14 | 指标和审计可观测 | NOT_RUN |"));
    }

    private static Map<String, String> validProperties() {
        return validProperties("/tmp/github-pr-remote-evidence.json");
    }

    private static Map<String, String> validProperties(String githubPrRemoteEvidenceJson) {
        return Map.ofEntries(
                entry("rd.observability-metrics.smoke.production-evidence", "true"),
                entry("rd.observability-metrics.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.observability-metrics.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.observability-metrics.smoke.executed-by", "qa-runner"),
                entry("rd.observability-metrics.smoke.base-url", "https://rd-bot.example.com"),
                entry("rd.observability-metrics.smoke.postgres-url",
                        "jdbc:postgresql://127.0.0.1:5432/rd_bot"),
                entry("rd.observability-metrics.smoke.postgres-user", "rd_bot"),
                entry("rd.observability-metrics.smoke.postgres-password", "postgres-secret"),
                entry("rd.observability-metrics.smoke.task-id", "task-observability"),
                entry("rd.observability-metrics.smoke.github-pr-remote-evidence-json",
                        githubPrRemoteEvidenceJson),
                entry("rd.observability-metrics.smoke.secret-scan-needles", "postgres-secret")
        );
    }

    private static MultiAgentProductionAcceptanceProfile multiAgentProfile(Path observabilityMetricsEvidenceJson) {
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
