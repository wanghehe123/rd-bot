package com.wish.rd.rag.retrieval.run.model;

/** Lifecycle of one immutable-attempt retrieval run. */
public enum RetrievalRunStatus {
    CREATED,
    PLANNING,
    RETRIEVING,
    EVALUATING,
    PACKAGING,
    RECOVERING,
    WAITING_INPUT,
    SUCCEEDED,
    SUCCEEDED_DEGRADED,
    FAILED_RETRYABLE,
    FAILED_NEEDS_HUMAN,
    CANCELLED,
    DEAD_LETTERED;

    public boolean isTerminal() {
        return switch (this) {
            case WAITING_INPUT, SUCCEEDED, SUCCEEDED_DEGRADED, FAILED_RETRYABLE,
                    FAILED_NEEDS_HUMAN, CANCELLED, DEAD_LETTERED -> true;
            default -> false;
        };
    }
}
