package com.wish.rd.bootstrap.retry.impl;

import com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService;
import com.wish.rd.engine.retry.TaskRetryDispatcherPort;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import org.springframework.stereotype.Component;

/** Re-enqueues a persisted retry checkpoint through the durable requirement delivery worker. */
@Component
public final class RequirementTaskRetryDispatcherAdapter implements TaskRetryDispatcherPort {

    private final RequirementDeliveryDispatchService dispatchService;
    private final TaskRetryCheckpointStore checkpointStore;

    public RequirementTaskRetryDispatcherAdapter(
            RequirementDeliveryDispatchService dispatchService,
            TaskRetryCheckpointStore checkpointStore
    ) {
        this.dispatchService = dispatchService;
        this.checkpointStore = checkpointStore;
    }

    @Override
    public void dispatch(String taskId, TaskRetryPoint retryPoint) {
        throw new IllegalStateException("retry dispatch requires an exact durable checkpoint route: " + taskId);
    }

    @Override
    public void dispatchCheckpoint(String checkpointId) {
        TaskRetryCheckpoint checkpoint = checkpointStore.find(checkpointId)
                .orElseThrow(() -> new IllegalStateException("retry checkpoint not found: " + checkpointId));
        if (checkpoint.status() != TaskRetryCheckpointStatus.DISPATCHED
                || checkpoint.dispatchCommandId().isBlank()) {
            throw new IllegalStateException("retry checkpoint has no dispatched command: " + checkpointId);
        }
        dispatchService.schedulePersistedCommand(checkpoint.dispatchCommandId());
    }
}
