package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkSnapshot;

import java.util.List;
import java.util.Optional;

/** Exposes only server-validated, immutable coding benchmark snapshots. */
public interface CodingBenchmarkCatalogPort {

    /** @return ready snapshots with redacted display metadata only. */
    List<CodingBenchmarkSnapshot> readySnapshots();

    /** Returns a ready snapshot by its safe server-owned ID. */
    default Optional<CodingBenchmarkSnapshot> findReady(String snapshotId) {
        return readySnapshots().stream().filter(snapshot -> snapshot.snapshotId().equals(snapshotId)).findFirst();
    }
}
