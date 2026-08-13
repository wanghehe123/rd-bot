package com.wish.rd.rag.knowledge.projection.impl;

import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexSettleCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public synchronized List<KnowledgeExternalIndexOperation> claimPollBatch(
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
            if (!pollable(operation, nowEpochMillis)) {
                continue;
            }
            KnowledgeExternalIndexOperation next =
                    operation.pollClaimed(leaseOwner, leaseUntilEpochMillis, nowEpochMillis);
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
            ExternalIndexSettleCommand command,
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
        KnowledgeExternalIndexOperation settled = current.settled(command, leaseOwner, nowEpochMillis);
        operations.put(eventId, settled);
        return Optional.of(settled);
    }

    @Override
    public synchronized Optional<KnowledgeExternalIndexOperation> findById(String eventId) {
        return Optional.ofNullable(operations.get(eventId));
    }

    @Override
    public synchronized Optional<KnowledgeExternalIndexOperation> requeueDeadLetter(
            String eventId,
            long expectedRowVersion,
            long nowEpochMillis
    ) {
        KnowledgeExternalIndexOperation current = operations.get(eventId);
        if (current == null
                || current.status() != ExternalKnowledgeOperationStatus.DEAD_LETTER
                || current.rowVersion() != expectedRowVersion) {
            return Optional.empty();
        }
        KnowledgeExternalIndexOperation next = current.requeued(nowEpochMillis);
        operations.put(eventId, next);
        return Optional.of(next);
    }

    @Override
    public synchronized List<KnowledgeExternalIndexOperation> listByDocumentId(String documentId) {
        return operations.values().stream()
                .filter(operation -> operation.documentId().equals(documentId))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeExternalIndexOperation> findByDocument(
            String provider,
            String documentId,
            int limit
    ) {
        String normalized = normalizeProvider(provider);
        return operations.values().stream()
                .filter(operation -> operation.provider().equals(normalized)
                        && operation.documentId().equals(documentId))
                .sorted(ADMIN_ORDER)
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeExternalIndexOperation> findByStatus(
            String provider,
            String knowledgeBaseId,
            ExternalKnowledgeOperationStatus status,
            int offset,
            int limit
    ) {
        String normalized = normalizeProvider(provider);
        return operations.values().stream()
                .filter(operation -> operation.provider().equals(normalized)
                        && operation.knowledgeBaseId().equals(knowledgeBaseId)
                        && operation.status() == status)
                .sorted(ADMIN_ORDER)
                .skip(Math.max(0, offset))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public synchronized Map<ExternalKnowledgeOperationStatus, Long> countByStatus(
            String provider,
            String knowledgeBaseId
    ) {
        String normalized = normalizeProvider(provider);
        EnumMap<ExternalKnowledgeOperationStatus, Long> counts =
                new EnumMap<>(ExternalKnowledgeOperationStatus.class);
        for (KnowledgeExternalIndexOperation operation : operations.values()) {
            if (operation.provider().equals(normalized) && operation.knowledgeBaseId().equals(knowledgeBaseId)) {
                counts.merge(operation.status(), 1L, Long::sum);
            }
        }
        return Map.copyOf(counts);
    }

    @Override
    public synchronized Optional<KnowledgeExternalIndexOperation> resumeStalled(
            String eventId,
            long expectedRowVersion,
            long nowEpochMillis
    ) {
        KnowledgeExternalIndexOperation current = operations.get(eventId);
        if (current == null || current.rowVersion() != expectedRowVersion) {
            return Optional.empty();
        }
        if (current.status() == ExternalKnowledgeOperationStatus.RETRY_WAIT) {
            KnowledgeExternalIndexOperation next = new KnowledgeExternalIndexOperation(
                    current.eventId(),
                    current.idempotencyKey(),
                    current.provider(),
                    current.operationType(),
                    current.knowledgeBaseId(),
                    current.documentId(),
                    current.syncVersion(),
                    current.checksum(),
                    current.remoteUri(),
                    current.revisionId(),
                    current.payloadRef(),
                    current.status(),
                    current.remoteTaskId(),
                    current.remoteOperationId(),
                    "",
                    0L,
                    current.attemptCount(),
                    current.maxAttempts(),
                    nowEpochMillis,
                    current.publishedAtEpochMillis(),
                    current.lastErrorCode(),
                    current.lastErrorMessage(),
                    current.rowVersion() + 1L,
                    current.createdAtEpochMillis(),
                    nowEpochMillis
            );
            operations.put(eventId, next);
            return Optional.of(next);
        }
        if (current.status() == ExternalKnowledgeOperationStatus.NEEDS_HUMAN) {
            KnowledgeExternalIndexOperation next = current.requeued(nowEpochMillis);
            operations.put(eventId, next);
            return Optional.of(next);
        }
        return Optional.empty();
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
        if (operation.crossedSendBoundary()) {
            return false;
        }
        if (operation.status() == ExternalKnowledgeOperationStatus.CLAIMED) {
            return operation.leaseUntilEpochMillis() > 0L && operation.leaseUntilEpochMillis() <= nowEpochMillis;
        }
        return operation.nextVisibleAtEpochMillis() <= nowEpochMillis
                && (operation.leaseUntilEpochMillis() <= 0L || operation.leaseUntilEpochMillis() <= nowEpochMillis);
    }

    private static boolean pollable(KnowledgeExternalIndexOperation operation, long nowEpochMillis) {
        if (!operation.status().awaitingRemoteOutcome()) {
            return false;
        }
        return operation.nextVisibleAtEpochMillis() <= nowEpochMillis
                && (operation.leaseUntilEpochMillis() <= 0L || operation.leaseUntilEpochMillis() <= nowEpochMillis);
    }

    private static String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? "OPENVIKING" : provider.strip().toUpperCase();
    }

    private static final Comparator<KnowledgeExternalIndexOperation> ADMIN_ORDER =
            Comparator.comparingLong(KnowledgeExternalIndexOperation::updatedAtEpochMillis)
                    .reversed()
                    .thenComparing(KnowledgeExternalIndexOperation::eventId, Comparator.reverseOrder());
}
