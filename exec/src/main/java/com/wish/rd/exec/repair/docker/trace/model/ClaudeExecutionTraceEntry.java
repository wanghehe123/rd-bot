package com.wish.rd.exec.repair.docker.trace.model;

/**
 * A user-visible, redacted execution event from a Claude Code container.
 *
 * <p>This deliberately carries no raw tool input, tool output, or model reasoning.
 */
public record ClaudeExecutionTraceEntry(
        long sequence,
        String kind,
        String label,
        String detail,
        boolean error
) {

    public ClaudeExecutionTraceEntry {
        sequence = Math.max(0L, sequence);
        kind = safe(kind);
        label = safe(label);
        detail = safe(detail);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
