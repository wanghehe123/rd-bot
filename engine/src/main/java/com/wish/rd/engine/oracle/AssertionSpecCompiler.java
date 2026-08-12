package com.wish.rd.engine.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import com.wish.rd.engine.oracle.model.AssertionType;
import com.wish.rd.engine.oracle.model.FrozenAssertionBundle;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles explicit Host-authored assertion JSON into canonical frozen bundles.
 * Natural-language acceptance criteria deliberately do not become executable assertions.
 */
public final class AssertionSpecCompiler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MILLIS = 5_000L;

    /**
     * Compiles a Host-authored object with an {@code assertions} array into independent scope bundles.
     *
     * @param source host-controlled acceptance definition JSON
     * @return immutable scope-to-bundle map; empty for legacy natural-language criteria
     */
    public Map<String, AssertionSpecBundle> compileByScope(String source) {
        JsonNode root = parse(source);
        if (root == null || !root.isObject() || !root.has("assertions")) {
            return Map.of();
        }
        return compileExplicitDefinition(root);
    }

    /**
     * Compiles an explicit structured Host assertion request.
     * Unlike the legacy string overload, an object supplied through the dedicated task field is
     * never silently treated as natural-language acceptance criteria: malformed input fails closed.
     *
     * @param definition structured Host assertion envelope from the Host-owned task input
     * @return immutable CURRENT and REGRESSION bundles
     */
    public Map<String, AssertionSpecBundle> compileByScope(JsonNode definition) {
        if (definition == null || definition.isNull()) {
            throw new IllegalArgumentException("Host assertion bundle must not be null");
        }
        if (!definition.isObject()) {
            throw new IllegalArgumentException("Host assertion bundle must be an object");
        }
        return compileExplicitDefinition(definition);
    }

    private Map<String, AssertionSpecBundle> compileExplicitDefinition(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Host assertion bundle must be an object");
        }
        if (!root.has("assertions")) {
            throw new IllegalArgumentException("Host assertion bundle must contain an assertions array");
        }
        JsonNode definitions = root.path("assertions");
        if (!definitions.isArray() || definitions.isEmpty()) {
            throw new IllegalArgumentException("Host assertion definitions must contain a non-empty assertions array");
        }

        Map<String, List<AssertionSpec>> byScope = new LinkedHashMap<>();
        Map<String, Set<String>> idsByScope = new LinkedHashMap<>();
        for (int index = 0; index < definitions.size(); index++) {
            JsonNode definition = definitions.get(index);
            CompiledSpec compiled = compile(definition, index);
            Set<String> ids = idsByScope.computeIfAbsent(compiled.scope(), ignored -> new LinkedHashSet<>());
            if (!ids.add(compiled.spec().id())) {
                throw new IllegalArgumentException(
                        "duplicate Host assertion id for " + compiled.scope() + ": " + compiled.spec().id());
            }
            byScope.computeIfAbsent(compiled.scope(), ignored -> new ArrayList<>()).add(compiled.spec());
        }
        if (!byScope.containsKey("CURRENT") || !byScope.containsKey("REGRESSION")) {
            throw new IllegalArgumentException(
                    "Host assertion definitions must contain both CURRENT and REGRESSION scopes");
        }
        Map<String, AssertionSpecBundle> frozen = new LinkedHashMap<>();
        frozen.put("CURRENT", AssertionSpecBundle.freeze(byScope.get("CURRENT")));
        frozen.put("REGRESSION", AssertionSpecBundle.freeze(byScope.get("REGRESSION")));
        return Map.copyOf(frozen);
    }

    /** Returns whether source contains the explicit Host assertion envelope. */
    public boolean isExplicitHostAssertionDefinition(String source) {
        JsonNode root = parse(source);
        return root != null && root.isObject() && root.has("assertions");
    }

    private static JsonNode parse(String source) {
        String raw = source == null ? "" : source.strip();
        if (raw.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(raw);
        } catch (Exception exception) {
            if (looksLikeHostAssertionDefinition(raw)) {
                throw new IllegalArgumentException("Host assertion definition JSON is invalid", exception);
            }
            return null;
        }
    }

    private static boolean looksLikeHostAssertionDefinition(String raw) {
        String normalized = raw == null ? "" : raw.strip();
        return normalized.startsWith("{")
                || normalized.toLowerCase(Locale.ROOT).contains("assertions");
    }

    private static CompiledSpec compile(JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("assertions[" + index + "] must be an object");
        }
        String scope = FrozenAssertionBundle.normalizeScope(text(node, "scope"));
        AssertionType type = assertionType(text(node, "assertionType"), index);
        String operator = require(text(node, "operator"), "assertions[" + index + "].operator").toLowerCase(Locale.ROOT);
        String target = require(text(node, "target"), "assertions[" + index + "].target");
        String expected = text(node, "expected");
        String action = text(node, "action");
        validate(type, operator, target, expected, action, index);
        return new CompiledSpec(
                scope,
                new AssertionSpec(
                        require(text(node, "id"), "assertions[" + index + "].id"),
                        text(node, "sourceCriteriaId"),
                        strings(node.path("preconditions")),
                        text(node, "fixture"),
                        action,
                        type,
                        target,
                        operator,
                        expected,
                        text(node, "tolerance"),
                        strings(node.path("evidenceRequired")),
                        positiveTimeout(node.path("timeoutMillis"), index),
                        text(node, "sensitivity")
                )
        );
    }

    private static void validate(
            AssertionType type,
            String operator,
            String target,
            String expected,
            String action,
            int index
    ) {
        Set<String> allowed = switch (type) {
            case FILE_EXISTS -> Set.of("exists");
            case FILE_FORBIDDEN -> Set.of("not_exists", "absent");
            case FILE_HASH, FILE_TEXT -> Set.of("eq", "equals");
            case HTTP_STATUS, HTTP_HEADER, HTTP_SCHEMA -> Set.of("eq", "equals");
            case HTTP_JSONPATH -> Set.of("eq", "equals");
            case HTTP_POLL -> Set.of("eq", "equals");
            case SQL_ROW_EXISTS -> Set.of("exists");
            case SQL_FIELD_VALUE -> Set.of("eq", "equals");
            case SQL_ROW_COUNT -> Set.of("eq", "equals", "gt", "gte", "lt", "lte");
            case SQL_INVARIANT -> Set.of("true", "eq", "equals");
            case LOG_MUST_MATCH -> Set.of("matches");
            case LOG_MUST_NOT_MATCH -> Set.of("not_matches");
            case LOG_SECRET_SCAN -> Set.of("clean");
            case BROWSER_DOM -> Set.of("exists", "not_exists");
            case BROWSER_ARIA -> Set.of("eq", "equals");
            case BROWSER_VISIBLE -> Set.of("visible", "hidden");
            case BROWSER_ROUTE -> Set.of("eq", "equals");
        };
        if (!allowed.contains(operator)) {
            throw new IllegalArgumentException(
                    "assertions[" + index + "] has unsupported operator '" + operator + "' for " + type);
        }
        if (EnumSet.of(
                AssertionType.HTTP_STATUS,
                AssertionType.HTTP_JSONPATH,
                AssertionType.HTTP_HEADER,
                AssertionType.HTTP_SCHEMA,
                AssertionType.HTTP_POLL,
                AssertionType.SQL_FIELD_VALUE,
                AssertionType.SQL_ROW_COUNT,
                AssertionType.SQL_INVARIANT,
                AssertionType.FILE_HASH,
                AssertionType.FILE_TEXT,
                AssertionType.BROWSER_ARIA,
                AssertionType.BROWSER_ROUTE
        ).contains(type) && expected.isBlank()) {
            throw new IllegalArgumentException("assertions[" + index + "] expected must not be blank for " + type);
        }
        if (type == AssertionType.HTTP_JSONPATH && !action.strip().toUpperCase(Locale.ROOT).startsWith("GET ")) {
            throw new IllegalArgumentException("assertions[" + index + "] HTTP_JSONPATH action must be a GET request");
        }
        if (type.name().startsWith("SQL_")) {
            ReadOnlySqlPolicy.validate(target, ReadOnlySqlPolicy.defaultAllowedSchemas());
        }
        if (type.name().startsWith("LOG_") && (type != AssertionType.LOG_SECRET_SCAN && expected.isBlank())) {
            throw new IllegalArgumentException("assertions[" + index + "] expected pattern must not be blank for " + type);
        }
    }

    private static AssertionType assertionType(String raw, int index) {
        try {
            return AssertionType.valueOf(require(raw, "assertions[" + index + "].assertionType")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("assertions[" + index + "] has unknown assertionType: " + raw);
        }
    }

    private static long positiveTimeout(JsonNode node, int index) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        long timeout = node.asLong(-1L);
        if (timeout <= 0L) {
            throw new IllegalArgumentException("assertions[" + index + "].timeoutMillis must be positive");
        }
        return timeout;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("assertion list fields must be arrays");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("assertion list fields must contain strings");
            }
            String normalized = value.asText("").strip();
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() || value.isNumber() || value.isBoolean() ? value.asText("").strip() : "";
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private record CompiledSpec(String scope, AssertionSpec spec) {
    }
}
