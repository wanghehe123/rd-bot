package com.wish.rd.engine.requirement.review.model;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle status for an independent AI delivery-review attempt. */
public enum AiReviewRunStatus {
    CREATED,
    PACKAGING,
    REVIEWING,
    VALIDATING,
    SUCCEEDED_OK,
    SUCCEEDED_NOT_OK,
    SUCCEEDED_NEEDS_HUMAN,
    FAILED_RETRYABLE,
    CANCELLED;

    private static final Set<AiReviewRunStatus> TERMINAL = EnumSet.of(
            SUCCEEDED_OK,
            SUCCEEDED_NOT_OK,
            SUCCEEDED_NEEDS_HUMAN,
            FAILED_RETRYABLE,
            CANCELLED
    );

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
