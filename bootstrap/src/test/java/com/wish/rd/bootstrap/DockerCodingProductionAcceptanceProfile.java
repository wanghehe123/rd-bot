package com.wish.rd.bootstrap;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Docker coding production smoke properties.
 */
record DockerCodingProductionAcceptanceProfile(
        String rdBotVersion,
        String environmentId,
        String executedBy,
        String baseUrl,
        String postgresUrl,
        String postgresUser,
        String postgresPassword,
        String repositoryUrl,
        String taskId,
        String patchArtifactUri,
        String resultArtifactUri,
        String testLogArtifactUri,
        String dockerMetadataArtifactUri,
        List<String> secretScanNeedles
) {

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "rd.docker-coding.smoke.production-evidence",
            "rd.docker-coding.smoke.rd-bot-version",
            "rd.docker-coding.smoke.environment-id",
            "rd.docker-coding.smoke.executed-by",
            "rd.docker-coding.smoke.base-url",
            "rd.docker-coding.smoke.postgres-url",
            "rd.docker-coding.smoke.postgres-user",
            "rd.docker-coding.smoke.postgres-password",
            "rd.docker-coding.smoke.repository-url",
            "rd.docker-coding.smoke.task-id",
            "rd.docker-coding.smoke.patch-artifact-uri",
            "rd.docker-coding.smoke.result-artifact-uri",
            "rd.docker-coding.smoke.test-log-artifact-uri",
            "rd.docker-coding.smoke.docker-metadata-artifact-uri",
            "rd.docker-coding.smoke.secret-scan-needles"
    );

    DockerCodingProductionAcceptanceProfile {
        rdBotVersion = requireText(rdBotVersion, "rdBotVersion");
        environmentId = requireText(environmentId, "environmentId");
        executedBy = requireText(executedBy, "executedBy");
        baseUrl = requireHttpUrl(baseUrl, "baseUrl");
        postgresUrl = requirePostgresJdbcUrl(postgresUrl);
        postgresUser = requireText(postgresUser, "postgresUser");
        postgresPassword = requireText(postgresPassword, "postgresPassword");
        repositoryUrl = requireHttpUrl(repositoryUrl, "repositoryUrl");
        taskId = requireText(taskId, "taskId");
        patchArtifactUri = requireProductionArtifactUri(patchArtifactUri, "patchArtifactUri");
        resultArtifactUri = requireProductionArtifactUri(resultArtifactUri, "resultArtifactUri");
        testLogArtifactUri = requireProductionArtifactUri(testLogArtifactUri, "testLogArtifactUri");
        dockerMetadataArtifactUri = requireProductionArtifactUri(dockerMetadataArtifactUri, "dockerMetadataArtifactUri");
        List<String> artifactUris = List.of(
                patchArtifactUri,
                resultArtifactUri,
                testLogArtifactUri,
                dockerMetadataArtifactUri
        );
        if (artifactUris.stream().distinct().count() != artifactUris.size()) {
            throw new IllegalArgumentException("Docker coding artifact URIs must be distinct");
        }
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

    static DockerCodingProductionAcceptanceProfile from(Map<String, String> properties) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        List<String> missing = missingRequiredProperties(safeProperties);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing Docker coding production acceptance properties: " + missing);
        }
        return new DockerCodingProductionAcceptanceProfile(
                value(safeProperties, "rd.docker-coding.smoke.rd-bot-version"),
                value(safeProperties, "rd.docker-coding.smoke.environment-id"),
                value(safeProperties, "rd.docker-coding.smoke.executed-by"),
                value(safeProperties, "rd.docker-coding.smoke.base-url"),
                value(safeProperties, "rd.docker-coding.smoke.postgres-url"),
                value(safeProperties, "rd.docker-coding.smoke.postgres-user"),
                value(safeProperties, "rd.docker-coding.smoke.postgres-password"),
                value(safeProperties, "rd.docker-coding.smoke.repository-url"),
                value(safeProperties, "rd.docker-coding.smoke.task-id"),
                value(safeProperties, "rd.docker-coding.smoke.patch-artifact-uri"),
                value(safeProperties, "rd.docker-coding.smoke.result-artifact-uri"),
                value(safeProperties, "rd.docker-coding.smoke.test-log-artifact-uri"),
                value(safeProperties, "rd.docker-coding.smoke.docker-metadata-artifact-uri"),
                splitCsv(value(safeProperties, "rd.docker-coding.smoke.secret-scan-needles"))
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
        String productionEvidence = value(safeProperties, "rd.docker-coding.smoke.production-evidence");
        if (!productionEvidence.isBlank() && !"true".equalsIgnoreCase(productionEvidence)) {
            missing.remove("rd.docker-coding.smoke.production-evidence");
            missing.add("rd.docker-coding.smoke.production-evidence=true");
        }
        validateUrlProperty(safeProperties, missing, "rd.docker-coding.smoke.base-url", "http(s)://<host>");
        validatePostgresProperty(safeProperties, missing);
        validateUrlProperty(safeProperties, missing, "rd.docker-coding.smoke.repository-url", "http(s)://<host>");
        validateArtifactUri(safeProperties, missing, "rd.docker-coding.smoke.patch-artifact-uri");
        validateArtifactUri(safeProperties, missing, "rd.docker-coding.smoke.result-artifact-uri");
        validateArtifactUri(safeProperties, missing, "rd.docker-coding.smoke.test-log-artifact-uri");
        validateArtifactUri(safeProperties, missing, "rd.docker-coding.smoke.docker-metadata-artifact-uri");
        List<String> artifactUris = List.of(
                value(safeProperties, "rd.docker-coding.smoke.patch-artifact-uri"),
                value(safeProperties, "rd.docker-coding.smoke.result-artifact-uri"),
                value(safeProperties, "rd.docker-coding.smoke.test-log-artifact-uri"),
                value(safeProperties, "rd.docker-coding.smoke.docker-metadata-artifact-uri")
        ).stream().filter(value -> !value.isBlank()).toList();
        if (artifactUris.size() > 1 && artifactUris.stream().distinct().count() != artifactUris.size()) {
            missing.add("rd.docker-coding.smoke.artifact-uris=distinct");
        }
        String secretScanNeedles = value(safeProperties, "rd.docker-coding.smoke.secret-scan-needles");
        if (!secretScanNeedles.isBlank() && splitCsv(secretScanNeedles).isEmpty()) {
            missing.add("rd.docker-coding.smoke.secret-scan-needles>=1");
        }
        return List.copyOf(missing);
    }

    String describeMissingRequirements() {
        return """
                Required real Docker coding smoke properties:
                - rd.docker-coding.smoke.production-evidence=true
                - rd.docker-coding.smoke.rd-bot-version
                - rd.docker-coding.smoke.environment-id
                - rd.docker-coding.smoke.executed-by
                - rd.docker-coding.smoke.base-url
                - rd.docker-coding.smoke.postgres-url
                - rd.docker-coding.smoke.postgres-user
                - rd.docker-coding.smoke.postgres-password
                - rd.docker-coding.smoke.repository-url
                - rd.docker-coding.smoke.task-id
                - rd.docker-coding.smoke.patch-artifact-uri
                - rd.docker-coding.smoke.result-artifact-uri
                - rd.docker-coding.smoke.test-log-artifact-uri
                - rd.docker-coding.smoke.docker-metadata-artifact-uri
                - rd.docker-coding.smoke.secret-scan-needles
                """.strip();
    }

    private static void validateUrlProperty(
            Map<String, String> properties,
            List<String> missing,
            String name,
            String expectation
    ) {
        String value = value(properties, name);
        if (!value.isBlank() && !isHttpUrl(value)) {
            missing.add(name + "=" + expectation);
        }
    }

    private static void validatePostgresProperty(Map<String, String> properties, List<String> missing) {
        String value = value(properties, "rd.docker-coding.smoke.postgres-url");
        if (!value.isBlank() && !isPostgresJdbcUrl(value)) {
            missing.add("rd.docker-coding.smoke.postgres-url=jdbc:postgresql://<host>/<db>");
        }
    }

    private static void validateArtifactUri(Map<String, String> properties, List<String> missing, String name) {
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
