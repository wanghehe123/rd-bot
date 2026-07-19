package com.wish.rd.engine.requirement.job.model;

/** Durable dispatch lifecycle for one requirement delivery task. */
public enum RequirementDeliveryJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    DEAD_LETTERED,
    CANCELLED
}
