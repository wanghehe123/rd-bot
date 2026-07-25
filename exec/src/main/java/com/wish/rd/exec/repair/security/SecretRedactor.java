package com.wish.rd.exec.repair.security;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Redacts secrets before values are written to metadata, logs, or audit payloads.
 */
public final class SecretRedactor {

    public static final String REDACTED = "<redacted>";

    private SecretRedactor() {
    }

    /**
     * Redacts a single value when its key looks secret-bearing.
     *
     * @param key   metadata key
     * @param value metadata value
     * @return redacted or original value
     */
    public static String redactValue(String key, String value) {
        String safeValue = value == null ? "" : value;
        return isSecretKey(key) && !safeValue.isBlank() ? REDACTED : redactFreeform(safeValue);
    }

    /**
     * Redacts secret-looking entries in a metadata map.
     *
     * @param source source metadata
     * @return copied metadata with redacted values
     */
    public static Map<String, String> redactMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> redacted = new LinkedHashMap<>();
        source.forEach((key, value) -> redacted.put(key == null ? "" : key, redactValue(key, value)));
        return Map.copyOf(redacted);
    }

    /**
     * Redacts common inline token assignments in free-form text.
     *
     * @param value text value
     * @return text with token-like assignments redacted
     */
    public static String redactFreeform(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceAll("(?i)(authorization\\s*[=:]\\s*bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1" + REDACTED)
                .replaceAll("(?i)(token|api[_-]?key|password|secret|authorization)=([^\\s,;]+)", "$1=" + REDACTED)
                .replaceAll(
                        "(?i)(\\\"(?:token|api[_-]?key|password|secret|authorization)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")",
                        "$1" + REDACTED + "$2"
                )
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1" + REDACTED);
    }

    /**
     * Tests whether a key should never expose its value.
     *
     * @param key metadata or environment key
     * @return true when key is secret-bearing
     */
    public static boolean isSecretKey(String key) {
        String normalized = key == null ? "" : key.toUpperCase(Locale.ROOT);
        return normalized.contains("SECRET")
                || normalized.contains("TOKEN")
                || normalized.contains("PASSWORD")
                || normalized.contains("API_KEY")
                || normalized.contains("APIKEY")
                || normalized.contains("ACCESS_KEY")
                || normalized.contains("ACCESSKEY")
                || normalized.equals("PAT")
                || normalized.endsWith("_PAT")
                || normalized.equals("AUTHORIZATION");
    }
}
