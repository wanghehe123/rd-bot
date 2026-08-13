package com.wish.rd.rag.knowledge.projection.model;

import java.util.List;
import java.util.Objects;

/**
 * 一批回填的逐类计数与被跳过原因。
 */
public record InventoryBackfillBatchReport(
        String knowledgeBaseId,
        int attempted,
        int applied,
        int skippedAlreadyBound,
        int skippedNotEligible,
        int skippedConcurrentModification,
        String stopReason,
        List<InventoryBackfillOutcome> outcomes
) {

    public InventoryBackfillBatchReport {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        attempted = Math.max(0, attempted);
        applied = Math.max(0, applied);
        skippedAlreadyBound = Math.max(0, skippedAlreadyBound);
        skippedNotEligible = Math.max(0, skippedNotEligible);
        skippedConcurrentModification = Math.max(0, skippedConcurrentModification);
        stopReason = stopReason == null ? "" : stopReason;
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }
}
