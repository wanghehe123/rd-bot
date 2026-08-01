package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkCase;
import java.util.List;

/**
 * Server-side port for the frozen runtime descriptor that maps each benchmark case to its
 * container inputs.
 *
 * <p>Unlike {@link CodingBenchmarkCatalogPort} (which exposes only snapshotId / caseCount / digest to
 * web-facing clients), this port returns host paths, image digests, commands and timeouts needed to
 * construct {@link com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest}. It is never
 * called by admin controllers and is never serialised into web responses.
 *
 * @see com.wish.rd.engine.evaluation.impl.CodingBenchmarkRuntimeCatalogAdapter
 */
public interface CodingBenchmarkRuntimeCatalogPort {

    /**
     * Returns the runtime descriptor for every case in a frozen snapshot.
     *
     * @param snapshotId a snapshot that has passed {@code FileSystemCodingBenchmarkCatalog} readiness
     * @return one entry per case, in the same order as the snapshot's dataset-manifest
     * @throws java.util.NoSuchElementException if the snapshot does not exist or is not ready
     */
    List<CodingBenchmarkCaseRuntime> runtimeForSnapshot(String snapshotId);
}
