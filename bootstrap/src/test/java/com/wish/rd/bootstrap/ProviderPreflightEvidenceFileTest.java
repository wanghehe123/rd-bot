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

class ProviderPreflightEvidenceFileTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidatedProviderPreflightEvidenceForSameProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("provider-preflight.json");
        Files.writeString(evidenceJson, validEvidenceJson());

        ProviderPreflightEvidenceFile evidence = ProviderPreflightEvidenceFile.from(profile(evidenceJson));

        assertTrue(evidence.validated());
        assertEquals(2, evidence.successfulProviderCount());
        assertEquals(2, evidence.providerCount());
        assertEquals("long-cat,minimax", String.join(",", evidence.providerNames()));
    }

    @Test
    void shouldRejectProviderPreflightEvidenceFromDifferentProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("provider-preflight.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace("prod-equivalent-a", "another-env"));

        ProviderPreflightEvidenceFile evidence = ProviderPreflightEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
        assertEquals(0, evidence.successfulProviderCount());
    }

    @Test
    void shouldRejectProviderPreflightEvidenceWhenSuccessfulProviderCountIsTooSmall() throws Exception {
        Path evidenceJson = tempDir.resolve("provider-preflight.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"successfulProviderCount\": 2",
                "\"successfulProviderCount\": 1"
        ));

        ProviderPreflightEvidenceFile evidence = ProviderPreflightEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectProviderPreflightEvidenceWhenConclusionIsNotPassed() throws Exception {
        Path evidenceJson = tempDir.resolve("provider-preflight.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"conclusion\": \"PASSED_PROVIDER_PREFLIGHT_SMOKE\"",
                "\"conclusion\": \"FAILED_PROVIDER_PREFLIGHT_SMOKE\""
        ));

        ProviderPreflightEvidenceFile evidence = ProviderPreflightEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectProviderPreflightEvidenceWhenAProviderProbeFailed() throws Exception {
        Path evidenceJson = tempDir.resolve("provider-preflight.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"name\": \"minimax\", \"protocol\": \"openai-chat-completions\", \"adapterName\": \"openai-chat-completions\", \"baseUrl\": \"https://api.minimaxi.com/v1\", \"apiKeyEnv\": \"MINIMAX_API_KEY\", \"model\": \"MiniMax-M2.7\", \"httpStatus\": 200, \"success\": true",
                "\"name\": \"minimax\", \"protocol\": \"openai-chat-completions\", \"adapterName\": \"openai-chat-completions\", \"baseUrl\": \"https://api.minimaxi.com/v1\", \"apiKeyEnv\": \"MINIMAX_API_KEY\", \"model\": \"MiniMax-M2.7\", \"httpStatus\": 401, \"success\": false"
        ));

        ProviderPreflightEvidenceFile evidence = ProviderPreflightEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldReturnEmptyEvidenceWhenNoSidecarIsConfigured() {
        ProviderPreflightEvidenceFile evidence = ProviderPreflightEvidenceFile.from(profile(null));

        assertFalse(evidence.validated());
        assertEquals(0, evidence.providerCount());
        assertEquals(0, evidence.successfulProviderCount());
    }

    private static String validEvidenceJson() {
        return """
                {
                  "conclusion": "PASSED_PROVIDER_PREFLIGHT_SMOKE",
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "prod-equivalent-a",
                  "executedBy": "qa-runner",
                  "expectedProviderCount": 2,
                  "providerPreflightEvidenceValidated": true,
                  "successfulProviderCount": 2,
                  "providers": [
                    {"name": "long-cat", "protocol": "anthropic-compatible", "adapterName": "docker-claude-code", "baseUrl": "https://api.longcat.chat/anthropic", "apiKeyEnv": "LONGCAT_API_KEY", "model": "LongCat-2.0", "httpStatus": 200, "success": true, "responseFingerprint": "sha256:aaaaaaaa", "failureCategory": ""},
                    {"name": "minimax", "protocol": "openai-chat-completions", "adapterName": "openai-chat-completions", "baseUrl": "https://api.minimaxi.com/v1", "apiKeyEnv": "MINIMAX_API_KEY", "model": "MiniMax-M2.7", "httpStatus": 200, "success": true, "responseFingerprint": "sha256:bbbbbbbb", "failureCategory": ""}
                  ]
                }
                """;
    }

    private static MultiAgentProductionAcceptanceProfile profile(Path providerPreflightEvidenceJson) {
        Map<String, String> properties = new java.util.LinkedHashMap<>(Map.ofEntries(
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
                entry("rd.multi-agent.smoke.provider-secret-env-names", "LONGCAT_API_KEY,MINIMAX_API_KEY"),
                entry("rd.multi-agent.smoke.github-code-platform-mode", "real"),
                entry("rd.multi-agent.smoke.github-auth-mode", "PAT_LOCAL_SMOKE"),
                entry("rd.multi-agent.smoke.github-credential-env-names", "GITHUB_PAT"),
                entry("rd.multi-agent.smoke.secret-scan-needles", "postgres-secret,github-secret")
        ));
        if (providerPreflightEvidenceJson != null) {
            properties.put(
                    "rd.multi-agent.smoke.provider-preflight-evidence-json",
                    providerPreflightEvidenceJson.toString()
            );
        }
        return MultiAgentProductionAcceptanceProfile.from(properties, Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "MINIMAX_API_KEY", "minimax-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }
}
