package com.wish.rd.engine.retry.model;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.util.List;

/** Persistent audit record for one operator-initiated resume-from-failure attempt. */
public record TaskRetryCheckpoint(
        String checkpointId,
        String taskId,
        TaskFailurePhase failurePhase,
        AgentRole retryFromRole,
        String failedStageRunId,
        String failedRetrievalRunId,
        String failedAiReviewRunId,
        int attemptNo,
        String idempotencyKey,
        RdTaskStatus sourceTaskStatus,
        long sourceTaskVersion,
        String operatorNote,
        List<String> evidenceMaterialIds,
        TaskRetryCheckpointStatus status,
        String reason,
        String errorMessage,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {
    public TaskRetryCheckpoint {
        checkpointId = requireText(checkpointId, "checkpointId");
        taskId = requireText(taskId, "taskId");
        if (failurePhase == null) {
            throw new IllegalArgumentException("failurePhase must not be null");
        }
        failedStageRunId = safe(failedStageRunId);
        failedRetrievalRunId = safe(failedRetrievalRunId);
        failedAiReviewRunId = safe(failedAiReviewRunId);
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        if (sourceTaskStatus == null) {
            throw new IllegalArgumentException("sourceTaskStatus must not be null");
        }
        operatorNote = safe(operatorNote);
        evidenceMaterialIds = List.copyOf(evidenceMaterialIds == null ? List.of() : evidenceMaterialIds);
        status = status == null ? TaskRetryCheckpointStatus.CREATED : status;
        reason = safe(reason);
        errorMessage = safe(errorMessage);
    }

    public static TaskRetryCheckpoint created(
            String checkpointId,
            TaskRetryPoint point,
            int attemptNo,
            String idempotencyKey,
            RdTaskStatus sourceTaskStatus,
            long nowEpochMillis
    ) {
        return created(checkpointId, point, attemptNo, idempotencyKey, sourceTaskStatus,
                TaskRetryCommand.empty(), nowEpochMillis);
    }

    /** Creates an immutable checkpoint with the operator's recovery input. */
    public static TaskRetryCheckpoint created(
            String checkpointId,
            TaskRetryPoint point,
            int attemptNo,
            String idempotencyKey,
            RdTaskStatus sourceTaskStatus,
            TaskRetryCommand command,
            long nowEpochMillis
    ) {
        TaskRetryCommand safeCommand = command == null ? TaskRetryCommand.empty() : command;
        return new TaskRetryCheckpoint(
                checkpointId, point.taskId(), point.failurePhase(), point.retryFromRole(),
                point.failedStageRunId(), point.failedRetrievalRunId(), point.failedAiReviewRunId(),
                attemptNo, idempotencyKey, sourceTaskStatus, point.sourceTaskVersion(),
                safeCommand.operatorNote(), safeCommand.evidenceMaterialIds(),
                TaskRetryCheckpointStatus.CREATED, point.reason(), "", nowEpochMillis, nowEpochMillis);
    }

    public TaskRetryCheckpoint withStatus(
            TaskRetryCheckpointStatus target,
            String newErrorMessage,
            long nowEpochMillis
    ) {
        return new TaskRetryCheckpoint(
                checkpointId, taskId, failurePhase, retryFromRole, failedStageRunId,
                failedRetrievalRunId, failedAiReviewRunId, attemptNo, idempotencyKey,
                sourceTaskStatus, sourceTaskVersion, operatorNote, evidenceMaterialIds,
                target, reason, newErrorMessage,
                createdAtEpochMillis, nowEpochMillis);
    }

    private static String requireText(String value, String field) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
