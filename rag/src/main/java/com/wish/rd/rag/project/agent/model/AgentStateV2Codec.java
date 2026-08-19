package com.wish.rd.rag.project.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** RFC 8785-compatible codec for the integer-only {@code rd-agent-state/v2} contract. */
public final class AgentStateV2Codec {

    public static final String PROTOCOL = "rd-agent-state/v2";
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Set<String> BUDGET_AVAILABILITY = Set.of(
            "UNKNOWN", "AVAILABLE", "UNAVAILABLE"
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AgentStateV2Codec() {
    }

    public static String canonicalize(JsonNode value) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(requireValue(value), canonical, "$" );
        return canonical.toString();
    }

    public static String hash(JsonNode value) {
        return sha256(canonicalize(value));
    }

    public static JsonNode decodeAndVerify(String json, String expectedHash) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("state JSON must not be blank");
        }
        JsonNode value;
        try {
            value = OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid state JSON", exception);
        }
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("state JSON must be an object");
        }
        validateStateContract(value);
        String canonical = canonicalize(value);
        String actualHash = sha256(canonical);
        if (!actualHash.equals(expectedHash == null ? "" : expectedHash.toLowerCase())) {
            throw new IllegalArgumentException("state hash mismatch");
        }
        if (!canonical.equals(json)) {
            throw new IllegalArgumentException("state JSON must use canonical bytes");
        }
        return value;
    }

    private static void validateStateContract(JsonNode value) {
        if (!PROTOCOL.equals(value.path("protocol").asText())) {
            throw new IllegalArgumentException("state protocol must be " + PROTOCOL);
        }
        JsonNode budget = value.get("budget");
        if (budget == null || budget.isNull()) {
            return;
        }
        if (!budget.isObject()) {
            throw new IllegalArgumentException("state budget must be an object");
        }
        String availability = budget.path("availability").asText("");
        if (!BUDGET_AVAILABILITY.contains(availability)) {
            throw new IllegalArgumentException("unsupported budget availability: " + availability);
        }
    }

    private static JsonNode requireValue(JsonNode value) {
        if (value == null || value.isMissingNode()) {
            throw new IllegalArgumentException("canonical value must not be missing");
        }
        return value;
    }

    private static void appendCanonical(JsonNode node, StringBuilder target, String path) {
        if (node.isObject()) {
            target.append('{');
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                validateUnicode(entry.getKey(), path + ".<key>");
                fields.put(entry.getKey(), entry.getValue());
            }
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : fields.entrySet()) {
                if (!first) {
                    target.append(',');
                }
                appendString(entry.getKey(), target);
                target.append(':');
                appendCanonical(entry.getValue(), target, path + "." + entry.getKey());
                first = false;
            }
            target.append('}');
            return;
        }
        if (node.isArray()) {
            target.append('[');
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) {
                    target.append(',');
                }
                appendCanonical(node.get(index), target, path + "[" + index + "]");
            }
            target.append(']');
            return;
        }
        if (node.isTextual()) {
            validateUnicode(node.textValue(), path);
            appendString(node.textValue(), target);
            return;
        }
        if (node.isIntegralNumber()) {
            if (!node.canConvertToLong()) {
                throw new IllegalArgumentException(path + " integer exceeds JSON safe integer range");
            }
            long value = node.longValue();
            if (value < -MAX_SAFE_INTEGER || value > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException(path + " integer exceeds JSON safe integer range");
            }
            target.append(value);
            return;
        }
        if (node.isFloatingPointNumber()) {
            throw new IllegalArgumentException(path + " must use integer JSON numbers");
        }
        if (node.isBoolean()) {
            target.append(node.booleanValue());
            return;
        }
        if (node.isNull()) {
            target.append("null");
            return;
        }
        throw new IllegalArgumentException(path + " contains an unsupported JSON value");
    }

    private static void appendString(String value, StringBuilder target) {
        try {
            target.append(OBJECT_MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("state string is not serializable", exception);
        }
    }

    private static void validateUnicode(String value, String path) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(path + " contains an illegal surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(path + " contains an illegal surrogate");
            }
        }
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
