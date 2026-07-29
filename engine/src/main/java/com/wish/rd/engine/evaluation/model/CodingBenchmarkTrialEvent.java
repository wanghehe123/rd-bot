package com.wish.rd.engine.evaluation.model;

/**
 * Immutable append-only lifecycle evidence for a coding benchmark trial.
 *
 * <p>An event is written in the same transaction as its corresponding trial mutation so recovery
 * and later statistical reports can distinguish a worker failure from a lost lease.</p>
 */
public record CodingBenchmarkTrialEvent(
        String eventId,
        String campaignId,
        String trialId,
        CodingBenchmarkTrialStatus fromStatus,
        CodingBenchmarkTrialStatus toStatus,
        long version,
        String message,
        String errorCategory,
        String errorMessage,
        long occurredAtEpochMillis
) {
}
