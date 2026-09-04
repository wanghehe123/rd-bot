package com.wish.rd.engine.admin.projectmemory.model;

/** Recent retrieval audit row for admin detail views. */
public record ProjectMemoryRetrievalAuditView(
        String memoryId,
        String revisionId,
        long revisionVersion,
        String querySummary,
        int examinedRowCount,
        long observedAtEpochMillis
) {}
