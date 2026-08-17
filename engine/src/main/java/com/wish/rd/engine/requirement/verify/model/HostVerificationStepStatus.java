package com.wish.rd.engine.requirement.verify.model;

/**
 * Execution status of a single host verification step.
 *
 * <p>Steps start {@link #PENDING}, move to {@link #RUNNING}, then settle as
 * {@link #SUCCEEDED}, {@link #FAILED}, or {@link #SKIPPED} (for example docs-only).
 */
public enum HostVerificationStepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
