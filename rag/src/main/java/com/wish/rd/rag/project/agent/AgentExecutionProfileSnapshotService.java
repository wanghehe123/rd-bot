package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;

import java.util.Objects;

/** Enforces append-only, hash-verified snapshot resolution for a stage. */
public final class AgentExecutionProfileSnapshotService {

    private final AgentExecutionProfileSnapshotStore store;

    public AgentExecutionProfileSnapshotService(AgentExecutionProfileSnapshotStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public AgentExecutionProfileSnapshot resolveOrSave(AgentExecutionProfileSnapshot requested) {
        if (requested == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (!requested.hasValidIntegrityHash()) {
            throw new IllegalArgumentException("snapshot integrity hash does not match snapshotJson");
        }
        AgentExecutionProfileSnapshot stored = store.saveIfAbsent(requested);
        if (!stored.snapshotHash().equals(requested.snapshotHash())) {
            throw new IllegalStateException(
                    "stage already has a different execution profile snapshot: " + requested.stageRunId()
            );
        }
        if (!stored.hasValidIntegrityHash()) {
            throw new IllegalStateException(
                    "stored execution profile snapshot is corrupt: " + requested.stageRunId()
            );
        }
        return stored;
    }
}
