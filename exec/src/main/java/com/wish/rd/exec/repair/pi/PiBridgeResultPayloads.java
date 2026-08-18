package com.wish.rd.exec.repair.pi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;

/**
 * Detects synthetic Pi-bridge failure payloads so host role validators
 * do not misreport missing architect/reviewer fields.
 */
public final class PiBridgeResultPayloads {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> SYNTHETIC_CATEGORIES = Set.of(
            "PI_BRIDGE_PROTOCOL",
            "PI_RESULT_RECOVERY",
            "BUDGET_EXCEEDED"
    );

    private PiBridgeResultPayloads() {
    }

    public static boolean isSyntheticFailure(String rawJson) {
        return !failureMessage(rawJson).isBlank() || syntheticCategory(rawJson);
    }

    public static String failureMessage(String rawJson) {
        JsonNode root = parse(rawJson);
        if (root == null || !syntheticCategory(root)) {
            return "";
        }
        String error = text(root, "errorMessage");
        if (!error.isBlank()) {
            return error;
        }
        return text(root, "summary");
    }

    private static boolean syntheticCategory(String rawJson) {
        return syntheticCategory(parse(rawJson));
    }

    private static boolean syntheticCategory(JsonNode root) {
        if (root == null) {
            return false;
        }
        String category = text(root, "failureCategory").toUpperCase(Locale.ROOT);
        return SYNTHETIC_CATEGORIES.contains(category);
    }

    private static JsonNode parse(String rawJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawJson == null ? "" : rawJson);
            return root != null && root.isObject() ? root : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String text(JsonNode root, String field) {
        return root.path(field).asText("").strip();
    }
}
