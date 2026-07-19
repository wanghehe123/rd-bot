package com.wish.rd.bootstrap.retry.impl;

import com.wish.rd.engine.retry.TaskRetryTaskPort;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.springframework.stereotype.Component;

/** Adapts the task registry's atomic state transitions to retry orchestration. */
@Component
public final class RegistryTaskRetryTaskAdapter implements TaskRetryTaskPort {

    private final RagStreamTaskRegistry taskRegistry;

    public RegistryTaskRetryTaskAdapter(RagStreamTaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
    }

    @Override
    public RdRequirementTask getRequirementTask(String taskId) {
        RdTask task = taskRegistry.getTask(taskId);
        if (!(task instanceof RdRequirementTask requirementTask)) {
            throw new IllegalArgumentException("task is not a requirement task: " + taskId);
        }
        return requirementTask;
    }

    @Override
    public RdRequirementTask markRecovering(String taskId, String reason) {
        return (RdRequirementTask) taskRegistry.markRecovering(taskId, reason);
    }

    @Override
    public RdRequirementTask markRetryPreparationFailed(String taskId, String reason) {
        RdRequirementTask task = getRequirementTask(taskId);
        if (task.status() == RdTaskStatus.RECOVERING) {
            return taskRegistry.markRequirementFailedRetryable(taskId, reason, task.executionResultJson());
        }
        if (task.status() == RdTaskStatus.REJECTED
                || task.status() == RdTaskStatus.FAILED_RETRYABLE
                || task.status() == RdTaskStatus.FAILED_NEEDS_HUMAN) {
            return task;
        }
        throw new IllegalStateException("cannot compensate retry preparation from task status: " + task.status());
    }
}
