package com.wish.rd.engine.project.memory.model;

import com.wish.rd.engine.project.memory.ProjectMemoryCaptureEvidence;
import com.wish.rd.engine.project.memory.ProjectMemoryPromotionEvidence;

/** Host-owned consolidation input bound to one deterministic operation identity. */
public record ProjectMemoryConsolidationRequest(
        String memoryId,
        String revisionId,
        String sourceId,
        String scopeRole,
        ProjectMemoryCandidate candidate,
        ProjectMemoryCaptureEvidence captureEvidence,
        ProjectMemoryPromotionEvidence promotionEvidence,
        String sourceUri,
        String taskId,
        String stageRunId,
        String repositoryRevision,
        long createdAtEpochMillis,
        boolean irreconcilableConflict
) {
    public ProjectMemoryConsolidationRequest {
        memoryId = safe(memoryId);
        revisionId = safe(revisionId);
        sourceId = safe(sourceId);
        scopeRole = safe(scopeRole);
        sourceUri = safe(sourceUri);
        taskId = safe(taskId);
        stageRunId = safe(stageRunId);
        repositoryRevision = safe(repositoryRevision);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
