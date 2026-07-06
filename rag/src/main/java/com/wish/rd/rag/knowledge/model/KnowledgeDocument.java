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
        long nextRefreshAtEpochMillis
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

    public KnowledgeDocument withEnabled(boolean newEnabled) {
        return new KnowledgeDocument(
                id,
                knowledgeBaseId,
                sourceName,
                knowledgeType,
                mimeType,
                status,
                newEnabled,
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
                nextRefreshAtEpochMillis
        );
    }

    public KnowledgeDocument withDocumentFields(String newSourceName, String newKnowledgeType) {
        return new KnowledgeDocument(
                id,
                knowledgeBaseId,
                newSourceName,
                newKnowledgeType,
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
                nextRefreshAtEpochMillis
        );
    }

    public KnowledgeDocument withChunkCount(int newChunkCount) {
        return new KnowledgeDocument(
                id,
                knowledgeBaseId,
                sourceName,
                knowledgeType,
                mimeType,
                status,
                enabled,
                newChunkCount,
                nodeLogs,
                createdAtEpochMillis,
                sourceType,
                sourceToken,
                sourceUrl,
                revisionId,
                checksum,
                rawPreview,
                lastSyncedAtEpochMillis,
                nextRefreshAtEpochMillis
        );
    }

    public KnowledgeDocument withSyncState(
            String newRevisionId,
            String newChecksum,
            String newRawPreview,
            long newLastSyncedAt,
            long newNextRefreshAt
    ) {
        return new KnowledgeDocument(
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
                newRevisionId,
                newChecksum,
                newRawPreview,
                newLastSyncedAt,
                newNextRefreshAt
        );
    }
}
