package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.impl.InMemoryAgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.model.AgentContextInjectionProjectionUpdate;
import com.wish.rd.rag.project.agent.model.AgentStageProjectionWriteStatus;
import com.wish.rd.rag.project.agent.model.AgentStageStateIdentity;
import com.wish.rd.rag.project.agent.model.AgentStateProjectionUpdate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStageStateProjectionStoreTest {

    private static final String HASH_A = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final AgentStageStateIdentity IDENTITY = new AgentStageStateIdentity(
            "task-1", "stage-1", "CODING_AGENT", 1
    );

    @Test
    void keepsStateAndInjectionSequencesIndependentWithIdempotentCas() {
        AgentStageStateProjectionStore store = new InMemoryAgentStageStateProjectionStore();

        assertEquals(AgentStageProjectionWriteStatus.APPLIED, store.projectState(
                state(0, HASH_A, "{\"sequence\":0}", 100L)
        ).status());
        assertEquals(AgentStageProjectionWriteStatus.IDEMPOTENT, store.projectState(
                state(0, HASH_A, "{\"sequence\":0}", 100L)
        ).status());
        assertEquals(AgentStageProjectionWriteStatus.REJECTED_CONFLICT, store.projectState(
                state(0, HASH_B, "{\"sequence\":0}", 101L)
        ).status());
        assertEquals(AgentStageProjectionWriteStatus.APPLIED, store.projectState(
                state(2, HASH_B, "{\"sequence\":2}", 200L)
        ).status());
        assertEquals(AgentStageProjectionWriteStatus.REJECTED_STALE, store.projectState(
                state(1, HASH_A, "{\"sequence\":1}", 150L)
        ).status());

        assertEquals(AgentStageProjectionWriteStatus.APPLIED, store.projectInjection(
                injection(1, 2, HASH_B, HASH_C, 210L)
        ).status());
        assertEquals(AgentStageProjectionWriteStatus.IDEMPOTENT, store.projectInjection(
                injection(1, 2, HASH_B, HASH_C, 210L)
        ).status());
        assertEquals(AgentStageProjectionWriteStatus.REJECTED_CONFLICT, store.projectInjection(
                injection(1, 2, HASH_B, HASH_A, 211L)
        ).status());
        assertEquals(AgentStageProjectionWriteStatus.APPLIED, store.projectInjection(
                injection(3, 2, HASH_B, HASH_A, 300L)
        ).status());
        assertEquals(AgentStageProjectionWriteStatus.REJECTED_STALE, store.projectInjection(
                injection(2, 2, HASH_B, HASH_C, 250L)
        ).status());

        var projection = store.findByStageRunId("stage-1").orElseThrow();
        assertEquals(2L, projection.stateSequence());
        assertEquals(3L, projection.injectionSequence());
        assertEquals(2L, projection.injectedStateSequence());
        assertFalse(projection.finalized());
    }

    @Test
    void rejectsIdentityConflictsAndNewWritesAfterFinalization() {
        AgentStageStateProjectionStore store = new InMemoryAgentStageStateProjectionStore();
        assertEquals(AgentStageProjectionWriteStatus.APPLIED, store.projectState(
                state(0, HASH_A, "{\"sequence\":0}", 100L)
        ).status());
        AgentStageStateIdentity conflicting = new AgentStageStateIdentity(
                "wrong-task", "stage-1", "CODING_AGENT", 1
        );
        assertEquals(AgentStageProjectionWriteStatus.REJECTED_IDENTITY, store.projectState(
                new AgentStateProjectionUpdate(conflicting, 1L, HASH_B, "{\"sequence\":1}", 200L)
        ).status());

        assertEquals(AgentStageProjectionWriteStatus.APPLIED,
                store.finalizeProjection(IDENTITY, 500L).status());
        assertTrue(store.findByStageRunId("stage-1").orElseThrow().finalized());
        assertEquals(AgentStageProjectionWriteStatus.IDEMPOTENT,
                store.finalizeProjection(IDENTITY, 500L).status());
        assertEquals(AgentStageProjectionWriteStatus.REJECTED_FINALIZED, store.projectState(
                state(1, HASH_B, "{\"sequence\":1}", 600L)
        ).status());
        assertEquals(AgentStageProjectionWriteStatus.REJECTED_FINALIZED, store.projectInjection(
                injection(1, 0, HASH_A, HASH_C, 600L)
        ).status());
    }

    private static AgentStateProjectionUpdate state(long sequence, String hash, String json, long at) {
        return new AgentStateProjectionUpdate(IDENTITY, sequence, hash, json, at);
    }

    private static AgentContextInjectionProjectionUpdate injection(
            long injectionSequence,
            long stateSequence,
            String stateHash,
            String blockHash,
            long at
    ) {
        return new AgentContextInjectionProjectionUpdate(
                IDENTITY,
                injectionSequence,
                stateSequence,
                stateHash,
                HASH_A,
                blockHash,
                "<rd-agent-state>safe</rd-agent-state>",
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                at
        );
    }
}
