package com.wish.rd.rag.knowledge;

import java.util.Map;

/**
 * Knowledge refresh operation metric.
 *
 * @param documentId           document id
 * @param knowledgeBaseId      knowledge base id
 * @param sourceType           source type
 * @param sourceName           source name
 * @param success              whether refresh succeeded
 * @param oldChunkCount        chunk count before refresh
 * @param newChunkCount        chunk count after refresh
 * @param durationMillis       refresh duration
 * @param errorMessage         error summary when failed
 * @param metadata             extension metadata
 * @param createdAtEpochMillis metric creation time
 */
public record KnowledgeRefreshMetric(
        String documentId,
        String knowledgeBaseId,
        String sourceType,
        String sourceName,
        boolean success,
        int oldChunkCount,
        int newChunkCount,
        long durationMillis,
        String errorMessage,
        Map<String, String> metadata,
        long createdAtEpochMillis
) {

    public KnowledgeRefreshMetric {
        documentId = normalize(documentId);
        knowledgeBaseId = normalize(knowledgeBaseId);
        sourceType = normalize(sourceType);
        sourceName = normalize(sourceName);
        oldChunkCount = Math.max(0, oldChunkCount);
        newChunkCount = Math.max(0, newChunkCount);
        durationMillis = Math.max(0L, durationMillis);
        errorMessage = normalize(errorMessage);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
