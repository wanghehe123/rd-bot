package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Dispatches long-running requirement delivery workflows onto the dedicated
 * requirement-delivery pool.
 *
 * <p>HTTP and Feishu event adapters should enqueue via this service instead of
 * running the whole multi-agent chain on request or callback threads.
 */
@Service
public class RequirementDeliveryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(RequirementDeliveryDispatchService.class);

    private final RequirementDeliveryEngine deliveryEngine;
    private final AsyncTaskExecutor executor;

    /**
     * Creates the requirement delivery dispatcher.
     *
     * @param deliveryEngine requirement delivery orchestrator
     * @param executor       dedicated requirement-delivery executor
     */
    public RequirementDeliveryDispatchService(
            RequirementDeliveryEngine deliveryEngine,
            @Qualifier(RdBotThreadPoolConfiguration.REQUIREMENT_DELIVERY_EXECUTOR_BEAN)
            AsyncTaskExecutor executor
    ) {
        this.deliveryEngine = deliveryEngine;
        this.executor = executor;
    }

    /**
     * Enqueues one requirement delivery run.
     *
     * @param taskId RD requirement task ID
     * @return future completed with the delivery result or the execution failure
     */
    public CompletableFuture<RequirementDeliveryResult> submit(String taskId) {
        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        try {
            executor.execute(() -> run(taskId, future));
        } catch (TaskRejectedException exception) {
            future.completeExceptionally(exception);
            throw new IllegalStateException("requirement delivery thread pool rejected task: " + taskId, exception);
        }
        return future;
    }

    private void run(String taskId, CompletableFuture<RequirementDeliveryResult> future) {
        try {
            RequirementDeliveryResult result = deliveryEngine.submit(taskId);
            future.complete(result);
        } catch (RuntimeException exception) {
            log.error("requirement delivery failed in isolated thread pool, taskId={}", taskId, exception);
            future.completeExceptionally(exception);
        }
    }
}
