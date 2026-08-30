package com.wish.rd.engine.admin.projectmemory.model;

import com.wish.rd.rag.project.memory.model.ProjectMemoryPurgeCounts;

/** Authorized purge preview with short-lived confirmation token. */
public record ProjectMemoryPurgePreviewResult(
        String projectId,
        String operatorId,
        String reason,
        ProjectMemoryPurgeCounts expectedCounts,
        String confirmToken,
        long confirmTokenExpiresAtEpochMillis,
        String requestId
) {}
