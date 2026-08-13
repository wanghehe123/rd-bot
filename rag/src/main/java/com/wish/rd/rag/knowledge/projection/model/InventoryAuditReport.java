package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 存量审计总览：分类计数、总数、求和校验、待回填剩余与未收敛操作数。
 */
public record InventoryAuditReport(
        String knowledgeBaseId,
        InventoryCategoryCounts categories,
        long documentTotal,
        boolean sumMatchesTotal,
        long pendingBackfillRemaining,
        long inFlightOperations
) {

    public InventoryAuditReport {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(categories, "categories must not be null");
        documentTotal = Math.max(0L, documentTotal);
        pendingBackfillRemaining = Math.max(0L, pendingBackfillRemaining);
        inFlightOperations = Math.max(0L, inFlightOperations);
    }
}
