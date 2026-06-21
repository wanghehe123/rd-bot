package com.wish.rd.rag.trace;

import java.util.List;

public record RagTraceDetail(
        RagTraceRunView run,
        List<RagTraceNodeView> nodes
) {

    public RagTraceDetail {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
