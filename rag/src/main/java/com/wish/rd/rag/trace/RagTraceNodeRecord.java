package com.wish.rd.rag.trace;

import java.util.Map;
import java.util.Objects;

public record RagTraceNodeRecord(
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
        Map<String, String> extraData
) {

    public RagTraceNodeRecord {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        nodeType = nodeType == null || nodeType.isBlank() ? "UNKNOWN" : nodeType;
        nodeName = nodeName == null ? nodeId : nodeName;
        status = status == null || status.isBlank() ? "SUCCESS" : status;
        durationMs = Math.max(0L, durationMs);
        extraData = extraData == null ? Map.of() : Map.copyOf(extraData);
    }
}
