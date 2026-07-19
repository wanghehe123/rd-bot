package com.wish.rd.rag.retrieval.run.model;

/** Immutable audit entry emitted atomically with a retrieval-run state transition. */
public record RetrievalRunEvent(
        String eventId,
        String runId,
        RetrievalRunStatus fromStatus,
        RetrievalRunStatus toStatus,
        String trigger,
        String message,
        String errorCategory,
        long occurredAtEpochMillis
) {

    public RetrievalRunEvent {
        eventId = safe(eventId);
        runId = safe(runId);
        trigger = safe(trigger);
        message = safe(message);
        errorCategory = safe(errorCategory);
        occurredAtEpochMillis = Math.max(0L, occurredAtEpochMillis);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
