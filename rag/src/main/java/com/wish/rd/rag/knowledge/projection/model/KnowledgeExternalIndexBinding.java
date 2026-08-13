package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 一个 provider 对一篇逻辑文档的 desired/observed 投影账本。
 */
public record KnowledgeExternalIndexBinding(
        String provider,
        String documentId,
        String knowledgeBaseId,
        String remoteUri,
        String ownershipMarker,
        ExternalKnowledgeDesiredState desiredState,
        long desiredVersion,
        String desiredChecksum,
        ExternalKnowledgeObservedState observedState,
        long observedVersion,
        String observedChecksum,
        ExternalKnowledgeProjectionStatus projectionStatus,
        String activeOperationId,
        String remoteTaskId,
        String semanticConfigFingerprint,
        long lastSubmittedAtEpochMillis,
        long lastVerifiedAtEpochMillis,
        String lastErrorCode,
        String lastErrorMessage,
        long rowVersion,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {

    public static final String OPENVIKING = "OPENVIKING";

    public KnowledgeExternalIndexBinding {
        provider = provider == null || provider.isBlank() ? OPENVIKING : provider.strip().toUpperCase();
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        remoteUri = remoteUri == null ? "" : remoteUri;
        ownershipMarker = ownershipMarker == null ? "" : ownershipMarker;
        desiredState = desiredState == null ? ExternalKnowledgeDesiredState.PRESENT : desiredState;
        if (desiredVersion <= 0L) {
            throw new IllegalArgumentException("desiredVersion must be positive");
        }
        desiredChecksum = desiredChecksum == null ? "" : desiredChecksum;
        observedState = observedState == null ? ExternalKnowledgeObservedState.UNKNOWN : observedState;
        observedVersion = Math.max(0L, observedVersion);
        observedChecksum = observedChecksum == null ? "" : observedChecksum;
        projectionStatus = projectionStatus == null ? ExternalKnowledgeProjectionStatus.PENDING : projectionStatus;
        activeOperationId = activeOperationId == null ? "" : activeOperationId;
        remoteTaskId = remoteTaskId == null ? "" : remoteTaskId;
        semanticConfigFingerprint = semanticConfigFingerprint == null ? "" : semanticConfigFingerprint;
        lastSubmittedAtEpochMillis = Math.max(0L, lastSubmittedAtEpochMillis);
        lastVerifiedAtEpochMillis = Math.max(0L, lastVerifiedAtEpochMillis);
        lastErrorCode = lastErrorCode == null ? "" : lastErrorCode;
        lastErrorMessage = lastErrorMessage == null ? "" : lastErrorMessage;
        rowVersion = Math.max(0L, rowVersion);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
        updatedAtEpochMillis = Math.max(0L, updatedAtEpochMillis);
    }
}
