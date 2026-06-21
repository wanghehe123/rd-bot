package com.wish.rd.rag.trace;

import java.util.Map;
import java.util.Objects;

public record RagTraceRunStart(
        String traceId,
        String traceName,
        String entryMethod,
        String conversationId,
        String taskId,
        String userId,
        Map<String, String> extraData
) {

    public RagTraceRunStart {
        Objects.requireNonNull(traceId, "traceId must not be null");
        traceName = traceName == null || traceName.isBlank() ? traceId : traceName;
        entryMethod = entryMethod == null ? "" : entryMethod;
        extraData = extraData == null ? Map.of() : Map.copyOf(extraData);
    }
}
