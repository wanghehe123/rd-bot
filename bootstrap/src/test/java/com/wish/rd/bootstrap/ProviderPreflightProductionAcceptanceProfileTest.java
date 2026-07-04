package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderPreflightProductionAcceptanceProfileTest {

    @Test
    void shouldUseApplicationProviderDefaultsAndRequireRealSecretEnvValues() {
        Map<String, String> properties = Map.ofEntries(
                entry("rd.provider.preflight.smoke.production-evidence", "true"),
                entry("rd.provider.preflight.smoke.rd-bot-version", "0.1.0-test"),
                entry("rd.provider.preflight.smoke.environment-id", "local-real"),
                entry("rd.provider.preflight.smoke.executed-by", "junit"),
                entry("rd.provider.preflight.smoke.expected-provider-count", "2"),
                entry("rd.provider.preflight.smoke.providers", "long-cat,minimax"),
                entry("rd.provider.preflight.smoke.secret-scan-needles", "needle-a")
        );

        assertEquals(
                java.util.List.of("env:MINIMAX_API_KEY"),
                ProviderPreflightProductionAcceptanceProfile.missingRequiredProperties(
                        properties,
                        Map.of("LONGCAT_API_KEY", "longcat-secret")
                )
        );

        ProviderPreflightProductionAcceptanceProfile profile = ProviderPreflightProductionAcceptanceProfile.from(
                properties,
                Map.of("LONGCAT_API_KEY", "longcat-secret", "MINIMAX_API_KEY", "minimax-secret")
        );

        assertEquals(2, profile.providers().size());
        assertEquals("long-cat", profile.providers().getFirst().name());
        assertEquals("anthropic-compatible", profile.providers().getFirst().protocol());
        assertEquals("LONGCAT_API_KEY", profile.providers().getFirst().apiKeyEnv());
        assertEquals("minimax", profile.providers().get(1).name());
        assertEquals("openai-chat-completions", profile.providers().get(1).protocol());
        assertEquals("MINIMAX_API_KEY", profile.providers().get(1).apiKeyEnv());
    }

    @Test
    void shouldRequireSupportedProviderProtocol() {
        Map<String, String> properties = Map.ofEntries(
                entry("rd.provider.preflight.smoke.production-evidence", "true"),
                entry("rd.provider.preflight.smoke.rd-bot-version", "0.1.0-test"),
                entry("rd.provider.preflight.smoke.environment-id", "local-real"),
                entry("rd.provider.preflight.smoke.executed-by", "junit"),
                entry("rd.provider.preflight.smoke.expected-provider-count", "2"),
                entry("rd.provider.preflight.smoke.providers", "long-cat,minimax"),
                entry("rd.provider.preflight.smoke.long-cat.protocol", "unsupported"),
                entry("rd.provider.preflight.smoke.secret-scan-needles", "needle-a")
        );

        assertTrue(ProviderPreflightProductionAcceptanceProfile.missingRequiredProperties(
                properties,
                Map.of("LONGCAT_API_KEY", "longcat-secret", "MINIMAX_API_KEY", "minimax-secret")
        ).contains("rd.provider.preflight.smoke.long-cat.protocol=supported"));
    }
}
