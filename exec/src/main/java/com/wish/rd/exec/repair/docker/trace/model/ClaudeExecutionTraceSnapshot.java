package com.wish.rd.exec.repair.docker.trace.model;

import java.util.List;

/**
 * Bounded safe trace snapshot for one Claude Code execution.
 */
public record ClaudeExecutionTraceSnapshot(
        int version,
        String source,
        boolean available,
        boolean finalized,
        boolean truncated,
        boolean hasMore,
        long nextSequence,
        List<ClaudeExecutionTraceEntry> entries
) {

    public static final int VERSION = 1;

    public ClaudeExecutionTraceSnapshot {
        version = version <= 0 ? VERSION : version;
        source = source == null ? "" : source.strip();
        nextSequence = Math.max(0L, nextSequence);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static ClaudeExecutionTraceSnapshot unavailable(String source) {
        return new ClaudeExecutionTraceSnapshot(VERSION, source, false, false, false, false, 0L, List.of());
    }
}
