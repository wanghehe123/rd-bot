package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeExternalIndexOutboxRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeExternalIndexOutboxMapper;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexSettleCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.LeaseDisposition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresKnowledgeExternalIndexOutboxStore implements KnowledgeExternalIndexOutboxStore {

    private final KnowledgeExternalIndexOutboxMapper mapper;

    public PostgresKnowledgeExternalIndexOutboxStore(KnowledgeExternalIndexOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public KnowledgeExternalIndexOperation enqueue(KnowledgeExternalIndexOperation operation) {
        try {
            int inserted = mapper.insertIfAbsent(toRow(operation));
            if (inserted == 1) {
                return operation;
            }
        } catch (DataIntegrityViolationException ignored) {
            // Unique (provider, document_id, sync_version, operation_type) absorbs the duplicate.
        }
        KnowledgeExternalIndexOutboxRow existing = mapper.findByIdempotencyKey(operation.idempotencyKey());
        if (existing == null && !operation.documentId().isBlank()) {
            existing = mapper.findByDocumentIdentity(
                    operation.provider(),
                    PostgresPersistenceSupport.parseId(operation.documentId()),
                    operation.syncVersion(),
                    operation.operationType().name()
            );
        }
        if (existing == null) {
            throw new IllegalStateException("outbox enqueue conflicted but existing row is missing: "
                    + operation.idempotencyKey());
        }
        KnowledgeExternalIndexOperation current = toOperation(existing);
        if (current.status().terminal()) {
            throw new IllegalStateException("cannot absorb terminal outbox row: " + current.eventId());
        }
        return current;
    }

    @Override
    public List<KnowledgeExternalIndexOperation> claimBatch(
            String leaseOwner,
            long nowEpochMillis,
            long leaseUntilEpochMillis,
            int batchSize
    ) {
        return mapper.claimBatch(
                        leaseOwner,
                        PostgresPersistenceSupport.toDateTime(nowEpochMillis),
                        PostgresPersistenceSupport.toDateTime(leaseUntilEpochMillis),
                        batchSize
                ).stream()
                .map(this::toOperation)
                .toList();
    }

    @Override
    public List<KnowledgeExternalIndexOperation> claimPollBatch(
            String leaseOwner,
            long nowEpochMillis,
            long leaseUntilEpochMillis,
            int batchSize
    ) {
        return mapper.claimPollBatch(
                        leaseOwner,
                        PostgresPersistenceSupport.toDateTime(nowEpochMillis),
                        PostgresPersistenceSupport.toDateTime(leaseUntilEpochMillis),
                        batchSize
                ).stream()
                .map(this::toOperation)
                .toList();
    }

    @Override
    public Optional<KnowledgeExternalIndexOperation> settle(
            String eventId,
            ExternalKnowledgeOperationStatus expectedStatus,
            String leaseOwner,
            long expectedRowVersion,
            ExternalIndexSettleCommand command,
            long nowEpochMillis
    ) {
        KnowledgeExternalIndexOutboxRow row = mapper.settle(
                PostgresPersistenceSupport.parseId(eventId),
                expectedStatus.name(),
                leaseOwner,
                expectedRowVersion,
                command.nextStatus().name(),
                command.leaseDisposition() == LeaseDisposition.RELEASE,
                command.remoteTaskId(),
                command.remoteOperationId(),
                PostgresPersistenceSupport.nullableDateTime(command.nextVisibleAtEpochMillis()),
                PostgresPersistenceSupport.nullableDateTime(command.publishedAtEpochMillis()),
                command.errorCode(),
                command.errorMessage(),
                command.clearError(),
                command.clearSendMarker(),
                command.refundAttempt(),
                PostgresPersistenceSupport.toDateTime(nowEpochMillis)
        );
        return Optional.ofNullable(row).map(this::toOperation);
    }

    @Override
    public Optional<KnowledgeExternalIndexOperation> findById(String eventId) {
        return Optional.ofNullable(mapper.selectById(PostgresPersistenceSupport.parseId(eventId)))
                .map(this::toOperation);
    }

    @Override
    public List<KnowledgeExternalIndexOperation> listByDocumentId(String documentId) {
        return mapper.selectList(new QueryWrapper<KnowledgeExternalIndexOutboxRow>()
                        .eq("document_id", PostgresPersistenceSupport.parseId(documentId)))
                .stream()
                .map(this::toOperation)
                .toList();
    }

    @Override
    public List<KnowledgeExternalIndexOperation> listAll() {
        return mapper.selectList(null).stream().map(this::toOperation).toList();
    }

    @Override
    public void delete(String eventId) {
        mapper.deleteById(PostgresPersistenceSupport.parseId(eventId));
    }

    private KnowledgeExternalIndexOutboxRow toRow(KnowledgeExternalIndexOperation operation) {
        KnowledgeExternalIndexOutboxRow row = new KnowledgeExternalIndexOutboxRow();
        row.eventId = PostgresPersistenceSupport.parseId(operation.eventId());
        row.idempotencyKey = operation.idempotencyKey();
        row.provider = operation.provider();
        row.operationType = operation.operationType().name();
        row.knowledgeBaseId = PostgresPersistenceSupport.parseId(operation.knowledgeBaseId());
        row.documentId = PostgresPersistenceSupport.parseOptionalId(operation.documentId());
        row.syncVersion = operation.syncVersion();
        row.checksum = operation.checksum();
        row.remoteUri = operation.remoteUri();
        row.revisionId = PostgresPersistenceSupport.parseOptionalId(operation.revisionId());
        row.payloadRef = operation.payloadRef();
        row.status = operation.status().name();
        row.remoteTaskId = operation.remoteTaskId();
        row.remoteOperationId = operation.remoteOperationId();
        row.leaseOwner = operation.leaseOwner();
        row.leaseUntil = PostgresPersistenceSupport.nullableDateTime(operation.leaseUntilEpochMillis());
        row.attemptCount = operation.attemptCount();
        row.maxAttempts = operation.maxAttempts();
        row.nextVisibleAt = PostgresPersistenceSupport.toDateTime(operation.nextVisibleAtEpochMillis());
        row.publishedAt = PostgresPersistenceSupport.nullableDateTime(operation.publishedAtEpochMillis());
        row.lastErrorCode = operation.lastErrorCode();
        row.lastErrorMessage = operation.lastErrorMessage();
        row.rowVersion = operation.rowVersion();
        row.createdAt = PostgresPersistenceSupport.toDateTime(operation.createdAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(operation.updatedAtEpochMillis());
        return row;
    }

    private KnowledgeExternalIndexOperation toOperation(KnowledgeExternalIndexOutboxRow row) {
        return new KnowledgeExternalIndexOperation(
                PostgresPersistenceSupport.idString(row.eventId),
                row.idempotencyKey,
                row.provider,
                ExternalKnowledgeOperationType.valueOf(row.operationType),
                PostgresPersistenceSupport.idString(row.knowledgeBaseId),
                PostgresPersistenceSupport.idString(row.documentId),
                row.syncVersion == null ? 1L : row.syncVersion,
                row.checksum,
                row.remoteUri,
                PostgresPersistenceSupport.idString(row.revisionId),
                row.payloadRef,
                ExternalKnowledgeOperationStatus.valueOf(row.status),
                row.remoteTaskId,
                row.remoteOperationId,
                row.leaseOwner,
                PostgresPersistenceSupport.toEpochMillis(row.leaseUntil),
                row.attemptCount == null ? 0 : row.attemptCount,
                row.maxAttempts == null ? 8 : row.maxAttempts,
                PostgresPersistenceSupport.toEpochMillis(row.nextVisibleAt),
                PostgresPersistenceSupport.toEpochMillis(row.publishedAt),
                row.lastErrorCode,
                row.lastErrorMessage,
                row.rowVersion == null ? 0L : row.rowVersion,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }
}
