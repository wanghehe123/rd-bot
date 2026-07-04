package com.wish.rd.bootstrap;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill policy production smoke properties.
 */
record SkillProductionAcceptanceProfile(
        String rdBotVersion,
        String environmentId,
        String executedBy,
        String skillId,
        String skillVersion,
        String skillSourceUri,
        String skillChecksum,
        String installRoot,
        String allowedRole,
        String rejectedRole,
        String highRiskRole,
        String highRiskSkillId,
        String taskId,
        List<String> secretScanNeedles
) {

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "rd.skill.smoke.production-evidence",
            "rd.skill.smoke.rd-bot-version",
            "rd.skill.smoke.environment-id",
            "rd.skill.smoke.executed-by",
            "rd.skill.smoke.skill-id",
            "rd.skill.smoke.skill-version",
            "rd.skill.smoke.skill-source-uri",
            "rd.skill.smoke.skill-checksum",
            "rd.skill.smoke.install-root",
            "rd.skill.smoke.allowed-role",
            "rd.skill.smoke.rejected-role",
            "rd.skill.smoke.high-risk-role"
    );

    SkillProductionAcceptanceProfile {
        rdBotVersion = requireText(rdBotVersion, "rdBotVersion");
        environmentId = requireText(environmentId, "environmentId");
        executedBy = requireText(executedBy, "executedBy");
        skillId = requireText(skillId, "skillId");
        skillVersion = requireText(skillVersion, "skillVersion");
        skillSourceUri = requireAbsoluteFileUri(skillSourceUri);
        skillChecksum = requireFullSha256(skillChecksum);
        installRoot = requireAbsolutePath(installRoot, "installRoot");
        allowedRole = requireText(allowedRole, "allowedRole").toUpperCase();
        rejectedRole = requireText(rejectedRole, "rejectedRole").toUpperCase();
        highRiskRole = requireText(highRiskRole, "highRiskRole").toUpperCase();
        if (!distinctRoles(allowedRole, rejectedRole, highRiskRole)) {
            throw new IllegalArgumentException("skill role boundaries must be distinct");
        }
        highRiskSkillId = defaultWhenBlank(highRiskSkillId, skillId + "-high-risk");
        taskId = taskId == null ? "" : taskId.strip();
        secretScanNeedles = secretScanNeedles == null ? List.of() : List.copyOf(secretScanNeedles);
    }

    static List<String> requiredPropertyNames() {
        return REQUIRED_PROPERTIES;
    }

    static Map<String, String> systemProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        REQUIRED_PROPERTIES.forEach(name -> properties.put(name, System.getProperty(name, "")));
        properties.put("rd.skill.smoke.high-risk-skill-id", System.getProperty(
                "rd.skill.smoke.high-risk-skill-id", ""));
        properties.put("rd.skill.smoke.task-id", System.getProperty(
                "rd.skill.smoke.task-id", ""));
        properties.put("rd.skill.smoke.secret-scan-needles", System.getProperty(
                "rd.skill.smoke.secret-scan-needles", ""));
        return properties;
    }

    static SkillProductionAcceptanceProfile from(Map<String, String> properties) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        List<String> missing = missingRequiredProperties(safeProperties);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing Skill production acceptance properties: " + missing);
        }
        return new SkillProductionAcceptanceProfile(
                value(safeProperties, "rd.skill.smoke.rd-bot-version"),
                value(safeProperties, "rd.skill.smoke.environment-id"),
                value(safeProperties, "rd.skill.smoke.executed-by"),
                value(safeProperties, "rd.skill.smoke.skill-id"),
                value(safeProperties, "rd.skill.smoke.skill-version"),
                value(safeProperties, "rd.skill.smoke.skill-source-uri"),
                value(safeProperties, "rd.skill.smoke.skill-checksum"),
                value(safeProperties, "rd.skill.smoke.install-root"),
                value(safeProperties, "rd.skill.smoke.allowed-role"),
                value(safeProperties, "rd.skill.smoke.rejected-role"),
                value(safeProperties, "rd.skill.smoke.high-risk-role"),
                value(safeProperties, "rd.skill.smoke.high-risk-skill-id"),
                value(safeProperties, "rd.skill.smoke.task-id"),
                splitCsv(value(safeProperties, "rd.skill.smoke.secret-scan-needles"))
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
        String productionEvidence = value(safeProperties, "rd.skill.smoke.production-evidence");
        if (!productionEvidence.isBlank() && !"true".equalsIgnoreCase(productionEvidence)) {
            missing.remove("rd.skill.smoke.production-evidence");
            missing.add("rd.skill.smoke.production-evidence=true");
        }
        String skillSourceUri = value(safeProperties, "rd.skill.smoke.skill-source-uri");
        if (!skillSourceUri.isBlank() && !isAbsoluteFileUri(skillSourceUri)) {
            missing.add("rd.skill.smoke.skill-source-uri=file://<absolute-path>");
        }
        String skillChecksum = value(safeProperties, "rd.skill.smoke.skill-checksum");
        if (!skillChecksum.isBlank() && !isFullSha256(skillChecksum)) {
            missing.add("rd.skill.smoke.skill-checksum=sha256:<64-hex>");
        }
        String installRoot = value(safeProperties, "rd.skill.smoke.install-root");
        if (!installRoot.isBlank() && !isAbsolutePath(installRoot)) {
            missing.add("rd.skill.smoke.install-root=<absolute-path>");
        }
        return List.copyOf(missing);
    }

    String describeMissingRequirements() {
        return """
                Required real Skill policy smoke properties:
                - rd.skill.smoke.production-evidence=true
                - rd.skill.smoke.rd-bot-version
                - rd.skill.smoke.environment-id
                - rd.skill.smoke.executed-by
                - rd.skill.smoke.skill-id
                - rd.skill.smoke.skill-version
                - rd.skill.smoke.skill-source-uri
                - rd.skill.smoke.skill-checksum
                - rd.skill.smoke.install-root
                - rd.skill.smoke.allowed-role
                - rd.skill.smoke.rejected-role
                - rd.skill.smoke.high-risk-role
                Optional:
                - rd.skill.smoke.high-risk-skill-id
                - rd.skill.smoke.task-id
                - rd.skill.smoke.secret-scan-needles
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

    private static String requireAbsoluteFileUri(String value) {
        String normalized = requireText(value, "skillSourceUri");
        if (!isAbsoluteFileUri(normalized)) {
            throw new IllegalArgumentException("skillSourceUri must be file://<absolute-path>");
        }
        return normalized;
    }

    private static String requireFullSha256(String value) {
        String normalized = requireText(value, "skillChecksum");
        if (!isFullSha256(normalized)) {
            throw new IllegalArgumentException("skillChecksum must be sha256:<64-hex>");
        }
        return normalized;
    }

    private static String requireAbsolutePath(String value, String fieldName) {
        String normalized = requireText(value, fieldName);
        if (!isAbsolutePath(normalized)) {
            throw new IllegalArgumentException(fieldName + " must be an absolute path");
        }
        return normalized;
    }

    private static String defaultWhenBlank(String value, String defaultValue) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? defaultValue : normalized;
    }

    private static boolean distinctRoles(String allowedRole, String rejectedRole, String highRiskRole) {
        return !allowedRole.equals(rejectedRole)
                && !allowedRole.equals(highRiskRole)
                && !rejectedRole.equals(highRiskRole);
    }

    private static boolean isAbsoluteFileUri(String value) {
        try {
            java.net.URI uri = new java.net.URI(value == null ? "" : value.strip());
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            return java.nio.file.Path.of(uri).isAbsolute();
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isFullSha256(String value) {
        return value != null && value.strip().matches("sha256:[0-9a-fA-F]{64}");
    }

    private static boolean isAbsolutePath(String value) {
        try {
            return java.nio.file.Path.of(value == null ? "" : value.strip()).isAbsolute();
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
