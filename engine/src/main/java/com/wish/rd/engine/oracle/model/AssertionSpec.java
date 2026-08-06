package com.wish.rd.engine.oracle.model;

import java.util.List;

/**
 * Immutable Host-owned assertion compiled from acceptance criteria.
 * Agents may propose results; they must not mutate this contract after freeze.
 *
 * @param id               stable assertion identity
 * @param sourceCriteriaId originating acceptance criterion id
 * @param preconditions    optional precondition expressions
 * @param fixture          fixture name or locator
 * @param action           action to perform before asserting
 * @param assertionType    assertion kind
 * @param target           assertion target (URL, SQL, path, selector, …)
 * @param operator         comparison operator
 * @param expected         expected value
 * @param tolerance        numeric/time tolerance (blank when unused)
 * @param evidenceRequired required evidence artifact prefixes/paths
 * @param timeoutMillis    per-assertion timeout
 * @param sensitivity      sensitivity label (e.g. PII, secret)
 */
public record AssertionSpec(
        String id,
        String sourceCriteriaId,
        List<String> preconditions,
        String fixture,
        String action,
        AssertionType assertionType,
        String target,
        String operator,
        String expected,
        String tolerance,
        List<String> evidenceRequired,
        long timeoutMillis,
        String sensitivity
) {

    public AssertionSpec {
        id = require(id, "id");
        sourceCriteriaId = safe(sourceCriteriaId);
        preconditions = copyList(preconditions);
        fixture = safe(fixture);
        action = safe(action);
        if (assertionType == null) {
            throw new IllegalArgumentException("assertionType must not be null");
        }
        target = require(target, "target");
        operator = require(operator, "operator");
        expected = safe(expected);
        tolerance = safe(tolerance);
        evidenceRequired = copyList(evidenceRequired);
        timeoutMillis = Math.max(0L, timeoutMillis);
        sensitivity = safe(sensitivity);
    }

    private static String require(String value, String field) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> copyList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(AssertionSpec::safe)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
