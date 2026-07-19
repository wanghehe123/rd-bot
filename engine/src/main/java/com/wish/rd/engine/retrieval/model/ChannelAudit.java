package com.wish.rd.engine.retrieval.model;

/** Bounded audit projection for one requirement retrieval channel. */
public record ChannelAudit(
        String channel,
        boolean failed,
        int candidateCount,
        String errorCategory,
        String errorMessage
) {
    public ChannelAudit {
        channel = safe(channel);
        candidateCount = Math.max(0, candidateCount);
        errorCategory = safe(errorCategory);
        errorMessage = safe(errorMessage);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
