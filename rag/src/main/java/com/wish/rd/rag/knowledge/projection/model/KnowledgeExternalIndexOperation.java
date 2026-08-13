package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 外部索引 Outbox 行。lease 过期后旧 owner 不得 settle。
 */
public record KnowledgeExternalIndexOperation(
        String eventId,
        String idempotencyKey,
        String provider,
        ExternalKnowledgeOperationType operationType,
        String knowledgeBaseId,
        String documentId,
        long syncVersion,
        String checksum,
        String remoteUri,
        String revisionId,
        String payloadRef,
        ExternalKnowledgeOperationStatus status,
        String remoteTaskId,
        String remoteOperationId,
        String leaseOwner,
        long leaseUntilEpochMillis,
        int attemptCount,
        int maxAttempts,
        long nextVisibleAtEpochMillis,
        long publishedAtEpochMillis,
        String lastErrorCode,
        String lastErrorMessage,
        long rowVersion,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {

    public KnowledgeExternalIndexOperation {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        provider = provider == null || provider.isBlank() ? KnowledgeExternalIndexBinding.OPENVIKING : provider.strip().toUpperCase();
        Objects.requireNonNull(operationType, "operationType must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        documentId = documentId == null ? "" : documentId;
        if (syncVersion <= 0L) {
            throw new IllegalArgumentException("syncVersion must be positive");
        }
        checksum = checksum == null ? "" : checksum;
        remoteUri = remoteUri == null ? "" : remoteUri;
        revisionId = revisionId == null ? "" : revisionId;
        payloadRef = payloadRef == null ? "" : payloadRef;
        status = status == null ? ExternalKnowledgeOperationStatus.PENDING : status;
        remoteTaskId = remoteTaskId == null ? "" : remoteTaskId;
        remoteOperationId = remoteOperationId == null ? "" : remoteOperationId;
        leaseOwner = leaseOwner == null ? "" : leaseOwner;
        leaseUntilEpochMillis = Math.max(0L, leaseUntilEpochMillis);
        attemptCount = Math.max(0, attemptCount);
        maxAttempts = maxAttempts <= 0 ? 8 : maxAttempts;
        nextVisibleAtEpochMillis = Math.max(0L, nextVisibleAtEpochMillis);
        publishedAtEpochMillis = Math.max(0L, publishedAtEpochMillis);
        lastErrorCode = lastErrorCode == null ? "" : lastErrorCode;
        lastErrorMessage = lastErrorMessage == null ? "" : lastErrorMessage;
        rowVersion = Math.max(0L, rowVersion);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
        updatedAtEpochMillis = Math.max(0L, updatedAtEpochMillis);
    }

    public boolean leaseActive(long nowEpochMillis) {
        return !leaseOwner.isBlank() && leaseUntilEpochMillis > nowEpochMillis;
    }

    public KnowledgeExternalIndexOperation claimed(String owner, long leaseUntil, long nowEpochMillis) {
        return new KnowledgeExternalIndexOperation(
                eventId,
                idempotencyKey,
                provider,
                operationType,
                knowledgeBaseId,
                documentId,
                syncVersion,
                checksum,
                remoteUri,
                revisionId,
                payloadRef,
                ExternalKnowledgeOperationStatus.CLAIMED,
                remoteTaskId,
                remoteOperationId,
                owner,
                leaseUntil,
                attemptCount + 1,
                maxAttempts,
                nextVisibleAtEpochMillis,
                publishedAtEpochMillis,
                "",
                "",
                rowVersion + 1L,
                createdAtEpochMillis,
                nowEpochMillis
        );
    }

    public KnowledgeExternalIndexOperation settled(
            ExternalKnowledgeOperationStatus nextStatus,
            String owner,
            long nowEpochMillis
    ) {
        return new KnowledgeExternalIndexOperation(
                eventId,
                idempotencyKey,
                provider,
                operationType,
                knowledgeBaseId,
                documentId,
                syncVersion,
                checksum,
                remoteUri,
                revisionId,
                payloadRef,
                nextStatus,
                remoteTaskId,
                remoteOperationId,
                nextStatus.terminal() ? "" : owner,
                nextStatus.terminal() ? 0L : leaseUntilEpochMillis,
                attemptCount,
                maxAttempts,
                nextVisibleAtEpochMillis,
                publishedAtEpochMillis,
                lastErrorCode,
                lastErrorMessage,
                rowVersion + 1L,
                createdAtEpochMillis,
                nowEpochMillis
        );
    }
}
