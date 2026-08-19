package com.wish.rd.rag.project.agent.model;

/** One independently monotonic actual model-context injection projection update. */
public record AgentContextInjectionProjectionUpdate(
        AgentStageStateIdentity identity,
        long injectionSequence,
        long stateSequence,
        String stateHash,
        String promptHash,
        String injectedBlockHash,
        String injectedBlock,
        String idempotencyKey,
        long injectedAtEpochMillis
) {
    public AgentContextInjectionProjectionUpdate {
        if (identity == null) throw new IllegalArgumentException("identity must not be null");
        if (injectionSequence < 1) {
            throw new IllegalArgumentException("injectionSequence must be positive");
        }
        if (stateSequence < 0) throw new IllegalArgumentException("stateSequence must not be negative");
        stateHash = AgentStateProjectionUpdate.requireHash(stateHash, "stateHash");
        promptHash = AgentStateProjectionUpdate.requireHash(promptHash, "promptHash");
        injectedBlockHash = AgentStateProjectionUpdate.requireHash(injectedBlockHash, "injectedBlockHash");
        injectedBlock = AgentStateProjectionUpdate.requireText(injectedBlock, "injectedBlock");
        idempotencyKey = AgentStateProjectionUpdate.requireHash(idempotencyKey, "idempotencyKey");
        if (injectedAtEpochMillis < 1) {
            throw new IllegalArgumentException("injectedAtEpochMillis must be positive");
        }
    }
}
