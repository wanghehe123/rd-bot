package com.wish.rd.rag.ingestion;

public record IngestionNodeLog(
        IngestionNodeType nodeType,
        String message,
        long durationMs,
        boolean success
) {

    public static IngestionNodeLog success(IngestionNodeType nodeType, String message, long durationMs) {
        return new IngestionNodeLog(nodeType, message, durationMs, true);
    }
}
