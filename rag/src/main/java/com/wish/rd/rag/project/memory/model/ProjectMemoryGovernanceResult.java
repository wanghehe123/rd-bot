package com.wish.rd.rag.project.memory.model;

/** Outcome of a governance mutation with post-commit row versions. */
public record ProjectMemoryGovernanceResult(
        String memoryId,
        String revisionId,
        long memoryRowVersion,
        long revisionRowVersion,
        ProjectMemoryRevisionStatus revisionStatus,
        boolean memoryDeleted
) {
    public ProjectMemoryGovernanceResult {
        memoryId = required(memoryId, "memoryId");
        revisionId = required(revisionId, "revisionId");
        memoryRowVersion = Math.max(1L, memoryRowVersion);
        revisionRowVersion = Math.max(1L, revisionRowVersion);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
