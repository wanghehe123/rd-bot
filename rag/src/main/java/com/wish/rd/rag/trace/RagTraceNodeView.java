package com.wish.rd.rag.trace;

import java.util.Map;

public record RagTraceNodeView(
        String traceId,
        String nodeId,
        String parentNodeId,
        int depth,
        String nodeType,
        String nodeName,
        String className,
        String methodName,
        String status,
        String errorMessage,
        long durationMs,
        long startTimeEpochMillis,
        long endTimeEpochMillis,
        Map<String, String> extraData
) {

    public RagTraceNodeView {
        extraData = extraData == null ? Map.of() : Map.copyOf(extraData);
    }
}
