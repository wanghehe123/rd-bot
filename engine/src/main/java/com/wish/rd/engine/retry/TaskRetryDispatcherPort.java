package com.wish.rd.engine.retry;

import com.wish.rd.engine.retry.model.TaskRetryPoint;

/** Durable dispatch boundary invoked after a retry checkpoint has been persisted. */
@FunctionalInterface
public interface TaskRetryDispatcherPort {

    void dispatch(String taskId, TaskRetryPoint retryPoint);
}
