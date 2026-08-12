package com.wish.rd.engine.retry.model;

/** Lifecycle status for one immutable task retry checkpoint. */
public enum TaskRetryCheckpointStatus {
    CREATED,
    DISPATCHED,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_NEEDS_HUMAN,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED_RETRYABLE
                || this == FAILED_NEEDS_HUMAN || this == CANCELLED;
    }
}
