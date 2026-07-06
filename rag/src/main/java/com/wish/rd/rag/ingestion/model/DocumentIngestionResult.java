package com.wish.rd.rag.ingestion.model;

import com.wish.rd.framework.convention.model.RetrievedChunk;

import java.util.List;

public record DocumentIngestionResult(
        String status,
        List<RetrievedChunk> chunks,
        List<IngestionNodeLog> nodeLogs
) {

    public DocumentIngestionResult {
        status = status == null ? "UNKNOWN" : status;
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        nodeLogs = nodeLogs == null ? List.of() : List.copyOf(nodeLogs);
    }
}
