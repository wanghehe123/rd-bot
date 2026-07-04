package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderPreflightProductionAcceptanceReportTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldWritePassedMarkdownAndJsonWithoutSecretValues() throws Exception {
        ProviderPreflightProductionAcceptanceReport report = new ProviderPreflightProductionAcceptanceReport(
                temporaryDirectory,
                Clock.fixed(Instant.parse("2026-07-04T07:20:00Z"), ZoneOffset.UTC)
        );
        ProviderPreflightProductionAcceptanceReport.ProviderPreflightEvidence evidence =
                new ProviderPreflightProductionAcceptanceReport.ProviderPreflightEvidence(
                        "0.1.0-test",
                        "local-real",
                        "junit",
                        2,
                        List.of(
                                new ProviderPreflightProductionAcceptanceReport.ProviderProbeEvidence(
                                        "long-cat",
                                        "anthropic-compatible",
                                        "docker-claude-code",
                                        "https://api.longcat.chat/anthropic",
                                        "LONGCAT_API_KEY",
                                        "LongCat-2.0",
                                        200,
                                        true,
                                        "body-sha256:abc",
                                        "",
                                        "ok"
                                ),
                                new ProviderPreflightProductionAcceptanceReport.ProviderProbeEvidence(
                                        "minimax",
                                        "openai-chat-completions",
                                        "openai-chat-completions",
                                        "https://api.minimaxi.com/v1",
                                        "MINIMAX_API_KEY",
                                        "MiniMax-M2.7",
                                        200,
                                        true,
                                        "body-sha256:def",
                                        "",
                                        "ok"
                                )
                        ),
                        List.of("super-secret-needle")
                );

        Path markdown = report.writePassed(evidence);
        String markdownText = Files.readString(markdown);
        String jsonText = Files.readString(markdown.resolveSibling(markdown.getFileName().toString()
                .replaceFirst("\\.md$", ".json")));

        assertTrue(markdownText.contains("PASSED_PROVIDER_PREFLIGHT_SMOKE"));
        assertTrue(markdownText.contains("providerPreflightEvidenceValidated：true"));
        assertTrue(jsonText.contains("\"providerPreflightEvidenceValidated\":true"));
        assertTrue(jsonText.contains("\"successfulProviderCount\":2"));
        assertFalse(markdownText.contains("super-secret-needle"));
        assertFalse(jsonText.contains("super-secret-needle"));
    }

    @Test
    void shouldWriteSkippedJsonWithMissingEnvNames() throws Exception {
        ProviderPreflightProductionAcceptanceReport report = new ProviderPreflightProductionAcceptanceReport(
                temporaryDirectory,
                Clock.fixed(Instant.parse("2026-07-04T07:21:00Z"), ZoneOffset.UTC)
        );

        Path markdown = report.writeSkipped(List.of("env:LONGCAT_API_KEY", "env:MINIMAX_API_KEY"));
        String jsonText = Files.readString(markdown.resolveSibling(markdown.getFileName().toString()
                .replaceFirst("\\.md$", ".json")));

        assertTrue(jsonText.contains("\"conclusion\":\"SKIPPED_PROVIDER_PREFLIGHT_SMOKE\""));
        assertTrue(jsonText.contains("\"providerPreflightEvidenceValidated\":false"));
        assertTrue(jsonText.contains("\"missingRequirements\":[\"env:LONGCAT_API_KEY\",\"env:MINIMAX_API_KEY\"]"));
    }

    @Test
    void shouldWriteFailedJsonWithoutSecretValues() throws Exception {
        ProviderPreflightProductionAcceptanceReport report = new ProviderPreflightProductionAcceptanceReport(
                temporaryDirectory,
                Clock.fixed(Instant.parse("2026-07-04T07:22:00Z"), ZoneOffset.UTC)
        );
        ProviderPreflightProductionAcceptanceReport.ProviderPreflightEvidence evidence =
                new ProviderPreflightProductionAcceptanceReport.ProviderPreflightEvidence(
                        "0.1.0-test",
                        "local-real",
                        "junit",
                        2,
                        List.of(new ProviderPreflightProductionAcceptanceReport.ProviderProbeEvidence(
                                "minimax",
                                "openai-chat-completions",
                                "openai-chat-completions",
                                "https://api.minimaxi.com/v1",
                                "MINIMAX_API_KEY",
                                "MiniMax-M2.7",
                                429,
                                false,
                                "body-sha256:def",
                                "PROVIDER_HTTP_OR_SCHEMA_FAILED",
                                "quota exceeded"
                        )),
                        List.of("super-secret-needle")
                );

        Path markdown = report.writeFailed(evidence, new AssertionError("super-secret-needle provider failed"));
        String jsonText = Files.readString(markdown.resolveSibling(markdown.getFileName().toString()
                .replaceFirst("\\.md$", ".json")));

        assertTrue(jsonText.contains("\"conclusion\":\"FAILED_PROVIDER_PREFLIGHT_SMOKE\""));
        assertTrue(jsonText.contains("\"providerPreflightEvidenceValidated\":false"));
        assertTrue(jsonText.contains("\"successfulProviderCount\":0"));
        assertFalse(jsonText.contains("super-secret-needle"));
    }

    @Test
    void shouldWriteFailedProviderMessagesAndOperationalFailureReasons() throws Exception {
        ProviderPreflightProductionAcceptanceReport report = new ProviderPreflightProductionAcceptanceReport(
                temporaryDirectory,
                Clock.fixed(Instant.parse("2026-07-04T07:23:00Z"), ZoneOffset.UTC)
        );
        ProviderPreflightProductionAcceptanceReport.ProviderPreflightEvidence evidence =
                new ProviderPreflightProductionAcceptanceReport.ProviderPreflightEvidence(
                        "0.1.0-test",
                        "local-real",
                        "junit",
                        2,
                        List.of(
                                new ProviderPreflightProductionAcceptanceReport.ProviderProbeEvidence(
                                        "long-cat",
                                        "anthropic-compatible",
                                        "docker-claude-code",
                                        "https://api.longcat.chat/anthropic",
                                        "LONGCAT_API_KEY",
                                        "LongCat-2.0",
                                        401,
                                        false,
                                        "body-sha256:abc",
                                        "PROVIDER_HTTP_OR_SCHEMA_FAILED",
                                        "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"secret-key invalid\"}}"
                                ),
                                new ProviderPreflightProductionAcceptanceReport.ProviderProbeEvidence(
                                        "minimax",
                                        "openai-chat-completions",
                                        "openai-chat-completions",
                                        "https://api.minimaxi.com/v1",
                                        "MINIMAX_API_KEY",
                                        "MiniMax-M2.7",
                                        429,
                                        false,
                                        "body-sha256:def",
                                        "PROVIDER_HTTP_OR_SCHEMA_FAILED",
                                        "Token Plan 用量上限"
                                )
                        ),
                        List.of("secret-key")
                );

        Path markdown = report.writeFailed(evidence, new AssertionError("provider failed"));
        String jsonText = Files.readString(markdown.resolveSibling(markdown.getFileName().toString()
                .replaceFirst("\\.md$", ".json")));

        assertTrue(jsonText.contains("\"failureReason\":\"PROVIDER_AUTHENTICATION_FAILED\""));
        assertTrue(jsonText.contains("\"failureReason\":\"PROVIDER_QUOTA_OR_RATE_LIMIT\""));
        assertTrue(jsonText.contains("\"message\":\"{\\\"error\\\":{\\\"code\\\":\\\"invalid_api_key\\\",\\\"message\\\":\\\"*** invalid\\\"}}\""));
        assertTrue(jsonText.contains("\"message\":\"Token Plan 用量上限\""));
        assertFalse(jsonText.contains("secret-key"));
    }
}
