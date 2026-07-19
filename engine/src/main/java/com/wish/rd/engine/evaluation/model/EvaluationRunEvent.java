package com.wish.rd.engine.evaluation.model;

/** Append-only state transition event for an evaluation run. */
public record EvaluationRunEvent(
        String eventId,
        String runId,
        EvaluationRunStatus fromStatus,
        EvaluationRunStatus toStatus,
        String message,
        String errorCategory,
        String errorMessage,
        long occurredAtEpochMillis
) {
}
