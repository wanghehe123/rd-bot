package com.wish.rd.engine.requirement.verify.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
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

    private static final Map<HostVerificationStatus, Set<HostVerificationStatus>> ALLOWED = allowedTransitions();

    /**
     * Whether this status is a terminal verification outcome.
     *
     * @return {@code true} when the run must not be reopened in place
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * Whether a store may move from this status to {@code target}.
     *
     * @param target candidate next status
     * @return {@code true} when the edge is in the allowed graph
     */
    public boolean canTransitionTo(HostVerificationStatus target) {
        return target != null && ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    private static Map<HostVerificationStatus, Set<HostVerificationStatus>> allowedTransitions() {
        EnumMap<HostVerificationStatus, Set<HostVerificationStatus>> allowed =
                new EnumMap<>(HostVerificationStatus.class);
        allowed.put(CREATED, EnumSet.of(PREPARING, CANCELLED));
        allowed.put(PREPARING, EnumSet.of(
                BUILDING, FAILED_RETRYABLE, FAILED_NEEDS_HUMAN, SKIPPED_DOCS_ONLY, CANCELLED));
        allowed.put(BUILDING, EnumSet.of(STATIC_CHECKING, FAILED_RETRYABLE, FAILED_NEEDS_HUMAN, CANCELLED));
        allowed.put(STATIC_CHECKING, EnumSet.of(SUCCEEDED, FAILED_RETRYABLE, FAILED_NEEDS_HUMAN, CANCELLED));
        return Map.copyOf(allowed);
    }
}
