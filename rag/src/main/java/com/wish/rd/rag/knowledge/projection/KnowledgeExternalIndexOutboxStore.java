package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;

import java.util.List;
import java.util.Optional;

/**
 * 外部索引 Outbox。claim/settle 使用 CAS，不在 mutation commit 内调用远端。
 */
public interface KnowledgeExternalIndexOutboxStore {

    KnowledgeExternalIndexOperation enqueue(KnowledgeExternalIndexOperation operation);

    List<KnowledgeExternalIndexOperation> claimBatch(String leaseOwner, long nowEpochMillis, long leaseUntilEpochMillis, int batchSize);

    Optional<KnowledgeExternalIndexOperation> settle(
            String eventId,
            ExternalKnowledgeOperationStatus expectedStatus,
            String leaseOwner,
            long expectedRowVersion,
            ExternalKnowledgeOperationStatus nextStatus,
            long nowEpochMillis
    );

    Optional<KnowledgeExternalIndexOperation> findById(String eventId);

    List<KnowledgeExternalIndexOperation> listByDocumentId(String documentId);

    List<KnowledgeExternalIndexOperation> listAll();

    void delete(String eventId);
}
