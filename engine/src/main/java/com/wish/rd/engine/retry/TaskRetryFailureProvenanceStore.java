package com.wish.rd.engine.retry;

import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.util.Optional;

/** Reads and writes the structured failure identity written atomically with a terminal task snapshot. */
public interface TaskRetryFailureProvenanceStore {

    Optional<TaskRetryFailureProvenance> findExact(
            String taskId,
            RdTaskStatus failedTaskStatus,
            long failedTaskVersion,
            long failedTaskFencingToken
    );

    /**
     * Inserts one immutable provenance row or returns the identical existing row.
     *
     * <p>The default keeps read-only adapters source-compatible until a writer overrides it.
     *
     * @param provenance immutable failure provenance
     * @return persisted provenance
     * @throws UnsupportedOperationException until a concrete writer overrides this default
     * @throws IllegalStateException when an existing row conflicts with the requested identity
     */
    default TaskRetryFailureProvenance save(TaskRetryFailureProvenance provenance) {
        String provenanceId = provenance == null ? "" : provenance.provenanceId();
        throw new UnsupportedOperationException("failure provenance write is not configured: " + provenanceId);
    }

    /** Whether absence is authoritative rather than a legacy/test configuration without this store. */
    default boolean authoritative() {
        return true;
    }

    static TaskRetryFailureProvenanceStore unavailable() {
        return new TaskRetryFailureProvenanceStore() {
            @Override
            public Optional<TaskRetryFailureProvenance> findExact(
                    String taskId,
                    RdTaskStatus failedTaskStatus,
                    long failedTaskVersion,
                    long failedTaskFencingToken
            ) {
                return Optional.empty();
            }

            @Override
            public boolean authoritative() {
                return false;
            }
        };
    }
}
