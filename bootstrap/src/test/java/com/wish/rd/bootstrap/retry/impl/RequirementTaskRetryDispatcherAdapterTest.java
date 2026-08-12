package com.wish.rd.bootstrap.retry.impl;

import com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementTaskRetryDispatcherAdapterTest {

    @Test
    void schedulesOnlyTheCheckpointBoundDispatchCommand() {
        RequirementDeliveryDispatchService service = mock(RequirementDeliveryDispatchService.class);
        TaskRetryCheckpointStore checkpoints = mock(TaskRetryCheckpointStore.class);
        when(checkpoints.find("101")).thenReturn(Optional.of(dispatchedCheckpoint()));
        RequirementTaskRetryDispatcherAdapter adapter =
                new RequirementTaskRetryDispatcherAdapter(service, checkpoints);

        adapter.dispatchCheckpoint("101");

        verify(service).schedulePersistedCommand("202");
    }

    private static TaskRetryCheckpoint dispatchedCheckpoint() {
        return new TaskRetryCheckpoint("101", "task-1", TaskFailurePhase.MATERIAL, null,
                "", "", "", 1, "retry-key", RdTaskStatus.FAILED_NEEDS_HUMAN, 12L,
                400L, "17", "MATERIAL_COLLECTING", "", "", "", "", "", 13L, 401L, "202", 101L,
                "", List.of(), TaskRetryCheckpointStatus.DISPATCHED, "failed", "", 1L, 2L);
    }
}
