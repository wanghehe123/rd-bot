package com.wish.rd.rag.knowledge;

import com.wish.rd.rag.ingestion.IngestionNodeLog;

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
        long createdAtEpochMillis
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
                createdAtEpochMillis
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
                createdAtEpochMillis
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
                createdAtEpochMillis
        );
    }
}
