package com.wish.rd.bootstrap.retry.impl;

import com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService;
import com.wish.rd.engine.retry.TaskRetryDispatcherPort;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import org.springframework.stereotype.Component;

/** Re-enqueues a persisted retry checkpoint through the durable requirement delivery worker. */
@Component
public final class RequirementTaskRetryDispatcherAdapter implements TaskRetryDispatcherPort {

    private final RequirementDeliveryDispatchService dispatchService;

    public RequirementTaskRetryDispatcherAdapter(RequirementDeliveryDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Override
    public void dispatch(String taskId, TaskRetryPoint retryPoint) {
        dispatchService.submit(taskId);
    }
}
