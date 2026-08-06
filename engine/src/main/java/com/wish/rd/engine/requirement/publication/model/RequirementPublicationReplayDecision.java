package com.wish.rd.engine.requirement.publication.model;

/**
 * Replay guidance for an existing publication operation.
 */
public enum RequirementPublicationReplayDecision {
    ALLOW_PUSH,
    ALLOW_CREATE_PULL_REQUEST,
    REUSE_BRANCH,
    REUSE_PULL_REQUEST,
    WAIT_RECONCILE,
    NEEDS_HUMAN
}
