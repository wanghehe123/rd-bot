package com.wish.rd.engine.requirement.audit;

/** Resolves {@link EvidenceRef} URIs to persisted Host artifacts. */
public interface EvidenceRefResolverPort {

    /**
     * Confirms the URI points at an already-persisted artifact.
     *
     * @param ref evidence reference
     * @throws IllegalStateException when the URI cannot be resolved
     */
    void requireResolvable(EvidenceRef ref);
}
