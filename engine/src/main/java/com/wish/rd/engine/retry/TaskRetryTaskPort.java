package com.wish.rd.engine.retry;

import com.wish.rd.rag.runtime.model.RdRequirementTask;

/** Task-state boundary used by retry orchestration to load and atomically mark recovery. */
public interface TaskRetryTaskPort {

    RdRequirementTask getRequirementTask(String taskId);

    RdRequirementTask markRecovering(String taskId, String reason);

    /** Restores a retryable task state when retry preparation or durable dispatch fails. */
    RdRequirementTask markRetryPreparationFailed(String taskId, String reason);
}
