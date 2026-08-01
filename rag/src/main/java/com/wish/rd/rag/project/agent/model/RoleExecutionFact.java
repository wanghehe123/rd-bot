package com.wish.rd.rag.project.agent.model;

import java.util.Objects;

/** Runtime-neutral role execution fact. */
public record RoleExecutionFact(
        String factId,
        FactKind kind,
        String statement,
        String sourceArtifactId,
        String sourceStageRunId,
        String sourceToolCallId,
        String repoRevision,
        String workspaceFingerprint,
        String observedAt,
        String commandHash,
        FactFreshnessPolicy freshnessPolicy,
        String expiresAt,
        Double confidence,
        FactFreshnessStatus freshnessStatus
) {

    public static final int MAX_STATEMENT_LENGTH = 512;

    public RoleExecutionFact {
        factId = requireText(factId, "factId");
        Objects.requireNonNull(kind, "kind must not be null");
        statement = requireBoundedStatement(statement);
        sourceArtifactId = normalizeOptional(sourceArtifactId);
        sourceStageRunId = normalizeOptional(sourceStageRunId);
        sourceToolCallId = normalizeOptional(sourceToolCallId);
        repoRevision = normalizeOptional(repoRevision);
        workspaceFingerprint = normalizeOptional(workspaceFingerprint);
        observedAt = normalizeOptional(observedAt);
        commandHash = normalizeOptional(commandHash);
        Objects.requireNonNull(freshnessPolicy, "freshnessPolicy must not be null");
        expiresAt = normalizeOptional(expiresAt);
        if (confidence != null && (confidence < 0.0d || confidence > 1.0d)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        freshnessStatus = freshnessStatus == null ? FactFreshnessStatus.FRESH : freshnessStatus;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String requireBoundedStatement(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("statement must not be blank");
        }
        if (normalized.length() > MAX_STATEMENT_LENGTH) {
            throw new IllegalArgumentException("statement exceeds max length " + MAX_STATEMENT_LENGTH);
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }
}
