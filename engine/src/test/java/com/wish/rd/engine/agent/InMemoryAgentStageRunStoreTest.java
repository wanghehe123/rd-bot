package com.wish.rd.engine.agent;

import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;

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

    @Test
    void shouldStampLifecycleTimestampsWhenStageStartsAndStops() {
        AgentStageRunStore store = new InMemoryAgentStageRunStore();
        AgentStageRun run = store.save(AgentStageRun.pending(
                "stage-2",
                "task-2001",
                AgentRole.CODING_AGENT,
                1,
                "task-2001:CODING_AGENT:1",
                1_783_000_000_000L
        ));

        store.transition(run.stageRunId(), AgentStageStatus.CONTEXT_READY, "", "", 1_783_000_000_010L);
        store.transition(run.stageRunId(), AgentStageStatus.DISPATCHING, "", "", 1_783_000_000_020L);
        AgentStageRun running = store.transition(
                run.stageRunId(),
                AgentStageStatus.RUNNING,
                "",
                "",
                1_783_000_000_050L
        );
        AgentStageRun collecting = store.transition(
                run.stageRunId(),
                AgentStageStatus.RESULT_COLLECTING,
                "",
                "",
                1_783_000_000_070L
        );
        store.transition(run.stageRunId(), AgentStageStatus.VERIFYING, "", "", 1_783_000_000_080L);
        AgentStageRun succeeded = store.transition(
                run.stageRunId(),
                AgentStageStatus.SUCCEEDED,
                "",
                "",
                1_783_000_000_110L
        );
        AgentStageRun withMetadata = succeeded.withProviderMetadata(
                "long-cat",
                "[{\"provider\":\"long-cat\",\"durationMillis\":60}]",
                1_783_000_000_120L
        );

        assertEquals(1_783_000_000_050L, running.startedAtEpochMillis());
        assertEquals(0L, running.finishedAtEpochMillis());
        assertEquals(1_783_000_000_050L, collecting.startedAtEpochMillis());
        assertEquals(0L, collecting.finishedAtEpochMillis());
        assertEquals(1_783_000_000_050L, succeeded.startedAtEpochMillis());
        assertEquals(1_783_000_000_110L, succeeded.finishedAtEpochMillis());
        assertEquals(1_783_000_000_050L, withMetadata.startedAtEpochMillis());
        assertEquals(1_783_000_000_110L, withMetadata.finishedAtEpochMillis());
    }
}
