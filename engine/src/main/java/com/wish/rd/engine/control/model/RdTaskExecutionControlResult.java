package com.wish.rd.engine.control.model;

import com.wish.rd.rag.runtime.model.RdTask;

import java.util.Objects;

/** Auditable result of stopping one task and its latest active role attempts. */
public record RdTaskExecutionControlResult(
        RdTask task,
        boolean externalStopped,
        String containerName,
        String message,
        int cancelledStageCount
) {
    public RdTaskExecutionControlResult {
        task = Objects.requireNonNull(task, "task must not be null");
        containerName = containerName == null ? "" : containerName.strip();
        message = message == null ? "" : message.strip();
        if (cancelledStageCount < 0) {
            throw new IllegalArgumentException("cancelledStageCount must not be negative");
        }
    }
}
