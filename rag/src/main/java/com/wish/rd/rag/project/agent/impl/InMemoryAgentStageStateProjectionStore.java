package com.wish.rd.rag.project.agent.impl;

import com.wish.rd.rag.project.agent.AgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.model.AgentContextInjectionProjectionUpdate;
import com.wish.rd.rag.project.agent.model.AgentStageProjectionWriteResult;
import com.wish.rd.rag.project.agent.model.AgentStageProjectionWriteStatus;
import com.wish.rd.rag.project.agent.model.AgentStageStateIdentity;
import com.wish.rd.rag.project.agent.model.AgentStageStateProjection;
import com.wish.rd.rag.project.agent.model.AgentStateProjectionUpdate;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe in-memory CAS implementation used by tests and local wiring. */
public final class InMemoryAgentStageStateProjectionStore implements AgentStageStateProjectionStore {

    private final ConcurrentHashMap<String, AgentStageStateProjection> values = new ConcurrentHashMap<>();

    @Override
    public AgentStageProjectionWriteResult projectState(AgentStateProjectionUpdate update) {
        if (update == null) throw new IllegalArgumentException("update must not be null");
        AtomicReference<AgentStageProjectionWriteResult> result = new AtomicReference<>();
        values.compute(update.identity().stageRunId(), (ignored, currentValue) -> {
            AgentStageStateProjection current = currentValue == null
                    ? AgentStageStateProjection.empty(update.identity()) : currentValue;
            if (!current.identity().equals(update.identity())) {
                result.set(rejected(AgentStageProjectionWriteStatus.REJECTED_IDENTITY, current, "identity conflict"));
                return current;
            }
            if (update.stateSequence() == current.stateSequence()) {
                if (current.stateHash().equals(update.stateHash())
                        && current.stateJson().equals(update.stateJson())) {
                    result.set(accepted(AgentStageProjectionWriteStatus.IDEMPOTENT, current));
                } else {
                    result.set(rejected(AgentStageProjectionWriteStatus.REJECTED_CONFLICT, current,
                            "same state sequence has conflicting content"));
                }
                return current;
            }
            if (current.finalized()) {
                result.set(rejected(AgentStageProjectionWriteStatus.REJECTED_FINALIZED, current,
                        "projection is finalized"));
                return current;
            }
            if (update.stateSequence() < current.stateSequence()) {
                result.set(rejected(AgentStageProjectionWriteStatus.REJECTED_STALE, current,
                        "state sequence regressed"));
                return current;
            }
            AgentStageStateProjection next = current.withState(update);
            result.set(accepted(AgentStageProjectionWriteStatus.APPLIED, next));
            return next;
        });
        return result.get();
    }

    @Override
    public AgentStageProjectionWriteResult projectInjection(AgentContextInjectionProjectionUpdate update) {
        if (update == null) throw new IllegalArgumentException("update must not be null");
        AtomicReference<AgentStageProjectionWriteResult> result = new AtomicReference<>();
        values.compute(update.identity().stageRunId(), (ignored, currentValue) -> {
            AgentStageStateProjection current = currentValue == null
                    ? AgentStageStateProjection.empty(update.identity()) : currentValue;
            if (!current.identity().equals(update.identity())) {
                result.set(rejected(AgentStageProjectionWriteStatus.REJECTED_IDENTITY, current, "identity conflict"));
                return current;
            }
            if (update.injectionSequence() == current.injectionSequence()) {
                if (sameInjection(current, update)) {
                    result.set(accepted(AgentStageProjectionWriteStatus.IDEMPOTENT, current));
                } else {
                    result.set(rejected(AgentStageProjectionWriteStatus.REJECTED_CONFLICT, current,
                            "same injection sequence has conflicting content"));
                }
                return current;
            }
            if (current.finalized()) {
                result.set(rejected(AgentStageProjectionWriteStatus.REJECTED_FINALIZED, current,
                        "projection is finalized"));
                return current;
            }
            if (update.injectionSequence() < current.injectionSequence()) {
                result.set(rejected(AgentStageProjectionWriteStatus.REJECTED_STALE, current,
                        "injection sequence regressed"));
                return current;
            }
            AgentStageStateProjection next = current.withInjection(update);
            result.set(accepted(AgentStageProjectionWriteStatus.APPLIED, next));
            return next;
        });
        return result.get();
    }

    @Override
    public AgentStageProjectionWriteResult finalizeProjection(
            AgentStageStateIdentity identity,
            long finalizedAtEpochMillis
    ) {
        if (identity == null) throw new IllegalArgumentException("identity must not be null");
        AtomicReference<AgentStageProjectionWriteResult> result = new AtomicReference<>();
        values.compute(identity.stageRunId(), (ignored, currentValue) -> {
            AgentStageStateProjection current = currentValue == null
                    ? AgentStageStateProjection.empty(identity) : currentValue;
            if (!current.identity().equals(identity)) {
                result.set(rejected(AgentStageProjectionWriteStatus.REJECTED_IDENTITY, current, "identity conflict"));
                return current;
            }
            if (current.finalized()) {
                result.set(accepted(AgentStageProjectionWriteStatus.IDEMPOTENT, current));
                return current;
            }
            AgentStageStateProjection next = current.finalizeAt(finalizedAtEpochMillis);
            result.set(accepted(AgentStageProjectionWriteStatus.APPLIED, next));
            return next;
        });
        return result.get();
    }

    @Override
    public Optional<AgentStageStateProjection> findByStageRunId(String stageRunId) {
        if (stageRunId == null || stageRunId.isBlank()) return Optional.empty();
        return Optional.ofNullable(values.get(stageRunId.strip()));
    }

    private static boolean sameInjection(
            AgentStageStateProjection current,
            AgentContextInjectionProjectionUpdate update
    ) {
        return current.injectedStateSequence() == update.stateSequence()
                && current.injectedStateHash().equals(update.stateHash())
                && current.promptHash().equals(update.promptHash())
                && current.injectedBlockHash().equals(update.injectedBlockHash())
                && current.injectedBlock().equals(update.injectedBlock())
                && current.injectionIdempotencyKey().equals(update.idempotencyKey());
    }

    private static AgentStageProjectionWriteResult accepted(
            AgentStageProjectionWriteStatus status,
            AgentStageStateProjection projection
    ) {
        return new AgentStageProjectionWriteResult(status, projection, "");
    }

    private static AgentStageProjectionWriteResult rejected(
            AgentStageProjectionWriteStatus status,
            AgentStageStateProjection projection,
            String reason
    ) {
        return new AgentStageProjectionWriteResult(status, projection, reason);
    }
}
