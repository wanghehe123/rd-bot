package com.wish.rd.engine.requirement.audit;

/**
 * One executor {@code facts[]} entry.
 *
 * @param statement fact text
 * @param kind {@code DECLARED} or {@code INFERRED}
 */
public record RoleFact(String statement, String kind) {
    public RoleFact {
        statement = require(statement, "statement");
        kind = kind == null || kind.isBlank() ? "DECLARED" : kind.strip();
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
