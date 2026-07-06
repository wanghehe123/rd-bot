package com.wish.rd.rag.ingestion.model;

import com.wish.rd.framework.convention.model.RetrievedChunk;

import java.util.List;

public record IngestionTaskResult(
        String taskId,
        String pipelineId,
        IngestionStatus status,
        String rawText,
        List<RetrievedChunk> chunks,
        List<IngestionNodeLog> nodeLogs,
        String message
) {

    public IngestionTaskResult {
        status = status == null ? IngestionStatus.FAILED : status;
        rawText = rawText == null ? "" : rawText;
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        nodeLogs = nodeLogs == null ? List.of() : List.copyOf(nodeLogs);
        message = message == null ? "" : message;
    }
}
