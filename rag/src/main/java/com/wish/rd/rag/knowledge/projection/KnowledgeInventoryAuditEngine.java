package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.projection.model.DuplicateIdentityGroup;
import com.wish.rd.rag.knowledge.projection.model.InventoryAuditReport;
import com.wish.rd.rag.knowledge.projection.model.InventoryCategoryCounts;
import com.wish.rd.rag.knowledge.projection.model.InventoryDriftEntry;

import java.util.List;
import java.util.Objects;

/**
 * 存量审计只读聚合。不写 store。
 */
public final class KnowledgeInventoryAuditEngine {

    private final KnowledgeInventoryAuditStore auditStore;

    public KnowledgeInventoryAuditEngine(KnowledgeInventoryAuditStore auditStore) {
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore must not be null");
    }

    public InventoryAuditReport overview(String knowledgeBaseId) {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        InventoryCategoryCounts categories = auditStore.countByCategory(knowledgeBaseId);
        long total = auditStore.countDocuments(knowledgeBaseId);
        return new InventoryAuditReport(
                knowledgeBaseId,
                categories,
                total,
                categories.sum() == total,
                categories.pendingBackfill(),
                auditStore.countInFlightOperations(knowledgeBaseId)
        );
    }

    public List<KnowledgeDocument> candidates(String knowledgeBaseId, String afterDocumentId, int limit) {
        return auditStore.nextBackfillCandidates(knowledgeBaseId, afterDocumentId, limit);
    }

    public List<DuplicateIdentityGroup> duplicates(String knowledgeBaseId, int limit) {
        return auditStore.listDuplicateGroups(knowledgeBaseId, limit);
    }

    public List<InventoryDriftEntry> drift(String knowledgeBaseId, int limit) {
        return auditStore.listLocalOrphanDrift(knowledgeBaseId, limit);
    }
}
