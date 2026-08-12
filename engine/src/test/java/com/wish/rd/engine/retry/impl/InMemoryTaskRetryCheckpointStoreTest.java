package com.wish.rd.engine.retry.impl;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ensures memory replay preserves PostgreSQL's fail-closed checkpoint source identity. */
class InMemoryTaskRetryCheckpointStoreTest {

    @Test
    void replayAcceptsWinnerOwnedIdButRejectsDifferentRouteInput() {
        InMemoryTaskRetryCheckpointStore store = new InMemoryTaskRetryCheckpointStore();
        TaskRetryCheckpoint winner = checkpoint("101", "operator note");
        assertEquals(true, store.createOrGet(winner).created());
        assertEquals("101", store.createOrGet(checkpoint("202", "operator note")).checkpoint().checkpointId());
        assertThrows(IllegalStateException.class, () -> store.createOrGet(checkpoint("303", "other note")));
    }

    @Test
    void rejectsASecondActiveCheckpointForTheSameTask() {
        InMemoryTaskRetryCheckpointStore store = new InMemoryTaskRetryCheckpointStore();
        store.createOrGet(checkpoint("101", "first"));

        assertThrows(IllegalStateException.class, () -> store.createOrGet(
                checkpoint("202", "second", "different-key")));
    }

    private static TaskRetryCheckpoint checkpoint(String id, String note) {
        return checkpoint(id, note, "same-key");
    }

    private static TaskRetryCheckpoint checkpoint(String id, String note, String idempotencyKey) {
        return new TaskRetryCheckpoint(id, "9001", TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                "7001", "", "", 1, idempotencyKey, RdTaskStatus.FAILED_NEEDS_HUMAN, 12L,
                note, List.of("7001"), TaskRetryCheckpointStatus.CREATED, "failed", "", 1L, 1L);
    }
}
