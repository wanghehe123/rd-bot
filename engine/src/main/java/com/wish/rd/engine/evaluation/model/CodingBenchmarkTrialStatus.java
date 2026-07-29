package com.wish.rd.engine.evaluation.model;

/** Lifecycle state for one persistent `(campaign, case, arm, replicate)` coding trial. */
public enum CodingBenchmarkTrialStatus {
    QUEUED,
    PREPARING,
    RUNNING_AGENTS,
    RUNNING_ORACLE,
    RETRY_PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** @return whether no worker may further mutate this trial. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** @return whether the trial may occupy a worker, Docker resources, or a lease. */
    public boolean isActive() {
        return !isTerminal() && this != QUEUED && this != RETRY_PENDING;
    }
}
