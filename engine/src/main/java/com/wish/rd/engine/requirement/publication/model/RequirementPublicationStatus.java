package com.wish.rd.engine.requirement.publication.model;

/**
 * External publication ledger states for branch push and GitHub PR side effects.
 */
public enum RequirementPublicationStatus {
    PREPARED,
    BRANCH_CONFIRMED,
    PR_CONFIRMED,
    COMMITTED,
    UNKNOWN_REMOTE_RESULT,
    NEEDS_HUMAN
}
