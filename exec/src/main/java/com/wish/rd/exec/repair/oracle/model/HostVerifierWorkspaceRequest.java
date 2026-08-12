package com.wish.rd.exec.repair.oracle.model;

import java.util.Locale;

/**
 * Neutral identity for one Host-controlled assertion replay scope.
 * This keeps the Exec boundary independent from Engine assertion-spec types.
 *
 * @param taskId workflow task identity
 * @param stageRunId immutable QA stage identity
 * @param scope assertion replay scope, {@code CURRENT} or {@code REGRESSION}
 * @param runtimeRequired whether the frozen Host assertion bundle needs a local HTTP/browser runtime
 */
public record HostVerifierWorkspaceRequest(
        String taskId,
        String stageRunId,
        String scope,
        boolean runtimeRequired
) {

    /**
     * Compatibility constructor for filesystem, SQL, and log-only assertions.
     *
     * @param taskId workflow task identity
     * @param stageRunId immutable QA stage identity
     * @param scope assertion replay scope
     */
    public HostVerifierWorkspaceRequest(String taskId, String stageRunId, String scope) {
        this(taskId, stageRunId, scope, false);
    }

    /** Normalizes and validates the Host-owned scope identity. */
    public HostVerifierWorkspaceRequest {
        taskId = requireText(taskId, "taskId");
        stageRunId = requireText(stageRunId, "stageRunId");
        scope = normalizeScope(scope);
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeScope(String value) {
        String normalized = requireText(value, "scope").toUpperCase(Locale.ROOT);
        if (!"CURRENT".equals(normalized) && !"REGRESSION".equals(normalized)) {
            throw new IllegalArgumentException("scope must be CURRENT or REGRESSION");
        }
        return normalized;
    }
}
