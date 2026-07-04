package com.wish.rd.bootstrap;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Requirement review blocker production smoke properties.
 */
record RequirementReviewBlockerProductionAcceptanceProfile(
        String rdBotVersion,
        String environmentId,
        String executedBy,
        String baseUrl,
        String postgresUrl,
        String postgresUser,
        String postgresPassword,
        String taskId,
        String reviewArtifactUri,
        String feishuAlertMessageId,
        List<String> secretScanNeedles
) {

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "rd.requirement-review.smoke.production-evidence",
            "rd.requirement-review.smoke.rd-bot-version",
            "rd.requirement-review.smoke.environment-id",
            "rd.requirement-review.smoke.executed-by",
            "rd.requirement-review.smoke.base-url",
            "rd.requirement-review.smoke.postgres-url",
            "rd.requirement-review.smoke.postgres-user",
            "rd.requirement-review.smoke.postgres-password",
            "rd.requirement-review.smoke.task-id",
            "rd.requirement-review.smoke.review-artifact-uri",
            "rd.requirement-review.smoke.feishu-alert-message-id",
            "rd.requirement-review.smoke.secret-scan-needles"
    );

    RequirementReviewBlockerProductionAcceptanceProfile {
        rdBotVersion = requireText(rdBotVersion, "rdBotVersion");
        environmentId = requireText(environmentId, "environmentId");
        executedBy = requireText(executedBy, "executedBy");
        baseUrl = requireHttpUrl(baseUrl, "baseUrl");
        postgresUrl = requirePostgresJdbcUrl(postgresUrl);
        postgresUser = requireText(postgresUser, "postgresUser");
        postgresPassword = requireText(postgresPassword, "postgresPassword");
        taskId = requireText(taskId, "taskId");
        reviewArtifactUri = requireProductionEvidenceUri(reviewArtifactUri, "reviewArtifactUri");
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

    static RequirementReviewBlockerProductionAcceptanceProfile from(Map<String, String> properties) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        List<String> missing = missingRequiredProperties(safeProperties);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "missing requirement review blocker production acceptance properties: " + missing
            );
        }
        return new RequirementReviewBlockerProductionAcceptanceProfile(
                value(safeProperties, "rd.requirement-review.smoke.rd-bot-version"),
                value(safeProperties, "rd.requirement-review.smoke.environment-id"),
                value(safeProperties, "rd.requirement-review.smoke.executed-by"),
                value(safeProperties, "rd.requirement-review.smoke.base-url"),
                value(safeProperties, "rd.requirement-review.smoke.postgres-url"),
                value(safeProperties, "rd.requirement-review.smoke.postgres-user"),
                value(safeProperties, "rd.requirement-review.smoke.postgres-password"),
                value(safeProperties, "rd.requirement-review.smoke.task-id"),
                value(safeProperties, "rd.requirement-review.smoke.review-artifact-uri"),
                value(safeProperties, "rd.requirement-review.smoke.feishu-alert-message-id"),
                splitCsv(value(safeProperties, "rd.requirement-review.smoke.secret-scan-needles"))
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
        String productionEvidence = value(safeProperties, "rd.requirement-review.smoke.production-evidence");
        if (!productionEvidence.isBlank() && !"true".equalsIgnoreCase(productionEvidence)) {
            missing.remove("rd.requirement-review.smoke.production-evidence");
            missing.add("rd.requirement-review.smoke.production-evidence=true");
        }
        String baseUrl = value(safeProperties, "rd.requirement-review.smoke.base-url");
        if (!baseUrl.isBlank() && !isHttpUrl(baseUrl)) {
            missing.add("rd.requirement-review.smoke.base-url=http(s)://<host>");
        }
        String postgresUrl = value(safeProperties, "rd.requirement-review.smoke.postgres-url");
        if (!postgresUrl.isBlank() && !isPostgresJdbcUrl(postgresUrl)) {
            missing.add("rd.requirement-review.smoke.postgres-url=jdbc:postgresql://<host>/<db>");
        }
        String reviewArtifactUri = value(safeProperties, "rd.requirement-review.smoke.review-artifact-uri");
        if (!reviewArtifactUri.isBlank() && !ProductionEvidenceUris.isProductionArtifactUri(reviewArtifactUri)) {
            missing.add("rd.requirement-review.smoke.review-artifact-uri=http(s)|s3|rd-artifact");
        }
        String secretScanNeedles = value(safeProperties, "rd.requirement-review.smoke.secret-scan-needles");
        if (!secretScanNeedles.isBlank() && splitCsv(secretScanNeedles).isEmpty()) {
            missing.add("rd.requirement-review.smoke.secret-scan-needles>=1");
        }
        return List.copyOf(missing);
    }

    String describeMissingRequirements() {
        return """
                Required real requirement review blocker smoke properties:
                - rd.requirement-review.smoke.production-evidence=true
                - rd.requirement-review.smoke.rd-bot-version
                - rd.requirement-review.smoke.environment-id
                - rd.requirement-review.smoke.executed-by
                - rd.requirement-review.smoke.base-url
                - rd.requirement-review.smoke.postgres-url
                - rd.requirement-review.smoke.postgres-user
                - rd.requirement-review.smoke.postgres-password
                - rd.requirement-review.smoke.task-id
                - rd.requirement-review.smoke.review-artifact-uri
                - rd.requirement-review.smoke.feishu-alert-message-id
                - rd.requirement-review.smoke.secret-scan-needles
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

    private static String requireProductionEvidenceUri(String value, String fieldName) {
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
