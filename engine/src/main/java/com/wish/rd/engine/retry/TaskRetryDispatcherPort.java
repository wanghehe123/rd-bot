package com.wish.rd.engine.retry;

import com.wish.rd.engine.retry.model.TaskRetryPoint;

/** Durable dispatch boundary invoked after a retry checkpoint has been persisted. */
@FunctionalInterface
public interface TaskRetryDispatcherPort {

    void dispatch(String taskId, TaskRetryPoint retryPoint);

    /** Wakes the already-persisted first command of one exact checkpoint generation. */
    default void dispatchCheckpoint(String checkpointId) {
        throw new UnsupportedOperationException("checkpoint-bound retry dispatch is not configured: " + checkpointId);
    }
}
