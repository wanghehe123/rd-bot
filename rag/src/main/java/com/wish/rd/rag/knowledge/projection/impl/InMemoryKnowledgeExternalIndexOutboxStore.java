package com.wish.rd.rag.knowledge.projection.impl;

import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * 内存 Outbox：唯一键吸收重复入队，claim/settle 带 lease 与 row_version CAS。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryKnowledgeExternalIndexOutboxStore implements KnowledgeExternalIndexOutboxStore {

    private final LinkedHashMap<String, KnowledgeExternalIndexOperation> operations = new LinkedHashMap<>();

    @Override
    public synchronized KnowledgeExternalIndexOperation enqueue(KnowledgeExternalIndexOperation operation) {
        Optional<KnowledgeExternalIndexOperation> existing = operations.values().stream()
                .filter(candidate -> sameIdentity(candidate, operation))
                .findFirst();
        if (existing.isPresent()) {
            KnowledgeExternalIndexOperation current = existing.get();
            if (current.status().terminal()) {
                throw new IllegalStateException("cannot absorb terminal outbox row: " + current.eventId());
            }
            return current;
        }
        operations.put(operation.eventId(), operation);
        return operation;
    }

    @Override
    public synchronized List<KnowledgeExternalIndexOperation> claimBatch(
            String leaseOwner,
            long nowEpochMillis,
            long leaseUntilEpochMillis,
            int batchSize
    ) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        ArrayList<KnowledgeExternalIndexOperation> claimed = new ArrayList<>();
        for (KnowledgeExternalIndexOperation operation : List.copyOf(operations.values())) {
            if (claimed.size() >= batchSize) {
                break;
            }
            if (!claimable(operation, nowEpochMillis)) {
                continue;
            }
            KnowledgeExternalIndexOperation next = operation.claimed(leaseOwner, leaseUntilEpochMillis, nowEpochMillis);
            operations.put(next.eventId(), next);
            claimed.add(next);
        }
        return List.copyOf(claimed);
    }

    @Override
    public synchronized Optional<KnowledgeExternalIndexOperation> settle(
            String eventId,
            ExternalKnowledgeOperationStatus expectedStatus,
            String leaseOwner,
            long expectedRowVersion,
            ExternalKnowledgeOperationStatus nextStatus,
            long nowEpochMillis
    ) {
        KnowledgeExternalIndexOperation current = operations.get(eventId);
        if (current == null) {
            return Optional.empty();
        }
        if (current.status() != expectedStatus
                || !current.leaseOwner().equals(leaseOwner)
                || current.rowVersion() != expectedRowVersion
                || !current.leaseActive(nowEpochMillis)) {
            return Optional.empty();
        }
        KnowledgeExternalIndexOperation settled = current.settled(nextStatus, leaseOwner, nowEpochMillis);
        operations.put(eventId, settled);
        return Optional.of(settled);
    }

    @Override
    public synchronized Optional<KnowledgeExternalIndexOperation> findById(String eventId) {
        return Optional.ofNullable(operations.get(eventId));
    }

    @Override
    public synchronized List<KnowledgeExternalIndexOperation> listByDocumentId(String documentId) {
        return operations.values().stream()
                .filter(operation -> operation.documentId().equals(documentId))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeExternalIndexOperation> listAll() {
        return List.copyOf(operations.values());
    }

    @Override
    public synchronized void delete(String eventId) {
        operations.remove(eventId);
    }

    private static boolean sameIdentity(
            KnowledgeExternalIndexOperation left,
            KnowledgeExternalIndexOperation right
    ) {
        if (left.idempotencyKey().equals(right.idempotencyKey())) {
            return true;
        }
        if (!left.documentId().isBlank() && !right.documentId().isBlank()) {
            return left.provider().equals(right.provider())
                    && left.documentId().equals(right.documentId())
                    && left.syncVersion() == right.syncVersion()
                    && left.operationType() == right.operationType();
        }
        return left.documentId().isBlank()
                && right.documentId().isBlank()
                && left.provider().equals(right.provider())
                && left.knowledgeBaseId().equals(right.knowledgeBaseId())
                && left.syncVersion() == right.syncVersion()
                && left.operationType() == right.operationType();
    }

    private static boolean claimable(KnowledgeExternalIndexOperation operation, long nowEpochMillis) {
        if (!operation.status().claimable() || operation.attemptCount() >= operation.maxAttempts()) {
            return false;
        }
        if (operation.status() == ExternalKnowledgeOperationStatus.CLAIMED) {
            return operation.leaseUntilEpochMillis() > 0L && operation.leaseUntilEpochMillis() <= nowEpochMillis;
        }
        return operation.nextVisibleAtEpochMillis() <= nowEpochMillis
                && (operation.leaseUntilEpochMillis() <= 0L || operation.leaseUntilEpochMillis() <= nowEpochMillis);
    }
}
