package com.wish.rd.rag.project.agent.model;

/** Freshness policy for a role execution fact. */
public enum FactFreshnessPolicy {
    SAME_REVISION,
    SAME_WORKSPACE,
    TTL,
    ALWAYS_RECHECK
}
