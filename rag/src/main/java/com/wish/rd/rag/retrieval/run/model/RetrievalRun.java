package com.wish.rd.rag.retrieval.run.model;

import java.util.List;

/**
 * Persistable retrieval attempt. Query content is intentionally represented by a hash and redacted
 * preview; full inputs and outputs belong to artifacts rather than the operational row.
 */
public record RetrievalRun(
        String runId,
        String taskId,
        RetrievalConsumerType consumerType,
        String role,
        String stageRunId,
        int attemptNo,
        String parentRunId,
        String idempotencyKey,
        RetrievalRunStatus status,
        List<String> knowledgeBaseIds,
        String queryHash,
        String queryPreview,
        int currentIteration,
        int maxIterations,
        int contextBudgetChars,
        int candidateCount,
        int selectedEvidenceCount,
        EvidenceQualityDecision qualityDecision,
        String stopReason,
        String errorCategory,
        String errorMessage,
        String leaseOwner,
        long leaseUntilEpochMillis,
        long version,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {

    public RetrievalRun {
        runId = safe(runId);
        taskId = safe(taskId);
        consumerType = consumerType == null ? RetrievalConsumerType.BUG_FIX : consumerType;
        role = safe(role).toUpperCase();
        stageRunId = safe(stageRunId);
        attemptNo = Math.max(1, attemptNo);
        parentRunId = safe(parentRunId);
        idempotencyKey = safe(idempotencyKey);
        status = status == null ? RetrievalRunStatus.CREATED : status;
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds.stream()
                .filter(id -> id != null && !id.isBlank()).map(String::strip).distinct().toList();
        queryHash = safe(queryHash);
        queryPreview = safe(queryPreview);
        currentIteration = Math.max(0, currentIteration);
        maxIterations = Math.max(1, maxIterations);
        contextBudgetChars = Math.max(0, contextBudgetChars);
        candidateCount = Math.max(0, candidateCount);
        selectedEvidenceCount = Math.max(0, selectedEvidenceCount);
        stopReason = safe(stopReason);
        errorCategory = safe(errorCategory);
        errorMessage = safe(errorMessage);
        leaseOwner = safe(leaseOwner);
        leaseUntilEpochMillis = Math.max(0L, leaseUntilEpochMillis);
        version = Math.max(0L, version);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
        updatedAtEpochMillis = Math.max(0L, updatedAtEpochMillis);
    }

    public RetrievalRun withStatus(
            RetrievalRunStatus nextStatus,
            int nextIteration,
            EvidenceQualityDecision nextDecision,
            String nextStopReason,
            String nextErrorCategory,
            String nextErrorMessage,
            long now
    ) {
        return new RetrievalRun(
                runId, taskId, consumerType, role, stageRunId, attemptNo, parentRunId, idempotencyKey,
                nextStatus, knowledgeBaseIds, queryHash, queryPreview, nextIteration, maxIterations,
                contextBudgetChars, candidateCount, selectedEvidenceCount, nextDecision, nextStopReason,
                nextErrorCategory, nextErrorMessage, "", 0L, version + 1, createdAtEpochMillis, now
        );
    }

    public RetrievalRun withEvidenceCounts(int nextCandidateCount, int nextSelectedEvidenceCount, long now) {
        return new RetrievalRun(
                runId, taskId, consumerType, role, stageRunId, attemptNo, parentRunId, idempotencyKey,
                status, knowledgeBaseIds, queryHash, queryPreview, currentIteration, maxIterations,
                contextBudgetChars, nextCandidateCount, nextSelectedEvidenceCount, qualityDecision, stopReason,
                errorCategory, errorMessage, leaseOwner, leaseUntilEpochMillis, version + 1,
                createdAtEpochMillis, now
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
