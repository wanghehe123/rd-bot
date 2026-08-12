package com.wish.rd.engine.provider;

import com.wish.rd.engine.provider.model.ProviderToolOperation;

import java.util.Optional;

/**
 * Persistence port for Host-owned provider tool-operation evidence.
 *
 * <p>Provider fallback policy reads this port rather than accepting a container workspace or
 * executor metadata as proof that side effects are settled.
 */
public interface ProviderToolOperationStore {

    /**
     * Persists an authoritative operation observation.
     *
     * @param operation immutable Host-owned operation evidence
     * @return the durable observation
     */
    ProviderToolOperation record(ProviderToolOperation operation);

    /**
     * Finds the newest Host-owned operation evidence for one task-stage boundary.
     *
     * @param taskId workflow task id
     * @param stageRunId stage-run id
     * @return newest operation evidence when it exists
     */
    Optional<ProviderToolOperation> findLatestByTaskAndStageRun(String taskId, String stageRunId);

    /**
     * Returns a fail-closed store for runtimes without an authoritative ledger.
     *
     * @return store that never reports operation authority
     */
    static ProviderToolOperationStore unavailable() {
        return new ProviderToolOperationStore() {
            @Override
            public ProviderToolOperation record(ProviderToolOperation operation) {
                throw new IllegalStateException("provider tool-operation ledger is unavailable");
            }

            @Override
            public Optional<ProviderToolOperation> findLatestByTaskAndStageRun(
                    String taskId,
                    String stageRunId
            ) {
                return Optional.empty();
            }
        };
    }
}
