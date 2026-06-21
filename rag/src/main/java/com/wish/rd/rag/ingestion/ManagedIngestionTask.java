package com.wish.rd.rag.ingestion;

import java.util.LinkedHashMap;
import java.util.Map;

public record ManagedIngestionTask(
        String id,
        String pipelineId,
        String knowledgeBaseId,
        String documentId,
        String sourceType,
        String sourceLocation,
        String sourceFileName,
        IngestionStatus status,
        int chunkCount,
        String errorMessage,
        Map<String, Object> metadata,
        long startedAtEpochMillis,
        Long completedAtEpochMillis,
        String createdBy,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public ManagedIngestionTask {
        status = status == null ? IngestionStatus.FAILED : status;
        errorMessage = errorMessage == null ? "" : errorMessage;
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
        createdBy = createdBy == null || createdBy.isBlank() ? "local" : createdBy;
    }
}
