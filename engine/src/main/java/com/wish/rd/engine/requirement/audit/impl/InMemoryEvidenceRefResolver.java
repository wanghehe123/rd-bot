package com.wish.rd.engine.requirement.audit.impl;

import com.wish.rd.engine.requirement.audit.EvidenceRefResolverPort;
import com.wish.rd.engine.requirement.audit.EvidenceRef;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Test/in-memory evidence index keyed by URI. */
public final class InMemoryEvidenceRefResolver implements EvidenceRefResolverPort {

    private final ConcurrentMap<String, EvidenceRef> artifacts = new ConcurrentHashMap<>();

    /**
     * Registers a persisted artifact.
     *
     * @param ref evidence to expose
     */
    public void put(EvidenceRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("evidence ref must not be null");
        }
        artifacts.put(ref.uri(), ref);
    }

    @Override
    public void requireResolvable(EvidenceRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("evidence ref must not be null");
        }
        if (!artifacts.containsKey(ref.uri())) {
            throw new IllegalStateException("dangling evidence ref: " + ref.uri());
        }
    }
}
