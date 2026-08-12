package com.wish.rd.engine.requirement.publication;

import com.wish.rd.engine.requirement.publication.model.RequirementPublicationContinuation;

/**
 * Persists bounded delivery work after remote publication reconciliation.
 *
 * <p>Implementations must make the operation-derived stage identity idempotent across scheduler
 * instances. They only enqueue work; normal stage-command claiming remains the dispatcher's
 * responsibility.
 */
@FunctionalInterface
public interface RequirementPublicationContinuationPort {

    /** Persists or reuses the continuation for one reconciled publication operation. */
    void enqueue(RequirementPublicationContinuation continuation);
}
