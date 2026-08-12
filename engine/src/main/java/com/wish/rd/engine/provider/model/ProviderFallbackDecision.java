package com.wish.rd.engine.provider.model;

/**
 * Host decision for a candidate provider fallback.
 */
public enum ProviderFallbackDecision {
    /** Fallback is allowed for this failure class and work risk. */
    ALLOW,
    /** Capability missing or policy requires explicit approval. */
    WAITING_POLICY,
    /** Must not silently degrade; human confirmation required. */
    NEEDS_HUMAN
}
