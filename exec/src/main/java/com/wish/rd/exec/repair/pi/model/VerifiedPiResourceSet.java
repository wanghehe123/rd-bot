package com.wish.rd.exec.repair.pi.model;

import java.util.List;

/** Immutable H1 resource-set lookup result. */
public record VerifiedPiResourceSet(
        String setId,
        long version,
        List<VerifiedPiResource> resources
) {

    public VerifiedPiResourceSet {
        setId = setId == null ? "" : setId.strip();
        version = Math.max(0L, version);
        resources = resources == null ? List.of() : List.copyOf(resources);
    }
}
