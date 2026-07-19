package com.wish.rd.engine.retry;

import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for idempotent task retry checkpoints. */
public interface TaskRetryCheckpointStore {

    CreateResult createOrGet(TaskRetryCheckpoint checkpoint);

    Optional<TaskRetryCheckpoint> find(String checkpointId);

    Optional<TaskRetryCheckpoint> findActiveByTask(String taskId);

    List<TaskRetryCheckpoint> listByTask(String taskId);

    TaskRetryCheckpoint transition(
            String checkpointId,
            TaskRetryCheckpointStatus expectedStatus,
            TaskRetryCheckpointStatus targetStatus,
            String errorMessage,
            long nowEpochMillis
    );

    record CreateResult(TaskRetryCheckpoint checkpoint, boolean created) {
    }
}
