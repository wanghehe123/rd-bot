package com.wish.rd.bootstrap;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QA failure blocker production smoke properties.
 */
record QaFailureBlockerProductionAcceptanceProfile(
        String rdBotVersion,
        String environmentId,
        String executedBy,
        String baseUrl,
        String postgresUrl,
        String postgresUser,
        String postgresPassword,
        String taskId,
        String qaReportArtifactUri,
        List<String> validationLogArtifactUris,
        String feishuAlertMessageId,
        List<String> secretScanNeedles
) {

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "rd.qa-failure.smoke.production-evidence",
            "rd.qa-failure.smoke.rd-bot-version",
            "rd.qa-failure.smoke.environment-id",
            "rd.qa-failure.smoke.executed-by",
            "rd.qa-failure.smoke.base-url",
            "rd.qa-failure.smoke.postgres-url",
            "rd.qa-failure.smoke.postgres-user",
            "rd.qa-failure.smoke.postgres-password",
            "rd.qa-failure.smoke.task-id",
            "rd.qa-failure.smoke.qa-report-artifact-uri",
            "rd.qa-failure.smoke.validation-log-artifact-uris",
            "rd.qa-failure.smoke.feishu-alert-message-id",
            "rd.qa-failure.smoke.secret-scan-needles"
    );

    QaFailureBlockerProductionAcceptanceProfile {
        rdBotVersion = requireText(rdBotVersion, "rdBotVersion");
        environmentId = requireText(environmentId, "environmentId");
        executedBy = requireText(executedBy, "executedBy");
        baseUrl = requireHttpUrl(baseUrl, "baseUrl");
        postgresUrl = requirePostgresJdbcUrl(postgresUrl);
        postgresUser = requireText(postgresUser, "postgresUser");
        postgresPassword = requireText(postgresPassword, "postgresPassword");
        taskId = requireText(taskId, "taskId");
        qaReportArtifactUri = requireProductionArtifactUri(qaReportArtifactUri, "qaReportArtifactUri");
        validationLogArtifactUris = effectiveItems(validationLogArtifactUris);
        if (validationLogArtifactUris.isEmpty()
                || validationLogArtifactUris.stream().anyMatch(uri -> !ProductionEvidenceUris.isProductionArtifactUri(uri))
                || validationLogArtifactUris.stream().distinct().count() != validationLogArtifactUris.size()) {
            throw new IllegalArgumentException("validationLogArtifactUris must be distinct production artifact URIs");
        }
        feishuAlertMessageId = requireText(feishuAlertMessageId, "feishuAlertMessageId");
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

    static QaFailureBlockerProductionAcceptanceProfile from(Map<String, String> properties) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        List<String> missing = missingRequiredProperties(safeProperties);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing QA failure production acceptance properties: " + missing);
        }
        return new QaFailureBlockerProductionAcceptanceProfile(
                value(safeProperties, "rd.qa-failure.smoke.rd-bot-version"),
                value(safeProperties, "rd.qa-failure.smoke.environment-id"),
                value(safeProperties, "rd.qa-failure.smoke.executed-by"),
                value(safeProperties, "rd.qa-failure.smoke.base-url"),
                value(safeProperties, "rd.qa-failure.smoke.postgres-url"),
                value(safeProperties, "rd.qa-failure.smoke.postgres-user"),
                value(safeProperties, "rd.qa-failure.smoke.postgres-password"),
                value(safeProperties, "rd.qa-failure.smoke.task-id"),
                value(safeProperties, "rd.qa-failure.smoke.qa-report-artifact-uri"),
                splitCsv(value(safeProperties, "rd.qa-failure.smoke.validation-log-artifact-uris")),
                value(safeProperties, "rd.qa-failure.smoke.feishu-alert-message-id"),
                splitCsv(value(safeProperties, "rd.qa-failure.smoke.secret-scan-needles"))
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
        String productionEvidence = value(safeProperties, "rd.qa-failure.smoke.production-evidence");
        if (!productionEvidence.isBlank() && !"true".equalsIgnoreCase(productionEvidence)) {
            missing.remove("rd.qa-failure.smoke.production-evidence");
            missing.add("rd.qa-failure.smoke.production-evidence=true");
        }
        String baseUrl = value(safeProperties, "rd.qa-failure.smoke.base-url");
        if (!baseUrl.isBlank() && !isHttpUrl(baseUrl)) {
            missing.add("rd.qa-failure.smoke.base-url=http(s)://<host>");
        }
        String postgresUrl = value(safeProperties, "rd.qa-failure.smoke.postgres-url");
        if (!postgresUrl.isBlank() && !isPostgresJdbcUrl(postgresUrl)) {
            missing.add("rd.qa-failure.smoke.postgres-url=jdbc:postgresql://<host>/<db>");
        }
        String qaReportUri = value(safeProperties, "rd.qa-failure.smoke.qa-report-artifact-uri");
        if (!qaReportUri.isBlank() && !ProductionEvidenceUris.isProductionArtifactUri(qaReportUri)) {
            missing.add("rd.qa-failure.smoke.qa-report-artifact-uri=http(s)|s3|rd-artifact");
        }
        List<String> validationLogUris = splitCsv(value(safeProperties,
                "rd.qa-failure.smoke.validation-log-artifact-uris"));
        if (!validationLogUris.isEmpty()
                && (validationLogUris.stream().anyMatch(uri -> !ProductionEvidenceUris.isProductionArtifactUri(uri))
                || validationLogUris.stream().distinct().count() != validationLogUris.size())) {
            missing.add("rd.qa-failure.smoke.validation-log-artifact-uris=http(s)|s3|rd-artifact");
        }
        String secretScanNeedles = value(safeProperties, "rd.qa-failure.smoke.secret-scan-needles");
        if (!secretScanNeedles.isBlank() && splitCsv(secretScanNeedles).isEmpty()) {
            missing.add("rd.qa-failure.smoke.secret-scan-needles>=1");
        }
        return List.copyOf(missing);
    }

    String describeMissingRequirements() {
        return """
                Required real QA failure blocker smoke properties:
                - rd.qa-failure.smoke.production-evidence=true
                - rd.qa-failure.smoke.rd-bot-version
                - rd.qa-failure.smoke.environment-id
                - rd.qa-failure.smoke.executed-by
                - rd.qa-failure.smoke.base-url
                - rd.qa-failure.smoke.postgres-url
                - rd.qa-failure.smoke.postgres-user
                - rd.qa-failure.smoke.postgres-password
                - rd.qa-failure.smoke.task-id
                - rd.qa-failure.smoke.qa-report-artifact-uri
                - rd.qa-failure.smoke.validation-log-artifact-uris
                - rd.qa-failure.smoke.feishu-alert-message-id
                - rd.qa-failure.smoke.secret-scan-needles
                """.strip();
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

    private static String requireProductionArtifactUri(String value, String fieldName) {
        String normalized = requireText(value, fieldName);
        if (!ProductionEvidenceUris.isProductionArtifactUri(normalized)) {
            throw new IllegalArgumentException(fieldName + " must be a production artifact URI");
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
}
