package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAgentProductionAcceptanceProfileTest {

    @Test
    void shouldReportAllMissingProductionAcceptancePropertiesTogether() {
        java.util.List<String> missing = MultiAgentProductionAcceptanceProfile.missingRequiredProperties(Map.of());

        assertTrue(missing.containsAll(MultiAgentProductionAcceptanceProfile.requiredPropertyNames()));
        assertTrue(missing.contains("rd.multi-agent.smoke.rd-bot-version"));
        assertTrue(missing.contains("rd.multi-agent.smoke.environment-id"));
        assertTrue(missing.contains("rd.multi-agent.smoke.executed-by"));
        assertTrue(missing.contains("rd.multi-agent.smoke.secret-scan-needles"));
        assertTrue(missing.contains("rd.multi-agent.smoke.provider-secret-env-names>=2"));
        assertTrue(missing.contains("rd.multi-agent.smoke.github-credential-env-names>=1"));
    }

    @Test
    void shouldRequireExplicitProductionEvidenceAndAtLeastTwoProviders() {
        Map<String, String> properties = Map.ofEntries(
                entry("rd.multi-agent.smoke.production-evidence", "false"),
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
                entry("rd.multi-agent.smoke.expected-provider-count", "1"),
                entry("rd.multi-agent.smoke.github-code-platform-mode", "real"),
                entry("rd.multi-agent.smoke.github-auth-mode", "PAT_LOCAL_SMOKE"),
                entry("rd.multi-agent.smoke.github-credential-env-names", "GITHUB_PAT"),
                entry("rd.multi-agent.smoke.secret-scan-needles", "secret")
        );

        assertEquals(
                java.util.List.of(
                        "rd.multi-agent.smoke.production-evidence=true",
                        "rd.multi-agent.smoke.expected-provider-count>=2",
                        "rd.multi-agent.smoke.provider-secret-env-names>=2"
                ),
                MultiAgentProductionAcceptanceProfile.missingRequiredProperties(
                        properties,
                        Map.of("GITHUB_PAT", "github-secret")
                )
        );
    }

    @Test
    void shouldBuildProfileFromRealProductionProperties() {
        Map<String, String> properties = Map.ofEntries(
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
                entry("rd.multi-agent.smoke.base-branch", "release"),
                entry("rd.multi-agent.smoke.expected-provider-count", "2"),
                entry("rd.multi-agent.smoke.provider-secret-env-names", "LONGCAT_API_KEY,ANTHROPIC_API_KEY"),
                entry("rd.multi-agent.smoke.github-code-platform-mode", "real"),
                entry("rd.multi-agent.smoke.github-auth-mode", "PAT_LOCAL_SMOKE"),
                entry("rd.multi-agent.smoke.github-credential-env-names", "GITHUB_PAT"),
                entry("rd.multi-agent.smoke.request-timeout-seconds", "900"),
                entry("rd.multi-agent.smoke.completion-timeout-seconds", "1200"),
                entry("rd.multi-agent.smoke.poll-interval-seconds", "10"),
                entry("rd.multi-agent.smoke.secret-scan-needles", "secret,token")
        );

        MultiAgentProductionAcceptanceProfile profile = MultiAgentProductionAcceptanceProfile.from(
                properties,
                Map.of(
                        "LONGCAT_API_KEY", "longcat-secret",
                        "ANTHROPIC_API_KEY", "anthropic-secret",
                        "GITHUB_PAT", "github-secret"
                )
        );

        assertEquals("release", profile.baseBranch());
        assertEquals("0.1.0-smoke", profile.rdBotVersion());
        assertEquals("prod-equivalent-a", profile.environmentId());
        assertEquals("qa-runner", profile.executedBy());
        assertEquals(2, profile.expectedProviderCount());
        assertEquals(java.util.List.of("LONGCAT_API_KEY", "ANTHROPIC_API_KEY"), profile.providerSecretEnvNames());
        assertEquals(900, profile.requestTimeout().toSeconds());
        assertEquals(1200, profile.completionTimeout().toSeconds());
        assertEquals(10, profile.pollInterval().toSeconds());
        assertEquals(java.util.List.of("secret", "token"), profile.secretScanNeedles());
        assertFalse(profile.describeMissingRequirements().contains("secret,token"));
        assertTrue(profile.describeMissingRequirements().contains("production-evidence"));
    }

    @Test
    void shouldAllowGhCliLocalSmokeWithoutTokenEnvironmentVariable() {
        Map<String, String> properties = new java.util.LinkedHashMap<>(validProperties());
        properties.put("rd.multi-agent.smoke.github-auth-mode", "GH_CLI_LOCAL_SMOKE");
        properties.put("rd.multi-agent.smoke.github-credential-env-names", "");

        MultiAgentProductionAcceptanceProfile profile = MultiAgentProductionAcceptanceProfile.from(
                properties,
                Map.of(
                        "LONGCAT_API_KEY", "longcat-secret",
                        "ANTHROPIC_API_KEY", "anthropic-secret"
                )
        );

        assertEquals("GH_CLI_LOCAL_SMOKE", profile.githubAuthMode());
        assertTrue(profile.githubCredentialEnvNames().isEmpty());
        assertFalse(MultiAgentProductionAcceptanceProfile.missingRequiredProperties(
                properties,
                Map.of(
                        "LONGCAT_API_KEY", "longcat-secret",
                        "ANTHROPIC_API_KEY", "anthropic-secret"
                )
        ).contains("rd.multi-agent.smoke.github-credential-env-names"));
    }

    @Test
    void shouldUseProductionSafeTimeoutDefaultsForRealDockerAndProviderExecution() {
        Map<String, String> properties = Map.ofEntries(
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
                entry("rd.multi-agent.smoke.secret-scan-needles", "secret")
        );

        MultiAgentProductionAcceptanceProfile profile = MultiAgentProductionAcceptanceProfile.from(
                properties,
                Map.of(
                        "LONGCAT_API_KEY", "longcat-secret",
                        "ANTHROPIC_API_KEY", "anthropic-secret",
                        "GITHUB_PAT", "github-secret"
                )
        );

        assertEquals(900, profile.requestTimeout().toSeconds());
        assertEquals(1800, profile.completionTimeout().toSeconds());
        assertEquals(5, profile.pollInterval().toSeconds());
        assertTrue(profile.describeMissingRequirements().contains("request-timeout-seconds"));
        assertTrue(profile.describeMissingRequirements().contains("completion-timeout-seconds"));
        assertTrue(profile.describeMissingRequirements().contains("poll-interval-seconds"));
    }

    @Test
    void shouldAcceptOptionalExistingTaskIdForSameTaskProductionAggregation() {
        Map<String, String> properties = new java.util.LinkedHashMap<>(validProperties());
        properties.put("rd.multi-agent.smoke.task-id", "7478000000000000000");

        MultiAgentProductionAcceptanceProfile profile = MultiAgentProductionAcceptanceProfile.from(
                properties,
                validEnv()
        );

        assertEquals("7478000000000000000", profile.taskId());
        assertTrue(profile.describeMissingRequirements().contains("rd.multi-agent.smoke.task-id"));
    }

    @Test
    void shouldAcceptOptionalProviderPreflightEvidenceSidecarPathForFastProductionGate() {
        Map<String, String> properties = new java.util.LinkedHashMap<>(validProperties());
        properties.put("rd.multi-agent.smoke.provider-preflight-evidence-json", "/tmp/provider-preflight.json");

        MultiAgentProductionAcceptanceProfile profile = MultiAgentProductionAcceptanceProfile.from(
                properties,
                validEnv()
        );

        assertEquals("/tmp/provider-preflight.json", profile.providerPreflightEvidenceJson());
        assertTrue(profile.describeMissingRequirements().contains("rd.multi-agent.smoke.provider-preflight-evidence-json"));
    }

    @Test
    void shouldRequireProviderSecretEnvNamesAndRealEnvValues() {
        Map<String, String> properties = Map.ofEntries(
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
                entry("rd.multi-agent.smoke.github-code-platform-mode", "real"),
                entry("rd.multi-agent.smoke.github-auth-mode", "PAT_LOCAL_SMOKE"),
                entry("rd.multi-agent.smoke.github-credential-env-names", "GITHUB_PAT"),
                entry("rd.multi-agent.smoke.secret-scan-needles", "secret")
        );

        assertTrue(MultiAgentProductionAcceptanceProfile.missingRequiredProperties(properties, Map.of())
                .contains("rd.multi-agent.smoke.provider-secret-env-names>=2"));

        Map<String, String> withEnvNames = new java.util.LinkedHashMap<>(properties);
        withEnvNames.put("rd.multi-agent.smoke.provider-secret-env-names", "LONGCAT_API_KEY,ANTHROPIC_API_KEY");

        assertEquals(
                java.util.List.of("env:ANTHROPIC_API_KEY"),
                MultiAgentProductionAcceptanceProfile.missingRequiredProperties(
                        withEnvNames,
                        Map.of("LONGCAT_API_KEY", "longcat-secret", "GITHUB_PAT", "github-secret")
                )
        );
    }

    @Test
    void shouldDescribeProviderSecretEnvNamesAsRequiredProductionEvidence() {
        MultiAgentProductionAcceptanceProfile profile = MultiAgentProductionAcceptanceProfile.from(
                validProperties(),
                validEnv()
        );

        String description = profile.describeMissingRequirements();

        assertTrue(description.contains("- rd.multi-agent.smoke.provider-secret-env-names"));
        assertTrue(description.indexOf("- rd.multi-agent.smoke.provider-secret-env-names")
                < description.indexOf("Optional:"));
    }

    @Test
    void shouldRequireDistinctProviderAndGitHubCredentialEnvNames() {
        Map<String, String> properties = Map.ofEntries(
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
                entry("rd.multi-agent.smoke.provider-secret-env-names", "LONGCAT_API_KEY,LONGCAT_API_KEY"),
                entry("rd.multi-agent.smoke.github-code-platform-mode", "real"),
                entry("rd.multi-agent.smoke.github-auth-mode", "GITHUB_APP"),
                entry("rd.multi-agent.smoke.github-credential-env-names",
                        "GITHUB_APP_ID,GITHUB_APP_ID,GITHUB_PRIVATE_KEY"),
                entry("rd.multi-agent.smoke.secret-scan-needles", "secret")
        );

        assertEquals(
                java.util.List.of(
                        "rd.multi-agent.smoke.provider-secret-env-names distinct>=2",
                        "rd.multi-agent.smoke.github-credential-env-names distinct>=3"
                ),
                MultiAgentProductionAcceptanceProfile.missingRequiredProperties(
                        properties,
                        Map.of(
                                "LONGCAT_API_KEY", "longcat-secret",
                                "GITHUB_APP_ID", "app-id",
                                "GITHUB_PRIVATE_KEY", "private-key"
                        )
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> MultiAgentProductionAcceptanceProfile.from(
                        properties,
                        Map.of(
                                "LONGCAT_API_KEY", "longcat-secret",
                                "GITHUB_APP_ID", "app-id",
                                "GITHUB_PRIVATE_KEY", "private-key"
                        )
                )
        );
    }

    @Test
    void shouldRequireProductionEndpointAndRepositoryUrlShapes() {
        Map<String, String> properties = Map.ofEntries(
                entry("rd.multi-agent.smoke.production-evidence", "true"),
                entry("rd.multi-agent.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.multi-agent.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.multi-agent.smoke.executed-by", "qa-runner"),
                entry("rd.multi-agent.smoke.base-url", "mock://rd-bot"),
                entry("rd.multi-agent.smoke.postgres-url", "jdbc:h2:mem:rd_bot"),
                entry("rd.multi-agent.smoke.postgres-user", "rd_bot"),
                entry("rd.multi-agent.smoke.postgres-password", "secret"),
                entry("rd.multi-agent.smoke.repository-url", "https://github.com/other/rd-bot-smoke.git"),
                entry("rd.multi-agent.smoke.repo-owner", "acme"),
                entry("rd.multi-agent.smoke.repo-name", "rd-bot-smoke"),
                entry("rd.multi-agent.smoke.expected-provider-count", "2"),
                entry("rd.multi-agent.smoke.provider-secret-env-names", "LONGCAT_API_KEY,ANTHROPIC_API_KEY"),
                entry("rd.multi-agent.smoke.github-code-platform-mode", "real"),
                entry("rd.multi-agent.smoke.github-auth-mode", "PAT_LOCAL_SMOKE"),
                entry("rd.multi-agent.smoke.github-credential-env-names", "GITHUB_PAT"),
                entry("rd.multi-agent.smoke.secret-scan-needles", "secret")
        );

        java.util.List<String> missing = MultiAgentProductionAcceptanceProfile.missingRequiredProperties(
                properties,
                Map.of(
                        "LONGCAT_API_KEY", "longcat-secret",
                        "ANTHROPIC_API_KEY", "anthropic-secret",
                        "GITHUB_PAT", "github-secret"
                )
        );

        assertTrue(missing.contains("rd.multi-agent.smoke.base-url=http(s)://<host>"));
        assertTrue(missing.contains("rd.multi-agent.smoke.postgres-url=jdbc:postgresql://<host>/<database>"));
        assertTrue(missing.contains("rd.multi-agent.smoke.repository-url matches repo-owner/repo-name"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MultiAgentProductionAcceptanceProfile.from(
                        properties,
                        Map.of(
                                "LONGCAT_API_KEY", "longcat-secret",
                                "ANTHROPIC_API_KEY", "anthropic-secret",
                                "GITHUB_PAT", "github-secret"
                        )
                )
        );
    }

    @Test
    void shouldRequireAtLeastOneEffectiveSecretScanNeedle() {
        Map<String, String> properties = Map.ofEntries(
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
                entry("rd.multi-agent.smoke.secret-scan-needles", ", ,")
        );

        assertTrue(MultiAgentProductionAcceptanceProfile.missingRequiredProperties(
                properties,
                Map.of(
                        "LONGCAT_API_KEY", "longcat-secret",
                        "ANTHROPIC_API_KEY", "anthropic-secret",
                        "GITHUB_PAT", "github-secret"
                )
        ).contains("rd.multi-agent.smoke.secret-scan-needles>=1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MultiAgentProductionAcceptanceProfile.from(
                        properties,
                        Map.of(
                                "LONGCAT_API_KEY", "longcat-secret",
                                "ANTHROPIC_API_KEY", "anthropic-secret",
                                "GITHUB_PAT", "github-secret"
                        )
                )
        );
    }

    @Test
    void shouldRequireRealGitHubCodePlatformAndCredentialEvidence() {
        Map<String, String> properties = Map.ofEntries(
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
                entry("rd.multi-agent.smoke.github-code-platform-mode", "mock"),
                entry("rd.multi-agent.smoke.github-auth-mode", "PAT_LOCAL_SMOKE"),
                entry("rd.multi-agent.smoke.github-credential-env-names", "GITHUB_PAT"),
                entry("rd.multi-agent.smoke.secret-scan-needles", "secret")
        );

        assertEquals(
                java.util.List.of("rd.multi-agent.smoke.github-code-platform-mode=real", "env:GITHUB_PAT"),
                MultiAgentProductionAcceptanceProfile.missingRequiredProperties(
                        properties,
                        Map.of("LONGCAT_API_KEY", "longcat-secret", "ANTHROPIC_API_KEY", "anthropic-secret")
                )
        );
    }

    private static Map<String, String> validProperties() {
        return Map.ofEntries(
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
                entry("rd.multi-agent.smoke.secret-scan-needles", "secret")
        );
    }

    private static Map<String, String> validEnv() {
        return Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        );
    }
}
