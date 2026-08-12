package com.wish.rd.engine.requirement.policy.model;

/**
 * Authoritative lifecycle of one immutable requirement-policy ledger generation.
 *
 * <p>The policy ledger, rather than a task timeline event or execution result, owns these states.
 */
public enum RequirementPolicyRunState {
    PLAN_READY,
    POLICY_DECIDED,
    WAITING_APPROVAL,
    APPROVED,
    APPLIED,
    DENIED,
    SUPERSEDED;

    /** Returns whether this generation occupies the task's sole active-policy slot. */
    public boolean isActive() {
        return this == PLAN_READY || this == POLICY_DECIDED
                || this == WAITING_APPROVAL || this == APPROVED;
    }

    /** Returns whether an authorized POLICY retry may terminalize this active generation. */
    public boolean canBeSuperseded() {
        return isActive();
    }
}
