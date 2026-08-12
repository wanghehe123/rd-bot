package com.wish.rd.engine.oracle.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable Host-owned assertion contract for one QA scope and stage attempt.
 *
 * @param taskId task that owns the assertions
 * @param stageRunId immutable QA stage run identity
 * @param scope validation scope, {@code CURRENT} or {@code REGRESSION}
 * @param bundle canonical frozen assertion bundle
 * @param version Host compiler schema version
 */
public record FrozenAssertionBundle(
        String taskId,
        String stageRunId,
        String scope,
        AssertionSpecBundle bundle,
        long version
) {

    /** Creates a validated immutable bundle identity. */
    public FrozenAssertionBundle {
        taskId = requireText(taskId, "taskId");
        stageRunId = requireText(stageRunId, "stageRunId");
        scope = normalizeScope(scope);
        bundle = Objects.requireNonNull(bundle, "bundle must not be null");
        if (bundle.specs().isEmpty()) {
            throw new IllegalArgumentException("bundle must contain at least one assertion");
        }
        if (version <= 0L) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    /** Returns the canonical content hash of the Host-owned bundle. */
    public String contentHash() {
        return bundle.contentHash();
    }

    /** Normalizes and validates the two independently replayed QA scopes. */
    public static String normalizeScope(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!"CURRENT".equals(normalized) && !"REGRESSION".equals(normalized)) {
            throw new IllegalArgumentException("scope must be CURRENT or REGRESSION");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
