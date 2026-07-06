package com.wish.rd.rag.ingestion.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record ManagedIngestionTaskNode(
        String id,
        String taskId,
        String pipelineId,
        String nodeId,
        String nodeType,
        int nodeOrder,
        String status,
        long durationMs,
        String message,
        String errorMessage,
        Map<String, Object> output,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public ManagedIngestionTaskNode {
        status = status == null || status.isBlank() ? "SUCCESS" : status;
        message = message == null ? "" : message;
        errorMessage = errorMessage == null ? "" : errorMessage;
        output = output == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(output));
    }
}
