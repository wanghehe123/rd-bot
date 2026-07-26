package com.wish.rd.exec.repair.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Bounded, normalized runtime-event snapshot suitable for polling or SSE replay. */
public record AgentExecutionTraceSnapshot(
        int version,
        String source,
        boolean available,
        boolean finalized,
        boolean truncated,
        boolean hasMore,
        long nextSequence,
        List<JsonNode> events
) {

    public static final int VERSION = 1;

    public AgentExecutionTraceSnapshot {
        version = version <= 0 ? VERSION : version;
        source = source == null ? "" : source.strip();
        nextSequence = Math.max(0L, nextSequence);
        events = events == null
                ? List.<JsonNode>of()
                : events.stream().map(node -> (JsonNode) node.deepCopy()).toList();
    }

    public static AgentExecutionTraceSnapshot unavailable(String source) {
        return new AgentExecutionTraceSnapshot(VERSION, source, false, false, false, false, 0L, List.of());
    }
}
