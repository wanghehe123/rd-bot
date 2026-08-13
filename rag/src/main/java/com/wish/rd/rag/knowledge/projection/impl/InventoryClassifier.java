package com.wish.rd.rag.knowledge.projection.impl;

import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeBaseLifecycle;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.InventoryCategory;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;

/**
 * D5 顺序判定：先命中即归类。漂移不在此列。
 */
final class InventoryClassifier {

    private InventoryClassifier() {
    }

    static InventoryCategory classify(
            KnowledgeDocument document,
            KnowledgeBase base,
            KnowledgeExternalIndexBinding binding,
            boolean duplicateIdentity
    ) {
        if (document.deletedAtEpochMillis() > 0L) {
            return InventoryCategory.TOMBSTONE;
        }
        if (!document.supersededByDocumentId().isBlank()) {
            return InventoryCategory.SUPERSEDED;
        }
        if (document.visible() && !document.sourceIdentityKey().isBlank() && duplicateIdentity) {
            return InventoryCategory.DUPLICATE_UNRESOLVED;
        }
        if (base == null || base.lifecycleStatus() != KnowledgeBaseLifecycle.ACTIVE) {
            return InventoryCategory.EXCLUDED_BASE_INACTIVE;
        }
        if (document.localOnlyOverride()) {
            return InventoryCategory.EXCLUDED_LOCAL_ONLY;
        }
        if (document.chunkCount() <= 0 || document.checksum().isBlank()) {
            return InventoryCategory.EXCLUDED_EMPTY;
        }
        if (binding != null) {
            ExternalKnowledgeProjectionStatus status = binding.projectionStatus();
            if (status == ExternalKnowledgeProjectionStatus.FAILED
                    || status == ExternalKnowledgeProjectionStatus.DEAD_LETTER
                    || status == ExternalKnowledgeProjectionStatus.NEEDS_HUMAN) {
                return InventoryCategory.FAILED;
            }
            if (status == ExternalKnowledgeProjectionStatus.IN_SYNC) {
                return InventoryCategory.IN_SYNC;
            }
            return InventoryCategory.PROJECTING;
        }
        return InventoryCategory.PENDING_BACKFILL;
    }
}
