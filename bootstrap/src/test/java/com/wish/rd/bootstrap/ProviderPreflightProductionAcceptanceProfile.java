package com.wish.rd.bootstrap;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Provider preflight production smoke properties.
 */
record ProviderPreflightProductionAcceptanceProfile(
        String rdBotVersion,
        String environmentId,
        String executedBy,
        int expectedProviderCount,
        List<ProviderSpec> providers,
        Duration requestTimeout,
        List<String> secretScanNeedles
) {

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "rd.provider.preflight.smoke.production-evidence",
            "rd.provider.preflight.smoke.rd-bot-version",
            "rd.provider.preflight.smoke.environment-id",
            "rd.provider.preflight.smoke.executed-by",
            "rd.provider.preflight.smoke.expected-provider-count",
            "rd.provider.preflight.smoke.providers",
            "rd.provider.preflight.smoke.secret-scan-needles"
    );
    private static final String DEFAULT_PROVIDERS = "long-cat,minimax";

    ProviderPreflightProductionAcceptanceProfile {
        rdBotVersion = requireText(rdBotVersion, "rdBotVersion");
        environmentId = requireText(environmentId, "environmentId");
        executedBy = requireText(executedBy, "executedBy");
        if (expectedProviderCount < 2) {
            throw new IllegalArgumentException("expectedProviderCount must be at least 2");
        }
        providers = providers == null ? List.of() : List.copyOf(providers);
        if (providers.size() < expectedProviderCount) {
            throw new IllegalArgumentException("providers must cover expectedProviderCount");
        }
        if (providers.stream().map(ProviderSpec::name).distinct().count() < expectedProviderCount) {
            throw new IllegalArgumentException("distinct providers must cover expectedProviderCount");
        }
        requestTimeout = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                ? Duration.ofSeconds(60)
                : requestTimeout;
        secretScanNeedles = effectiveItems(secretScanNeedles);
        if (secretScanNeedles.isEmpty()) {
            throw new IllegalArgumentException("secretScanNeedles must include at least one effective value");
        }
    }

    static List<String> requiredPropertyNames() {
        return REQUIRED_PROPERTIES;
    }

    static Map<String, String> systemProperties() {
        java.util.LinkedHashMap<String, String> properties = new java.util.LinkedHashMap<>();
        REQUIRED_PROPERTIES.forEach(name -> properties.put(name, System.getProperty(name, "")));
        properties.put("rd.provider.preflight.smoke.providers", System.getProperty(
                "rd.provider.preflight.smoke.providers",
                DEFAULT_PROVIDERS
        ));
        properties.put("rd.provider.preflight.smoke.request-timeout-seconds", System.getProperty(
                "rd.provider.preflight.smoke.request-timeout-seconds",
                "60"
        ));
        for (String provider : splitCsv(properties.get("rd.provider.preflight.smoke.providers"))) {
            for (String field : List.of("protocol", "base-url", "api-key-env", "model", "auth-header")) {
                String key = providerPropertyName(provider, field);
                properties.put(key, System.getProperty(key, ""));
            }
        }
        return Map.copyOf(properties);
    }

    static ProviderPreflightProductionAcceptanceProfile from(Map<String, String> properties) {
        return from(properties, System.getenv());
    }

    static ProviderPreflightProductionAcceptanceProfile from(Map<String, String> properties, Map<String, String> env) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        List<String> missing = missingRequiredProperties(safeProperties, env);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing provider preflight production properties: " + missing);
        }
        return new ProviderPreflightProductionAcceptanceProfile(
                value(safeProperties, "rd.provider.preflight.smoke.rd-bot-version"),
                value(safeProperties, "rd.provider.preflight.smoke.environment-id"),
                value(safeProperties, "rd.provider.preflight.smoke.executed-by"),
                Integer.parseInt(value(safeProperties, "rd.provider.preflight.smoke.expected-provider-count")),
                providerSpecs(safeProperties),
                durationSeconds(safeProperties, "rd.provider.preflight.smoke.request-timeout-seconds", 60),
                splitCsv(value(safeProperties, "rd.provider.preflight.smoke.secret-scan-needles"))
        );
    }

    static List<String> missingRequiredProperties(Map<String, String> properties) {
        return missingRequiredProperties(properties, System.getenv());
    }

    static List<String> missingRequiredProperties(Map<String, String> properties, Map<String, String> env) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        Map<String, String> safeEnv = env == null ? Map.of() : env;
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        for (String name : REQUIRED_PROPERTIES) {
            if (value(safeProperties, name).isBlank()) {
                missing.add(name);
            }
        }
        String productionEvidence = value(safeProperties, "rd.provider.preflight.smoke.production-evidence");
        if (!productionEvidence.isBlank() && !"true".equalsIgnoreCase(productionEvidence)) {
            missing.remove("rd.provider.preflight.smoke.production-evidence");
            missing.add("rd.provider.preflight.smoke.production-evidence=true");
        }
        String expected = value(safeProperties, "rd.provider.preflight.smoke.expected-provider-count");
        if (!expected.isBlank() && parseInt(expected) < 2) {
            missing.remove("rd.provider.preflight.smoke.expected-provider-count");
            missing.add("rd.provider.preflight.smoke.expected-provider-count>=2");
        }
        int expectedProviderCount = Math.max(2, parseInt(defaultWhenBlank(expected, "2")));
        List<String> providers = splitCsv(defaultWhenBlank(
                value(safeProperties, "rd.provider.preflight.smoke.providers"),
                DEFAULT_PROVIDERS
        ));
        if (providers.size() < expectedProviderCount) {
            missing.add("rd.provider.preflight.smoke.providers>=" + expectedProviderCount);
        } else if (new LinkedHashSet<>(providers).size() < expectedProviderCount) {
            missing.add("rd.provider.preflight.smoke.providers distinct>=" + expectedProviderCount);
        }
        for (ProviderSpec provider : providerSpecs(safeProperties)) {
            if (!isSupportedProtocol(provider.protocol())) {
                missing.add(providerPropertyName(provider.name(), "protocol") + "=supported");
            }
            if (!isHttpUrl(provider.baseUrl())) {
                missing.add(providerPropertyName(provider.name(), "base-url") + "=http(s)://<host>");
            }
            if (provider.model().isBlank()) {
                missing.add(providerPropertyName(provider.name(), "model"));
            }
            if (provider.apiKeyEnv().isBlank()) {
                missing.add(providerPropertyName(provider.name(), "api-key-env"));
            } else if (value(safeEnv, provider.apiKeyEnv()).isBlank()) {
                missing.add("env:" + provider.apiKeyEnv());
            }
            if (!isSupportedAuthHeader(provider.authHeader())) {
                missing.add(providerPropertyName(provider.name(), "auth-header") + "=supported");
            }
        }
        String secretScanNeedles = value(safeProperties, "rd.provider.preflight.smoke.secret-scan-needles");
        if (!secretScanNeedles.isBlank() && splitCsv(secretScanNeedles).isEmpty()) {
            missing.add("rd.provider.preflight.smoke.secret-scan-needles>=1");
        }
        return List.copyOf(missing);
    }

    String describeMissingRequirements() {
        return """
                Required real provider preflight properties:
                - rd.provider.preflight.smoke.production-evidence=true
                - rd.provider.preflight.smoke.rd-bot-version
                - rd.provider.preflight.smoke.environment-id
                - rd.provider.preflight.smoke.executed-by
                - rd.provider.preflight.smoke.expected-provider-count>=2
                - rd.provider.preflight.smoke.providers
                - rd.provider.preflight.smoke.secret-scan-needles
                Provider defaults mirror application.yaml for long-cat and minimax.
                Optional provider overrides:
                - rd.provider.preflight.smoke.<provider>.protocol
                - rd.provider.preflight.smoke.<provider>.base-url
                - rd.provider.preflight.smoke.<provider>.api-key-env
                - rd.provider.preflight.smoke.<provider>.model
                - rd.provider.preflight.smoke.<provider>.auth-header
                - rd.provider.preflight.smoke.request-timeout-seconds
                """.strip();
    }

    private static List<ProviderSpec> providerSpecs(Map<String, String> properties) {
        List<String> names = splitCsv(defaultWhenBlank(
                value(properties, "rd.provider.preflight.smoke.providers"),
                DEFAULT_PROVIDERS
        ));
        return names.stream()
                .map(name -> providerSpec(properties, name))
                .toList();
    }

    private static ProviderSpec providerSpec(Map<String, String> properties, String name) {
        ProviderDefaults defaults = ProviderDefaults.forName(name);
        return new ProviderSpec(
                name,
                defaultWhenBlank(value(properties, providerPropertyName(name, "protocol")), defaults.protocol()),
                defaultWhenBlank(value(properties, providerPropertyName(name, "base-url")), defaults.baseUrl()),
                defaultWhenBlank(value(properties, providerPropertyName(name, "api-key-env")), defaults.apiKeyEnv()),
                defaultWhenBlank(value(properties, providerPropertyName(name, "model")), defaults.model()),
                defaultWhenBlank(value(properties, providerPropertyName(name, "auth-header")), defaults.authHeader())
        );
    }

    private static String providerPropertyName(String provider, String field) {
        return "rd.provider.preflight.smoke." + safe(provider) + "." + field;
    }

    private static boolean isSupportedProtocol(String value) {
        return switch (safe(value)) {
            case "anthropic-compatible", "anthropic-claude-code", "claude-code", "anthropic",
                    "openai-chat-completions" -> true;
            default -> false;
        };
    }

    private static boolean isSupportedAuthHeader(String value) {
        return switch (safe(value)) {
            case "authorization-bearer", "x-api-key" -> true;
            default -> false;
        };
    }

    private static boolean isHttpUrl(String value) {
        try {
            java.net.URI uri = new java.net.URI(safe(value));
            String scheme = safe(uri.getScheme()).toLowerCase(java.util.Locale.ROOT);
            String host = safe(uri.getHost());
            return ("http".equals(scheme) || "https".equals(scheme)) && !host.isBlank();
        } catch (Exception exception) {
            return false;
        }
    }

    private static Duration durationSeconds(Map<String, String> properties, String name, long defaultSeconds) {
        long seconds = parseLong(defaultWhenBlank(value(properties, name), Long.toString(defaultSeconds)));
        return Duration.ofSeconds(seconds <= 0 ? defaultSeconds : seconds);
    }

    private static List<String> splitCsv(String value) {
        String safeValue = safe(value);
        if (safeValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(safeValue.split(","))
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static List<String> effectiveItems(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(ProviderPreflightProductionAcceptanceProfile::safe)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String value(Map<String, String> properties, String name) {
        return properties.getOrDefault(name, "").strip();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(safe(value));
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(safe(value));
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String defaultWhenBlank(String value, String defaultValue) {
        String normalized = safe(value);
        return normalized.isBlank() ? defaultValue : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    record ProviderSpec(
            String name,
            String protocol,
            String baseUrl,
            String apiKeyEnv,
            String model,
            String authHeader
    ) {

        ProviderSpec {
            name = requireText(name, "provider.name");
            protocol = requireText(protocol, "provider.protocol");
            baseUrl = requireText(baseUrl, "provider.baseUrl");
            apiKeyEnv = requireText(apiKeyEnv, "provider.apiKeyEnv");
            model = requireText(model, "provider.model");
            authHeader = requireText(authHeader, "provider.authHeader");
        }

        boolean openAiChatCompletions() {
            return "openai-chat-completions".equals(protocol);
        }

        String adapterName() {
            return openAiChatCompletions() ? "openai-chat-completions" : "docker-claude-code";
        }
    }

    private record ProviderDefaults(
            String protocol,
            String baseUrl,
            String apiKeyEnv,
            String model,
            String authHeader
    ) {

        static ProviderDefaults forName(String name) {
            return switch (safe(name)) {
                case "long-cat" -> new ProviderDefaults(
                        "anthropic-compatible",
                        "https://api.longcat.chat/anthropic",
                        "LONGCAT_API_KEY",
                        "LongCat-2.0",
                        "authorization-bearer"
                );
                case "minimax" -> new ProviderDefaults(
                        "openai-chat-completions",
                        "https://api.minimaxi.com/v1",
                        "MINIMAX_API_KEY",
                        "MiniMax-M3",
                        "authorization-bearer"
                );
                default -> new ProviderDefaults(
                        "anthropic-compatible",
                        "",
                        "",
                        "",
                        "x-api-key"
                );
            };
        }
    }
}
