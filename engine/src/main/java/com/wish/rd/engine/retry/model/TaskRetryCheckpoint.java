package com.wish.rd.engine.retry.model;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.util.List;

/** Persistent audit and dispatch identity for one checkpoint-bound requirement retry. */
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
        long sourceFencingToken,
        String failedStageCommandId,
        String failedStage,
        String sourcePolicyRunId,
        String policyRunId,
        String sourcePlanDigest,
        String authorizationPlanDigest,
        String publicationOperationId,
        long dispatchTaskVersion,
        long dispatchFencingToken,
        String dispatchCommandId,
        long businessGeneration,
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
        sourceTaskVersion = Math.max(0L, sourceTaskVersion);
        sourceFencingToken = Math.max(0L, sourceFencingToken);
        failedStageCommandId = safe(failedStageCommandId);
        failedStage = safe(failedStage);
        sourcePolicyRunId = safe(sourcePolicyRunId);
        policyRunId = safe(policyRunId);
        sourcePlanDigest = safe(sourcePlanDigest);
        authorizationPlanDigest = safe(authorizationPlanDigest);
        publicationOperationId = safe(publicationOperationId);
        dispatchTaskVersion = Math.max(0L, dispatchTaskVersion);
        dispatchFencingToken = Math.max(0L, dispatchFencingToken);
        dispatchCommandId = safe(dispatchCommandId);
        validateDispatch(dispatchTaskVersion, dispatchFencingToken, dispatchCommandId);
        businessGeneration = Math.max(0L, businessGeneration);
        validateGeneration(checkpointId, sourceFencingToken, failedStageCommandId, failedStage, businessGeneration);
        operatorNote = safe(operatorNote);
        evidenceMaterialIds = List.copyOf(evidenceMaterialIds == null ? List.of() : evidenceMaterialIds);
        status = status == null ? TaskRetryCheckpointStatus.CREATED : status;
        reason = safe(reason);
        errorMessage = safe(errorMessage);
    }

    /** Compatibility constructor for rows created before checkpoint-bound dispatch identity. */
    public TaskRetryCheckpoint(
            String checkpointId, String taskId, TaskFailurePhase failurePhase, AgentRole retryFromRole,
            String failedStageRunId, String failedRetrievalRunId, String failedAiReviewRunId, int attemptNo,
            String idempotencyKey, RdTaskStatus sourceTaskStatus, long sourceTaskVersion,
            String operatorNote, List<String> evidenceMaterialIds, TaskRetryCheckpointStatus status,
            String reason, String errorMessage, long createdAtEpochMillis, long updatedAtEpochMillis
    ) {
        this(checkpointId, taskId, failurePhase, retryFromRole, failedStageRunId, failedRetrievalRunId,
                failedAiReviewRunId, attemptNo, idempotencyKey, sourceTaskStatus, sourceTaskVersion,
                0L, "", "", "", "", "", "", "", 0L, 0L, "", 0L,
                operatorNote, evidenceMaterialIds, status, reason, errorMessage, createdAtEpochMillis, updatedAtEpochMillis);
    }

    public static TaskRetryCheckpoint created(
            String checkpointId, TaskRetryPoint point, int attemptNo, String idempotencyKey,
            RdTaskStatus sourceTaskStatus, long nowEpochMillis
    ) {
        return created(checkpointId, point, attemptNo, idempotencyKey, sourceTaskStatus,
                TaskRetryCommand.empty(), nowEpochMillis);
    }

    /** Creates an un-dispatched immutable checkpoint from the exact source failure point. */
    public static TaskRetryCheckpoint created(
            String checkpointId, TaskRetryPoint point, int attemptNo, String idempotencyKey,
            RdTaskStatus sourceTaskStatus, TaskRetryCommand command, long nowEpochMillis
    ) {
        TaskRetryCommand safeCommand = command == null ? TaskRetryCommand.empty() : command;
        long businessGeneration = generationFor(checkpointId, point);
        // Legacy audit-only checkpoints require fence 0. A positive fence without a durable
        // failed-command/stage pair is an illegal mixed identity and must fail closed.
        if (businessGeneration == 0L && point.sourceFencingToken() > 0L) {
            throw new IllegalArgumentException("legacy checkpoint must carry source fence 0");
        }
        long sourceFencingToken = businessGeneration == 0L ? 0L : point.sourceFencingToken();
        return new TaskRetryCheckpoint(
                checkpointId, point.taskId(), point.failurePhase(), point.retryFromRole(),
                point.failedStageRunId(), point.failedRetrievalRunId(), point.failedAiReviewRunId(),
                attemptNo, idempotencyKey, sourceTaskStatus, point.sourceTaskVersion(), sourceFencingToken,
                point.failedStageCommandId(), point.failedStage(), point.sourcePolicyRunId(), "",
                point.sourcePlanDigest(), "", point.publicationOperationId(), 0L, 0L, "",
                businessGeneration,
                safeCommand.operatorNote(), safeCommand.evidenceMaterialIds(), TaskRetryCheckpointStatus.CREATED,
                point.reason(), "", nowEpochMillis, nowEpochMillis);
    }

    public TaskRetryCheckpoint withStatus(
            TaskRetryCheckpointStatus target, String newErrorMessage, long nowEpochMillis
    ) {
        return new TaskRetryCheckpoint(
                checkpointId, taskId, failurePhase, retryFromRole, failedStageRunId, failedRetrievalRunId,
                failedAiReviewRunId, attemptNo, idempotencyKey, sourceTaskStatus, sourceTaskVersion,
                sourceFencingToken, failedStageCommandId, failedStage, sourcePolicyRunId, policyRunId,
                sourcePlanDigest, authorizationPlanDigest, publicationOperationId, dispatchTaskVersion,
                dispatchFencingToken, dispatchCommandId, businessGeneration, operatorNote, evidenceMaterialIds,
                target, reason, newErrorMessage, createdAtEpochMillis, nowEpochMillis);
    }

    /** Returns the sole durable first-command binding for this checkpoint generation. */
    public TaskRetryCheckpoint dispatched(
            long newDispatchTaskVersion,
            long newDispatchFencingToken,
            String newDispatchCommandId,
            long nowEpochMillis
    ) {
        if (status != TaskRetryCheckpointStatus.CREATED) {
            throw new IllegalStateException("only a CREATED retry checkpoint may bind its first command");
        }
        return new TaskRetryCheckpoint(
                checkpointId, taskId, failurePhase, retryFromRole, failedStageRunId, failedRetrievalRunId,
                failedAiReviewRunId, attemptNo, idempotencyKey, sourceTaskStatus, sourceTaskVersion,
                sourceFencingToken, failedStageCommandId, failedStage, sourcePolicyRunId, policyRunId,
                sourcePlanDigest, authorizationPlanDigest, publicationOperationId, newDispatchTaskVersion,
                newDispatchFencingToken, newDispatchCommandId, businessGeneration, operatorNote, evidenceMaterialIds,
                TaskRetryCheckpointStatus.DISPATCHED, reason, "", createdAtEpochMillis, nowEpochMillis);
    }

    private static void validateGeneration(
            String checkpointId, long sourceFence, String failedCommandId, String failedStage, long generation
    ) {
        boolean commandPresent = !failedCommandId.isBlank();
        boolean stagePresent = !failedStage.isBlank();
        if (commandPresent != stagePresent) {
            throw new IllegalArgumentException("durable failure command and stage must be supplied together");
        }
        if (!commandPresent) {
            if (sourceFence != 0L) {
                throw new IllegalArgumentException("legacy checkpoint must carry source fence 0");
            }
            if (generation != 0L) {
                throw new IllegalArgumentException("legacy checkpoint must not carry a businessGeneration");
            }
            return;
        }
        if (sourceFence <= 0L || generation <= 0L) {
            throw new IllegalArgumentException("durable checkpoint requires positive source fence and businessGeneration");
        }
        try {
            if (Long.parseLong(checkpointId) != generation) {
                throw new IllegalArgumentException("businessGeneration must equal checkpointId");
            }
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("checkpoint-bound retry id must be a durable numeric id");
        }
    }

    private static void validateDispatch(long version, long fence, String commandId) {
        boolean commandPresent = !commandId.isBlank();
        boolean complete = version > 0L && fence > 0L && commandPresent;
        boolean empty = version == 0L && fence == 0L && !commandPresent;
        if (!complete && !empty) {
            throw new IllegalArgumentException("retry checkpoint dispatch task version, fence, and command must be bound together");
        }
    }

    private static long generationFor(String checkpointId, TaskRetryPoint point) {
        boolean commandPresent = point != null && !point.failedStageCommandId().isBlank();
        boolean stagePresent = point != null && !point.failedStage().isBlank();
        if (commandPresent != stagePresent) {
            throw new IllegalArgumentException("durable failure command and stage must be supplied together");
        }
        if (!commandPresent) {
            return 0L;
        }
        try {
            return Long.parseLong(checkpointId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("checkpoint-bound retry id must be a durable numeric id", exception);
        }
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
