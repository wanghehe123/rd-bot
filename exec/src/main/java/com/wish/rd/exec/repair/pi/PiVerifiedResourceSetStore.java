package com.wish.rd.exec.repair.pi;

import com.wish.rd.exec.repair.pi.model.VerifiedPiResourceSet;
import java.util.Optional;

/** Reads only platform-published verified resource sets; it does not accept request paths or URLs. */
@FunctionalInterface
public interface PiVerifiedResourceSetStore {

    Optional<VerifiedPiResourceSet> find(String setId, long version);

    static PiVerifiedResourceSetStore empty() {
        return (setId, version) -> Optional.empty();
    }
}
