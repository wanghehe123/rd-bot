package com.wish.rd.engine.requirement.audit;

/**
 * One executor-declared scalar claim.
 *
 * @param name claim name such as {@code testStatus}
 * @param value declared value
 */
public record RoleClaim(String name, String value) {
    public RoleClaim {
        name = require(name, "name");
        value = value == null ? "" : value.strip();
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
