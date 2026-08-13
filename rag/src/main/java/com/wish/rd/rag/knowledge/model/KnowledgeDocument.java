package com.wish.rd.rag.knowledge.model;

import com.wish.rd.rag.ingestion.model.IngestionNodeLog;

import java.util.List;
import java.util.Objects;

public record KnowledgeDocument(
        String id,
        String knowledgeBaseId,
        String sourceName,
        String knowledgeType,
        String mimeType,
        KnowledgeDocumentStatus status,
        boolean enabled,
        int chunkCount,
        List<IngestionNodeLog> nodeLogs,
        long createdAtEpochMillis,
        String sourceType,
        String sourceToken,
        String sourceUrl,
        String revisionId,
        String checksum,
        String rawPreview,
        long lastSyncedAtEpochMillis,
        long nextRefreshAtEpochMillis,
        long syncVersion,
        String currentRevisionId,
        String sourceIdentityKey,
        long deletedAtEpochMillis,
        long purgeAfterEpochMillis,
        String supersededByDocumentId,
        long rowVersion,
        boolean localOnlyOverride
) {

    public KnowledgeDocument {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(knowledgeType, "knowledgeType must not be null");
        mimeType = mimeType == null || mimeType.isBlank() ? "text/plain" : mimeType;
        status = status == null ? KnowledgeDocumentStatus.UNKNOWN : status;
        chunkCount = Math.max(0, chunkCount);
        nodeLogs = nodeLogs == null ? List.of() : List.copyOf(nodeLogs);
        sourceType = sourceType == null || sourceType.isBlank() ? "LOCAL" : sourceType.strip().toUpperCase();
        sourceToken = sourceToken == null ? "" : sourceToken;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
        revisionId = revisionId == null ? "" : revisionId;
        checksum = checksum == null ? "" : checksum;
        rawPreview = rawPreview == null ? "" : rawPreview;
        lastSyncedAtEpochMillis = Math.max(0L, lastSyncedAtEpochMillis);
        nextRefreshAtEpochMillis = Math.max(0L, nextRefreshAtEpochMillis);
        syncVersion = syncVersion <= 0L ? 1L : syncVersion;
        currentRevisionId = currentRevisionId == null ? "" : currentRevisionId;
        sourceIdentityKey = sourceIdentityKey == null ? "" : sourceIdentityKey;
        deletedAtEpochMillis = Math.max(0L, deletedAtEpochMillis);
        purgeAfterEpochMillis = Math.max(0L, purgeAfterEpochMillis);
        supersededByDocumentId = supersededByDocumentId == null ? "" : supersededByDocumentId;
        rowVersion = Math.max(0L, rowVersion);
    }

    public KnowledgeDocument(
            String id,
            String knowledgeBaseId,
            String sourceName,
            String knowledgeType,
            String mimeType,
            KnowledgeDocumentStatus status,
            boolean enabled,
            int chunkCount,
            List<IngestionNodeLog> nodeLogs,
            long createdAtEpochMillis,
            String sourceType,
            String sourceToken,
            String sourceUrl,
            String revisionId,
            String checksum,
            String rawPreview,
            long lastSyncedAtEpochMillis,
            long nextRefreshAtEpochMillis
    ) {
        this(
                id,
                knowledgeBaseId,
                sourceName,
                knowledgeType,
                mimeType,
                status,
                enabled,
                chunkCount,
                nodeLogs,
                createdAtEpochMillis,
                sourceType,
                sourceToken,
                sourceUrl,
                revisionId,
                checksum,
                rawPreview,
                lastSyncedAtEpochMillis,
                nextRefreshAtEpochMillis,
                1L,
                "",
                "",
                0L,
                0L,
                "",
                0L,
                false
        );
    }

    public KnowledgeDocument(
            String id,
            String knowledgeBaseId,
            String sourceName,
            String knowledgeType,
            String mimeType,
            KnowledgeDocumentStatus status,
            boolean enabled,
            int chunkCount,
            List<IngestionNodeLog> nodeLogs,
            long createdAtEpochMillis
    ) {
        this(
                id,
                knowledgeBaseId,
                sourceName,
                knowledgeType,
                mimeType,
                status,
                enabled,
                chunkCount,
                nodeLogs,
                createdAtEpochMillis,
                "LOCAL",
                "",
                "",
                "",
                "",
                "",
                createdAtEpochMillis,
                0L
        );
    }

    public boolean visible() {
        return deletedAtEpochMillis == 0L && supersededByDocumentId.isBlank();
    }

    public KnowledgeDocument withEnabled(boolean newEnabled) {
        return withAvailability(newEnabled, syncVersion);
    }

    public KnowledgeDocument withAvailability(boolean newEnabled, long newSyncVersion) {
        return copy(
                sourceName,
                knowledgeType,
                status,
                newEnabled,
                chunkCount,
                nodeLogs,
                revisionId,
                checksum,
                rawPreview,
                lastSyncedAtEpochMillis,
                nextRefreshAtEpochMillis,
                newSyncVersion,
                currentRevisionId,
                sourceIdentityKey,
                deletedAtEpochMillis,
                purgeAfterEpochMillis,
                localOnlyOverride
        );
    }

    public KnowledgeDocument withDocumentFields(String newSourceName, String newKnowledgeType) {
        return copy(
                newSourceName,
                newKnowledgeType,
                status,
                enabled,
                chunkCount,
                nodeLogs,
                revisionId,
                checksum,
                rawPreview,
                lastSyncedAtEpochMillis,
                nextRefreshAtEpochMillis,
                syncVersion,
                currentRevisionId,
                sourceIdentityKey,
                deletedAtEpochMillis,
                purgeAfterEpochMillis,
                localOnlyOverride
        );
    }

    public KnowledgeDocument withChunkCount(int newChunkCount) {
        return copy(
                sourceName,
                knowledgeType,
                status,
                enabled,
                newChunkCount,
                nodeLogs,
                revisionId,
                checksum,
                rawPreview,
                lastSyncedAtEpochMillis,
                nextRefreshAtEpochMillis,
                syncVersion,
                currentRevisionId,
                sourceIdentityKey,
                deletedAtEpochMillis,
                purgeAfterEpochMillis,
                localOnlyOverride
        );
    }

    public KnowledgeDocument withSyncState(
            String newRevisionId,
            String newChecksum,
            String newRawPreview,
            long newLastSyncedAt,
            long newNextRefreshAt
    ) {
        return copy(
                sourceName,
                knowledgeType,
                status,
                enabled,
                chunkCount,
                nodeLogs,
                newRevisionId,
                newChecksum,
                newRawPreview,
                newLastSyncedAt,
                newNextRefreshAt,
                syncVersion,
                currentRevisionId,
                sourceIdentityKey,
                deletedAtEpochMillis,
                purgeAfterEpochMillis,
                localOnlyOverride
        );
    }

    public KnowledgeDocument withLocalOnlyOverride(boolean newLocalOnlyOverride) {
        return copy(
                sourceName,
                knowledgeType,
                status,
                enabled,
                chunkCount,
                nodeLogs,
                revisionId,
                checksum,
                rawPreview,
                lastSyncedAtEpochMillis,
                nextRefreshAtEpochMillis,
                syncVersion,
                currentRevisionId,
                sourceIdentityKey,
                deletedAtEpochMillis,
                purgeAfterEpochMillis,
                newLocalOnlyOverride
        );
    }

    public KnowledgeDocument withSoftDeleted(long deletedAt, long purgeAfter) {
        return copy(
                sourceName,
                knowledgeType,
                status,
                enabled,
                chunkCount,
                nodeLogs,
                revisionId,
                checksum,
                rawPreview,
                lastSyncedAtEpochMillis,
                nextRefreshAtEpochMillis,
                syncVersion,
                currentRevisionId,
                sourceIdentityKey,
                deletedAt,
                purgeAfter,
                localOnlyOverride
        );
    }

    public KnowledgeDocument withIndexedSnapshot(
            String newSourceName,
            String newKnowledgeType,
            String newMimeType,
            KnowledgeDocumentStatus newStatus,
            int newChunkCount,
            List<IngestionNodeLog> newNodeLogs,
            String newRevisionId,
            String newChecksum,
            String newRawPreview,
            long newLastSyncedAt,
            long newNextRefreshAt,
            long newSyncVersion,
            String newCurrentRevisionId,
            String newSourceIdentityKey,
            boolean newLocalOnlyOverride
    ) {
        return new KnowledgeDocument(
                id,
                knowledgeBaseId,
                newSourceName,
                newKnowledgeType,
                newMimeType,
                newStatus,
                enabled,
                newChunkCount,
                newNodeLogs,
                createdAtEpochMillis,
                sourceType,
                sourceToken,
                sourceUrl,
                newRevisionId,
                newChecksum,
                newRawPreview,
                newLastSyncedAt,
                newNextRefreshAt,
                newSyncVersion,
                newCurrentRevisionId,
                newSourceIdentityKey,
                deletedAtEpochMillis,
                purgeAfterEpochMillis,
                supersededByDocumentId,
                rowVersion + 1L,
                newLocalOnlyOverride
        );
    }

    private KnowledgeDocument copy(
            String newSourceName,
            String newKnowledgeType,
            KnowledgeDocumentStatus newStatus,
            boolean newEnabled,
            int newChunkCount,
            List<IngestionNodeLog> newNodeLogs,
            String newRevisionId,
            String newChecksum,
            String newRawPreview,
            long newLastSyncedAt,
            long newNextRefreshAt,
            long newSyncVersion,
            String newCurrentRevisionId,
            String newSourceIdentityKey,
            long newDeletedAt,
            long newPurgeAfter,
            boolean newLocalOnlyOverride
    ) {
        return new KnowledgeDocument(
                id,
                knowledgeBaseId,
                newSourceName,
                newKnowledgeType,
                mimeType,
                newStatus,
                newEnabled,
                newChunkCount,
                newNodeLogs,
                createdAtEpochMillis,
                sourceType,
                sourceToken,
                sourceUrl,
                newRevisionId,
                newChecksum,
                newRawPreview,
                newLastSyncedAt,
                newNextRefreshAt,
                newSyncVersion,
                newCurrentRevisionId,
                newSourceIdentityKey,
                newDeletedAt,
                newPurgeAfter,
                supersededByDocumentId,
                rowVersion,
                newLocalOnlyOverride
        );
    }
}
