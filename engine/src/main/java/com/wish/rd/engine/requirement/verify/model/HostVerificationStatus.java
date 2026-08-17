package com.wish.rd.engine.requirement.verify.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle status of one host BUILD/STATIC verification run.
 *
 * <p>This is independent of {@code AgentRole} and follows the same terminal-state
 * discipline as AI delivery-review runs: a terminal run is never reopened in place.
 * Callers are {@code RequirementAgentStageOrchestrator} and later store/HTTP adapters.
 */
public enum HostVerificationStatus {
    CREATED,
    PREPARING,
    BUILDING,
    STATIC_CHECKING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_NEEDS_HUMAN,
    SKIPPED_DOCS_ONLY,
    CANCELLED;

    private static final Set<HostVerificationStatus> TERMINAL = EnumSet.of(
            SUCCEEDED,
            FAILED_RETRYABLE,
            FAILED_NEEDS_HUMAN,
            SKIPPED_DOCS_ONLY,
            CANCELLED
    );

    /**
     * Whether this status is a terminal verification outcome.
     *
     * @return {@code true} when the run must not be reopened in place
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
