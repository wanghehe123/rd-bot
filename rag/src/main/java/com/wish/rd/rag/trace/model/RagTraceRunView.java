package com.wish.rd.rag.trace.model;

import java.util.Map;

public record RagTraceRunView(
        String traceId,
        String traceName,
        String entryMethod,
        String conversationId,
        String taskId,
        String userId,
        String status,
        String errorMessage,
        long durationMs,
        long startTimeEpochMillis,
        long endTimeEpochMillis,
        String question,
        Map<String, String> extraData
) {

    public RagTraceRunView {
        status = status == null ? "UNKNOWN" : status;
        durationMs = Math.max(0L, durationMs);
        question = question == null ? "" : question;
        extraData = extraData == null ? Map.of() : Map.copyOf(extraData);
    }
}
