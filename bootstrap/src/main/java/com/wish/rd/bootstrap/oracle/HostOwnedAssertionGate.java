package com.wish.rd.bootstrap.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.oracle.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.AssertionOutcome;
import com.wish.rd.engine.oracle.AssertionResult;
import com.wish.rd.engine.oracle.AssertionRunReport;
import com.wish.rd.engine.oracle.AssertionSpecBundle;
import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Optional Host-owned assertion gate for QA results.
 * When {@code hostAssertionBundle} is absent, validation is a no-op (backward compatible).
 * When present, the Host rejects hash mismatch / failed / unsupported assertions.
 */
public final class HostOwnedAssertionGate {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HostAssertionOracle oracle;

    public HostOwnedAssertionGate(HostAssertionOracle oracle) {
        this.oracle = Objects.requireNonNull(oracle, "oracle must not be null");
    }

    /**
     * Validates optional Host assertion bundle embedded in QA result JSON.
     *
     * @param resultJson QA agent result JSON
     * @param context    evaluation context (workspace / baseUrl)
     * @return error messages; empty when skipped or all assertions passed
     */
    public List<String> validate(String resultJson, AssertionEvaluationContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (resultJson == null || resultJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(resultJson);
        } catch (Exception exception) {
            return List.of("hostAssertionBundle: result JSON is not parseable");
        }
        if (root == null || !root.isObject()) {
            return List.of();
        }
        JsonNode bundleNode = root.get("hostAssertionBundle");
        if (bundleNode == null || bundleNode.isNull()) {
            return List.of();
        }
        if (!bundleNode.isObject()) {
            return List.of("hostAssertionBundle must be an object");
        }

        String contentHash = text(bundleNode.path("contentHash"));
        JsonNode specsNode = bundleNode.path("specs");
        if (!specsNode.isArray()) {
            return List.of("hostAssertionBundle.specs must be an array");
        }

        List<AssertionSpec> specs = new ArrayList<>();
        int index = 0;
        for (JsonNode specNode : specsNode) {
            try {
                specs.add(parseSpec(specNode));
            } catch (IllegalArgumentException exception) {
                return List.of("hostAssertionBundle.specs[" + index + "]: " + exception.getMessage());
            }
            index++;
        }

        AssertionSpecBundle bundle;
        try {
            bundle = new AssertionSpecBundle(specs, contentHash);
        } catch (IllegalArgumentException exception) {
            return List.of("hostAssertionBundle integrity failed: " + exception.getMessage());
        }

        AssertionRunReport report = oracle.evaluate(bundle, context);
        if (report.passed()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        for (AssertionResult result : report.results()) {
            if (result.outcome() != AssertionOutcome.PASSED) {
                errors.add(
                        "hostAssertion[" + result.assertionId() + "] "
                                + result.outcome() + ": " + result.message()
                );
            }
        }
        if (errors.isEmpty() && !report.failureSummary().isBlank()) {
            errors.add("hostAssertionBundle failed: " + report.failureSummary());
        }
        return List.copyOf(errors);
    }

    /**
     * Convenience for file-rooted evaluation without a base URL.
     */
    public List<String> validate(String resultJson, Path workspaceRoot) {
        return validate(resultJson, AssertionEvaluationContext.of(workspaceRoot));
    }

    private static AssertionSpec parseSpec(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("spec must be an object");
        }
        String typeName = text(node.path("assertionType"));
        AssertionType type;
        try {
            type = AssertionType.valueOf(typeName);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("unknown assertionType: " + typeName);
        }
        List<String> preconditions = stringList(node.path("preconditions"));
        List<String> evidenceRequired = stringList(node.path("evidenceRequired"));
        long timeout = node.path("timeoutMillis").asLong(1_000L);
        return new AssertionSpec(
                text(node.path("id")),
                text(node.path("sourceCriteriaId")),
                preconditions,
                text(node.path("fixture")),
                text(node.path("action")),
                type,
                text(node.path("target")),
                text(node.path("operator")),
                text(node.path("expected")),
                text(node.path("tolerance")),
                evidenceRequired,
                timeout,
                text(node.path("sensitivity"))
        );
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = text(item);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText("").strip();
        }
        return "";
    }
}
