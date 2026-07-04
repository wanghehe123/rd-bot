package com.wish.rd.engine.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryAgentStageRunStoreTest {

    @Test
    void shouldSaveListAndRejectDuplicateIdempotencyKey() {
        AgentStageRunStore store = new InMemoryAgentStageRunStore();
        AgentStageRun first = AgentStageRun.pending(
                "stage-1",
                "task-1001",
                AgentRole.REQUIREMENT_REVIEWER,
                1,
                "task-1001:REQUIREMENT_REVIEWER:1",
                1_783_000_000_000L
        );
        AgentStageRun duplicate = AgentStageRun.pending(
                "stage-2",
                "task-1001",
                AgentRole.REQUIREMENT_REVIEWER,
                1,
                "task-1001:REQUIREMENT_REVIEWER:1",
                1_783_000_000_001L
        );

        assertEquals(first, store.save(first));
        assertEquals(List.of(first), store.listByTask("task-1001"));
        assertThrows(IllegalStateException.class, () -> store.save(duplicate));
    }

    @Test
    void shouldProtectStageStatusTransitions() {
        AgentStageRunStore store = new InMemoryAgentStageRunStore();
        AgentStageRun run = store.save(AgentStageRun.pending(
                "stage-1",
                "task-1001",
                AgentRole.CODING_AGENT,
                1,
                "task-1001:CODING_AGENT:1",
                1_783_000_000_000L
        ));

        AgentStageRun contextReady = store.transition(
                run.stageRunId(),
                AgentStageStatus.CONTEXT_READY,
                "",
                "",
                1_783_000_000_010L
        );

        assertEquals(AgentStageStatus.CONTEXT_READY, contextReady.status());
        assertThrows(IllegalStateException.class, () -> store.transition(
                run.stageRunId(),
                AgentStageStatus.SUCCEEDED,
                "",
                "",
                1_783_000_000_020L
        ));
    }
}
