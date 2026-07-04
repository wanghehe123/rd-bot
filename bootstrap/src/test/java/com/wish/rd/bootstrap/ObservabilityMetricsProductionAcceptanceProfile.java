package com.wish.rd.bootstrap;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Observability metrics production smoke properties.
 */
record ObservabilityMetricsProductionAcceptanceProfile(
        String rdBotVersion,
        String environmentId,
        String executedBy,
        String baseUrl,
        String postgresUrl,
        String postgresUser,
        String postgresPassword,
        String taskId,
        String githubPrRemoteEvidenceJson,
        List<String> secretScanNeedles
) {

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "rd.observability-metrics.smoke.production-evidence",
            "rd.observability-metrics.smoke.rd-bot-version",
            "rd.observability-metrics.smoke.environment-id",
            "rd.observability-metrics.smoke.executed-by",
            "rd.observability-metrics.smoke.base-url",
            "rd.observability-metrics.smoke.postgres-url",
            "rd.observability-metrics.smoke.postgres-user",
            "rd.observability-metrics.smoke.postgres-password",
            "rd.observability-metrics.smoke.task-id",
            "rd.observability-metrics.smoke.github-pr-remote-evidence-json",
            "rd.observability-metrics.smoke.secret-scan-needles"
    );

    ObservabilityMetricsProductionAcceptanceProfile {
        rdBotVersion = requireText(rdBotVersion, "rdBotVersion");
        environmentId = requireText(environmentId, "environmentId");
        executedBy = requireText(executedBy, "executedBy");
        baseUrl = requireHttpUrl(baseUrl, "baseUrl");
        postgresUrl = requirePostgresJdbcUrl(postgresUrl);
        postgresUser = requireText(postgresUser, "postgresUser");
        postgresPassword = requireText(postgresPassword, "postgresPassword");
        taskId = requireText(taskId, "taskId");
        githubPrRemoteEvidenceJson = requireLocalEvidenceFilePath(
                githubPrRemoteEvidenceJson,
                "githubPrRemoteEvidenceJson"
        );
        secretScanNeedles = effectiveItems(secretScanNeedles);
        if (secretScanNeedles.isEmpty()) {
            throw new IllegalArgumentException("secretScanNeedles must include at least one effective value");
        }
    }

    static List<String> requiredPropertyNames() {
        return REQUIRED_PROPERTIES;
    }

    static Map<String, String> systemProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        REQUIRED_PROPERTIES.forEach(name -> properties.put(name, System.getProperty(name, "")));
        return properties;
    }

    static ObservabilityMetricsProductionAcceptanceProfile from(Map<String, String> properties) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        List<String> missing = missingRequiredProperties(safeProperties);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "missing observability metrics production acceptance properties: " + missing);
        }
        return new ObservabilityMetricsProductionAcceptanceProfile(
                value(safeProperties, "rd.observability-metrics.smoke.rd-bot-version"),
                value(safeProperties, "rd.observability-metrics.smoke.environment-id"),
                value(safeProperties, "rd.observability-metrics.smoke.executed-by"),
                value(safeProperties, "rd.observability-metrics.smoke.base-url"),
                value(safeProperties, "rd.observability-metrics.smoke.postgres-url"),
                value(safeProperties, "rd.observability-metrics.smoke.postgres-user"),
                value(safeProperties, "rd.observability-metrics.smoke.postgres-password"),
                value(safeProperties, "rd.observability-metrics.smoke.task-id"),
                value(safeProperties, "rd.observability-metrics.smoke.github-pr-remote-evidence-json"),
                splitCsv(value(safeProperties, "rd.observability-metrics.smoke.secret-scan-needles"))
        );
    }

    static List<String> missingRequiredProperties(Map<String, String> properties) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        for (String name : REQUIRED_PROPERTIES) {
            if (value(safeProperties, name).isBlank()) {
                missing.add(name);
            }
        }
        String productionEvidence = value(safeProperties, "rd.observability-metrics.smoke.production-evidence");
        if (!productionEvidence.isBlank() && !"true".equalsIgnoreCase(productionEvidence)) {
            missing.remove("rd.observability-metrics.smoke.production-evidence");
            missing.add("rd.observability-metrics.smoke.production-evidence=true");
        }
        validateUrlProperty(safeProperties, missing, "rd.observability-metrics.smoke.base-url");
        validatePostgresProperty(safeProperties, missing);
        validateLocalEvidenceFilePath(
                safeProperties,
                missing,
                "rd.observability-metrics.smoke.github-pr-remote-evidence-json"
        );
        String secretScanNeedles = value(safeProperties, "rd.observability-metrics.smoke.secret-scan-needles");
        if (!secretScanNeedles.isBlank() && splitCsv(secretScanNeedles).isEmpty()) {
            missing.add("rd.observability-metrics.smoke.secret-scan-needles>=1");
        }
        return List.copyOf(missing);
    }

    String metricsEndpointUrl() {
        return appendPath(baseUrl, "actuator/prometheus");
    }

    String describeMissingRequirements() {
        return """
                Required real observability metrics smoke properties:
                - rd.observability-metrics.smoke.production-evidence=true
                - rd.observability-metrics.smoke.rd-bot-version
                - rd.observability-metrics.smoke.environment-id
                - rd.observability-metrics.smoke.executed-by
                - rd.observability-metrics.smoke.base-url
                - rd.observability-metrics.smoke.postgres-url
                - rd.observability-metrics.smoke.postgres-user
                - rd.observability-metrics.smoke.postgres-password
                - rd.observability-metrics.smoke.task-id
                - rd.observability-metrics.smoke.github-pr-remote-evidence-json
                - rd.observability-metrics.smoke.secret-scan-needles
                """.strip();
    }

    private static void validateUrlProperty(Map<String, String> properties, List<String> missing, String name) {
        String value = value(properties, name);
        if (!value.isBlank() && !isHttpUrl(value)) {
            missing.add(name + "=http(s)://<host>");
        }
    }

    private static void validatePostgresProperty(Map<String, String> properties, List<String> missing) {
        String value = value(properties, "rd.observability-metrics.smoke.postgres-url");
        if (!value.isBlank() && !isPostgresJdbcUrl(value)) {
            missing.add("rd.observability-metrics.smoke.postgres-url=jdbc:postgresql://<host>/<db>");
        }
    }

    private static void validateLocalEvidenceFilePath(
            Map<String, String> properties,
            List<String> missing,
            String name
    ) {
        String value = value(properties, name);
        if (!value.isBlank() && looksLikeUri(value)) {
            missing.add(name + "=<file>");
        }
    }

    private static List<String> splitCsv(String value) {
        String safeValue = value == null ? "" : value.strip();
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
        return values.stream()
                .map(value -> value == null ? "" : value.strip())
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String value(Map<String, String> properties, String name) {
        return properties.getOrDefault(name, "").strip();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String requireHttpUrl(String value, String fieldName) {
        String normalized = requireText(value, fieldName);
        if (!isHttpUrl(normalized)) {
            throw new IllegalArgumentException(fieldName + " must be http(s)://<host>");
        }
        return normalized;
    }

    private static String requirePostgresJdbcUrl(String value) {
        String normalized = requireText(value, "postgresUrl");
        if (!isPostgresJdbcUrl(normalized)) {
            throw new IllegalArgumentException("postgresUrl must start with jdbc:postgresql://");
        }
        return normalized;
    }

    private static String requireLocalEvidenceFilePath(String value, String fieldName) {
        String normalized = requireText(value, fieldName);
        if (looksLikeUri(normalized)) {
            throw new IllegalArgumentException(fieldName + " must be a local JSON evidence file path");
        }
        return normalized;
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isPostgresJdbcUrl(String value) {
        return value != null && value.strip().startsWith("jdbc:postgresql://");
    }

    private static boolean looksLikeUri(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.contains("://");
    }

    private static String appendPath(String baseUrl, String path) {
        String safeBaseUrl = baseUrl == null ? "" : baseUrl.strip();
        String safePath = path == null ? "" : path.strip();
        while (safeBaseUrl.endsWith("/")) {
            safeBaseUrl = safeBaseUrl.substring(0, safeBaseUrl.length() - 1);
        }
        while (safePath.startsWith("/")) {
            safePath = safePath.substring(1);
        }
        return safeBaseUrl + "/" + safePath;
    }
}
