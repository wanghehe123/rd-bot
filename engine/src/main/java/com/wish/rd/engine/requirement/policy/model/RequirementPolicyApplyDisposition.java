package com.wish.rd.engine.requirement.policy.model;

/**
 * Host-owned terminal disposition produced when a durable {@code POLICY_APPLY} command consumes
 * a decided policy generation.
 */
public enum RequirementPolicyApplyDisposition {
    /** The policy permits the first requirement-delivery role to start. */
    ALLOWED,
    /** The policy requires a separately authorized approval before delivery can resume. */
    WAITING_APPROVAL,
    /** The policy stopped the task for human intervention. */
    DENIED
}
