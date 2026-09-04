package com.wish.rd.rag.project.memory.model;

/** Bounded retrieval audit row for admin read models. */
public record ProjectMemoryRetrievalAuditEntry(
        String memoryId,
        String revisionId,
        long revisionVersion,
        String querySummary,
        int examinedRowCount,
        long observedAtEpochMillis
) {
    public ProjectMemoryRetrievalAuditEntry {
        memoryId = required(memoryId, "memoryId");
        revisionId = required(revisionId, "revisionId");
        revisionVersion = Math.max(0L, revisionVersion);
        querySummary = querySummary == null ? "" : querySummary.strip();
        examinedRowCount = Math.max(0, examinedRowCount);
        observedAtEpochMillis = Math.max(0L, observedAtEpochMillis);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
