package com.wish.rd.engine.evaluation.model;

/** Lifecycle status for one Web-triggered local evaluation attempt. */
public enum EvaluationRunStatus {
    CREATED,
    QUEUED,
    RECORDING,
    SCORING,
    REPORTING,
    DIFFING,
    CANCEL_REQUESTED,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** @return whether the status is immutable and no longer executing */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** @return whether the run may still consume local execution resources */
    public boolean isActive() {
        return !isTerminal() && this != CREATED;
    }
}
