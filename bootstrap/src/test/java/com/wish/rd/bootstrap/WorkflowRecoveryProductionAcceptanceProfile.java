package com.wish.rd.bootstrap;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow recovery production smoke properties.
 */
record WorkflowRecoveryProductionAcceptanceProfile(
        String rdBotVersion,
        String environmentId,
        String executedBy,
        String baseUrl,
        String postgresUrl,
        String postgresUser,
        String postgresPassword,
        String taskId,
        int stageRunCountBeforeRestart,
        int stageEventCountBeforeRestart,
        int retryAttemptCount,
        int retainedRetryArtifactCount,
        String startupLogEvidenceUri,
        String databaseSnapshotEvidenceUri,
        List<String> secretScanNeedles
) {

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "rd.workflow.recovery.smoke.production-evidence",
            "rd.workflow.recovery.smoke.rd-bot-version",
            "rd.workflow.recovery.smoke.environment-id",
            "rd.workflow.recovery.smoke.executed-by",
            "rd.workflow.recovery.smoke.base-url",
            "rd.workflow.recovery.smoke.postgres-url",
            "rd.workflow.recovery.smoke.postgres-user",
            "rd.workflow.recovery.smoke.postgres-password",
            "rd.workflow.recovery.smoke.task-id",
            "rd.workflow.recovery.smoke.stage-run-count-before-restart",
            "rd.workflow.recovery.smoke.stage-event-count-before-restart",
            "rd.workflow.recovery.smoke.retry-attempt-count",
            "rd.workflow.recovery.smoke.retained-retry-artifact-count",
            "rd.workflow.recovery.smoke.startup-log-evidence-uri",
            "rd.workflow.recovery.smoke.database-snapshot-evidence-uri",
            "rd.workflow.recovery.smoke.secret-scan-needles"
    );

    WorkflowRecoveryProductionAcceptanceProfile {
        rdBotVersion = requireText(rdBotVersion, "rdBotVersion");
        environmentId = requireText(environmentId, "environmentId");
        executedBy = requireText(executedBy, "executedBy");
        baseUrl = requireHttpUrl(baseUrl, "baseUrl");
        postgresUrl = requirePostgresJdbcUrl(postgresUrl);
        postgresUser = requireText(postgresUser, "postgresUser");
        postgresPassword = requireText(postgresPassword, "postgresPassword");
        taskId = requireText(taskId, "taskId");
        stageRunCountBeforeRestart = requirePositive(stageRunCountBeforeRestart, "stageRunCountBeforeRestart");
        stageEventCountBeforeRestart = requirePositive(stageEventCountBeforeRestart, "stageEventCountBeforeRestart");
        retryAttemptCount = requireNonNegative(retryAttemptCount, "retryAttemptCount");
        retainedRetryArtifactCount = requireNonNegative(retainedRetryArtifactCount, "retainedRetryArtifactCount");
        startupLogEvidenceUri = requireProductionEvidenceUri(startupLogEvidenceUri, "startupLogEvidenceUri");
        databaseSnapshotEvidenceUri = requireProductionEvidenceUri(
                databaseSnapshotEvidenceUri,
                "databaseSnapshotEvidenceUri"
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

    static WorkflowRecoveryProductionAcceptanceProfile from(Map<String, String> properties) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        List<String> missing = missingRequiredProperties(safeProperties);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing workflow recovery production acceptance properties: " + missing);
        }
        return new WorkflowRecoveryProductionAcceptanceProfile(
                value(safeProperties, "rd.workflow.recovery.smoke.rd-bot-version"),
                value(safeProperties, "rd.workflow.recovery.smoke.environment-id"),
                value(safeProperties, "rd.workflow.recovery.smoke.executed-by"),
                value(safeProperties, "rd.workflow.recovery.smoke.base-url"),
                value(safeProperties, "rd.workflow.recovery.smoke.postgres-url"),
                value(safeProperties, "rd.workflow.recovery.smoke.postgres-user"),
                value(safeProperties, "rd.workflow.recovery.smoke.postgres-password"),
                value(safeProperties, "rd.workflow.recovery.smoke.task-id"),
                positiveInt(value(safeProperties, "rd.workflow.recovery.smoke.stage-run-count-before-restart")),
                positiveInt(value(safeProperties, "rd.workflow.recovery.smoke.stage-event-count-before-restart")),
                positiveInt(value(safeProperties, "rd.workflow.recovery.smoke.retry-attempt-count")),
                positiveInt(value(safeProperties, "rd.workflow.recovery.smoke.retained-retry-artifact-count")),
                value(safeProperties, "rd.workflow.recovery.smoke.startup-log-evidence-uri"),
                value(safeProperties, "rd.workflow.recovery.smoke.database-snapshot-evidence-uri"),
                splitCsv(value(safeProperties, "rd.workflow.recovery.smoke.secret-scan-needles"))
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
        String productionEvidence = value(safeProperties, "rd.workflow.recovery.smoke.production-evidence");
        if (!productionEvidence.isBlank() && !"true".equalsIgnoreCase(productionEvidence)) {
            missing.remove("rd.workflow.recovery.smoke.production-evidence");
            missing.add("rd.workflow.recovery.smoke.production-evidence=true");
        }
        requireFormat(safeProperties, missing);
        return List.copyOf(missing);
    }

    String describeMissingRequirements() {
        return """
                Required real workflow recovery smoke properties:
                - rd.workflow.recovery.smoke.production-evidence=true
                - rd.workflow.recovery.smoke.rd-bot-version
                - rd.workflow.recovery.smoke.environment-id
                - rd.workflow.recovery.smoke.executed-by
                - rd.workflow.recovery.smoke.base-url
                - rd.workflow.recovery.smoke.postgres-url
                - rd.workflow.recovery.smoke.postgres-user
                - rd.workflow.recovery.smoke.postgres-password
                - rd.workflow.recovery.smoke.task-id
                - rd.workflow.recovery.smoke.stage-run-count-before-restart
                - rd.workflow.recovery.smoke.stage-event-count-before-restart
                - rd.workflow.recovery.smoke.retry-attempt-count
                - rd.workflow.recovery.smoke.retained-retry-artifact-count
                - rd.workflow.recovery.smoke.startup-log-evidence-uri
                - rd.workflow.recovery.smoke.database-snapshot-evidence-uri
                - rd.workflow.recovery.smoke.secret-scan-needles
                """.strip();
    }

    private static void requireFormat(Map<String, String> properties, List<String> missing) {
        String baseUrl = value(properties, "rd.workflow.recovery.smoke.base-url");
        if (!baseUrl.isBlank() && !isHttpUrl(baseUrl)) {
            missing.add("rd.workflow.recovery.smoke.base-url=http(s)://<host>");
        }
        String postgresUrl = value(properties, "rd.workflow.recovery.smoke.postgres-url");
        if (!postgresUrl.isBlank() && !isPostgresJdbcUrl(postgresUrl)) {
            missing.add("rd.workflow.recovery.smoke.postgres-url=jdbc:postgresql://<host>/<db>");
        }
        addPositiveIntRequirement(properties, missing,
                "rd.workflow.recovery.smoke.stage-run-count-before-restart");
        addPositiveIntRequirement(properties, missing,
                "rd.workflow.recovery.smoke.stage-event-count-before-restart");
        addNonNegativeIntRequirement(properties, missing,
                "rd.workflow.recovery.smoke.retry-attempt-count");
        addNonNegativeIntRequirement(properties, missing,
                "rd.workflow.recovery.smoke.retained-retry-artifact-count");
        addProductionEvidenceUriRequirement(properties, missing,
                "rd.workflow.recovery.smoke.startup-log-evidence-uri");
        addProductionEvidenceUriRequirement(properties, missing,
                "rd.workflow.recovery.smoke.database-snapshot-evidence-uri");
        String secretScanNeedles = value(properties, "rd.workflow.recovery.smoke.secret-scan-needles");
        if (!secretScanNeedles.isBlank() && splitCsv(secretScanNeedles).isEmpty()) {
            missing.add("rd.workflow.recovery.smoke.secret-scan-needles>=1");
        }
    }

    private static void addPositiveIntRequirement(
            Map<String, String> properties,
            List<String> missing,
            String name
    ) {
        String value = value(properties, name);
        if (!value.isBlank() && positiveInt(value) <= 0) {
            missing.add(name + ">0");
        }
    }

    private static void addNonNegativeIntRequirement(
            Map<String, String> properties,
            List<String> missing,
            String name
    ) {
        String value = value(properties, name);
        if (!value.isBlank() && !isNonNegativeInt(value)) {
            missing.add(name + ">=0");
        }
    }

    private static void addProductionEvidenceUriRequirement(
            Map<String, String> properties,
            List<String> missing,
            String name
    ) {
        String value = value(properties, name);
        if (!value.isBlank() && !ProductionEvidenceUris.isProductionArtifactUri(value)) {
            missing.add(name + "=http(s)|s3|rd-artifact");
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

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
        return value;
    }

    private static int requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
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

    private static int positiveInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.strip());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static boolean isNonNegativeInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.strip()) >= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
