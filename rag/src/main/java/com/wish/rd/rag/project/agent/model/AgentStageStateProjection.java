package com.wish.rd.rag.project.agent.model;

/** Latest independently-versioned state and actual-context injection for one stage attempt. */
public record AgentStageStateProjection(
        AgentStageStateIdentity identity,
        long stateSequence,
        String stateHash,
        String stateJson,
        long stateProjectedAtEpochMillis,
        long injectionSequence,
        long injectedStateSequence,
        String injectedStateHash,
        String promptHash,
        String injectedBlockHash,
        String injectedBlock,
        String injectionIdempotencyKey,
        long injectedAtEpochMillis,
        boolean finalized,
        long finalizedAtEpochMillis
) {
    public AgentStageStateProjection {
        if (identity == null) throw new IllegalArgumentException("identity must not be null");
        stateHash = normalize(stateHash);
        stateJson = normalize(stateJson);
        injectedStateHash = normalize(injectedStateHash);
        promptHash = normalize(promptHash);
        injectedBlockHash = normalize(injectedBlockHash);
        injectedBlock = normalize(injectedBlock);
        injectionIdempotencyKey = normalize(injectionIdempotencyKey);
    }

    public static AgentStageStateProjection empty(AgentStageStateIdentity identity) {
        return new AgentStageStateProjection(
                identity, -1L, "", "", 0L,
                0L, -1L, "", "", "", "", "", 0L,
                false, 0L
        );
    }

    public AgentStageStateProjection withState(AgentStateProjectionUpdate update) {
        return new AgentStageStateProjection(
                identity, update.stateSequence(), update.stateHash(), update.stateJson(),
                update.projectedAtEpochMillis(), injectionSequence, injectedStateSequence,
                injectedStateHash, promptHash, injectedBlockHash, injectedBlock,
                injectionIdempotencyKey, injectedAtEpochMillis, finalized, finalizedAtEpochMillis
        );
    }

    public AgentStageStateProjection withInjection(AgentContextInjectionProjectionUpdate update) {
        return new AgentStageStateProjection(
                identity, stateSequence, stateHash, stateJson, stateProjectedAtEpochMillis,
                update.injectionSequence(), update.stateSequence(), update.stateHash(), update.promptHash(),
                update.injectedBlockHash(), update.injectedBlock(), update.idempotencyKey(),
                update.injectedAtEpochMillis(), finalized, finalizedAtEpochMillis
        );
    }

    public AgentStageStateProjection finalizeAt(long epochMillis) {
        if (epochMillis < 1) throw new IllegalArgumentException("finalizedAtEpochMillis must be positive");
        return new AgentStageStateProjection(
                identity, stateSequence, stateHash, stateJson, stateProjectedAtEpochMillis,
                injectionSequence, injectedStateSequence, injectedStateHash, promptHash,
                injectedBlockHash, injectedBlock, injectionIdempotencyKey, injectedAtEpochMillis,
                true, epochMillis
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
