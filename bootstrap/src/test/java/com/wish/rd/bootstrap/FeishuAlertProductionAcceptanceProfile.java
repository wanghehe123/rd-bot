package com.wish.rd.bootstrap;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feishu alert production smoke properties.
 */
record FeishuAlertProductionAcceptanceProfile(
        String baseUrl,
        String rdBotVersion,
        String environmentId,
        String executedBy,
        String appId,
        String appSecret,
        String chatId,
        String taskId,
        List<String> secretScanNeedles
) {

    private static final String REQUIRED_BASE_URL = "https://open.feishu.cn";

    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "rd.feishu.alert.smoke.production-evidence",
            "rd.feishu.alert.smoke.rd-bot-version",
            "rd.feishu.alert.smoke.environment-id",
            "rd.feishu.alert.smoke.executed-by",
            "rd.feishu.alert.smoke.app-id",
            "rd.feishu.alert.smoke.app-secret",
            "rd.feishu.alert.smoke.chat-id",
            "rd.feishu.alert.smoke.secret-scan-needles"
    );

    FeishuAlertProductionAcceptanceProfile {
        baseUrl = requireFeishuBaseUrl(defaultWhenBlank(baseUrl, REQUIRED_BASE_URL));
        rdBotVersion = requireText(rdBotVersion, "rdBotVersion");
        environmentId = requireText(environmentId, "environmentId");
        executedBy = requireText(executedBy, "executedBy");
        appId = requireText(appId, "appId");
        appSecret = requireText(appSecret, "appSecret");
        chatId = requireText(chatId, "chatId");
        taskId = taskId == null ? "" : taskId.strip();
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
        properties.put("rd.feishu.alert.smoke.base-url", System.getProperty(
                "rd.feishu.alert.smoke.base-url", "https://open.feishu.cn"));
        properties.put("rd.feishu.alert.smoke.task-id", System.getProperty(
                "rd.feishu.alert.smoke.task-id", ""));
        properties.put("rd.feishu.alert.smoke.secret-scan-needles", System.getProperty(
                "rd.feishu.alert.smoke.secret-scan-needles", ""));
        return properties;
    }

    static FeishuAlertProductionAcceptanceProfile from(Map<String, String> properties) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        List<String> missing = missingRequiredProperties(safeProperties);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing Feishu alert production acceptance properties: " + missing);
        }
        return new FeishuAlertProductionAcceptanceProfile(
                value(safeProperties, "rd.feishu.alert.smoke.base-url"),
                value(safeProperties, "rd.feishu.alert.smoke.rd-bot-version"),
                value(safeProperties, "rd.feishu.alert.smoke.environment-id"),
                value(safeProperties, "rd.feishu.alert.smoke.executed-by"),
                value(safeProperties, "rd.feishu.alert.smoke.app-id"),
                value(safeProperties, "rd.feishu.alert.smoke.app-secret"),
                value(safeProperties, "rd.feishu.alert.smoke.chat-id"),
                value(safeProperties, "rd.feishu.alert.smoke.task-id"),
                splitCsv(value(safeProperties, "rd.feishu.alert.smoke.secret-scan-needles"))
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
        String productionEvidence = value(safeProperties, "rd.feishu.alert.smoke.production-evidence");
        if (!productionEvidence.isBlank() && !"true".equalsIgnoreCase(productionEvidence)) {
            missing.remove("rd.feishu.alert.smoke.production-evidence");
            missing.add("rd.feishu.alert.smoke.production-evidence=true");
        }
        String baseUrl = value(safeProperties, "rd.feishu.alert.smoke.base-url");
        if (!baseUrl.isBlank() && !isFeishuBaseUrl(baseUrl)) {
            missing.add("rd.feishu.alert.smoke.base-url=" + REQUIRED_BASE_URL);
        }
        String secretScanNeedles = value(safeProperties, "rd.feishu.alert.smoke.secret-scan-needles");
        if (!secretScanNeedles.isBlank() && splitCsv(secretScanNeedles).isEmpty()) {
            missing.add("rd.feishu.alert.smoke.secret-scan-needles>=1");
        }
        return List.copyOf(missing);
    }

    String describeMissingRequirements() {
        return """
                Required real Feishu alert smoke properties:
                - rd.feishu.alert.smoke.production-evidence=true
                - rd.feishu.alert.smoke.rd-bot-version
                - rd.feishu.alert.smoke.environment-id
                - rd.feishu.alert.smoke.executed-by
                - rd.feishu.alert.smoke.app-id
                - rd.feishu.alert.smoke.app-secret
                - rd.feishu.alert.smoke.chat-id
                - rd.feishu.alert.smoke.secret-scan-needles
                Optional:
                - rd.feishu.alert.smoke.base-url
                - rd.feishu.alert.smoke.task-id
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

    private static String requireFeishuBaseUrl(String value) {
        String normalized = requireText(value, "baseUrl");
        if (!isFeishuBaseUrl(normalized)) {
            throw new IllegalArgumentException("baseUrl must be " + REQUIRED_BASE_URL);
        }
        return REQUIRED_BASE_URL;
    }

    private static boolean isFeishuBaseUrl(String value) {
        try {
            java.net.URI uri = new java.net.URI(value == null ? "" : value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().strip();
            String host = uri.getHost() == null ? "" : uri.getHost().strip();
            String path = uri.getPath() == null ? "" : uri.getPath().strip();
            return "https".equalsIgnoreCase(scheme)
                    && "open.feishu.cn".equalsIgnoreCase(host)
                    && (path.isBlank() || "/".equals(path));
        } catch (Exception exception) {
            return false;
        }
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

    private static String defaultWhenBlank(String value, String defaultValue) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? defaultValue : normalized;
    }
}
