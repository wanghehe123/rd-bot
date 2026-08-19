package com.wish.rd.engine.requirement.job;

import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageFinalization;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.util.Optional;

/**
 * Host boundary that records stage execution intent and atomically finalizes its command outcome.
 *
 * <p>Production implementations must validate the running lease, persist the marker outcome,
 * complete or retry the current command, enqueue the continuation, and update the umbrella job in
 * one transaction.
 */
public interface RequirementStageFinalizationPort {

    /**
     * Persists a recoverable marker before the stage executor can mutate its task.
     *
     * @param command currently leased stage command
     * @param leaseOwner command lease owner
     * @param nowEpochMillis Host timestamp
     * @return durable prepared marker
     */
    RequirementStageFinalization prepare(
            RequirementStageCommand command,
            String leaseOwner,
            RdTaskStatus expectedTaskStatus,
            long nowEpochMillis
    );

    /**
     * Source-compatible preparation for callers that have not yet supplied their snapshot status.
     *
     * <p>Production dispatchers must use the overload with {@code expectedTaskStatus}; the
     * fallback exists only for bounded legacy callers.
     *
     * @param command currently leased stage command
     * @param leaseOwner command lease owner
     * @param nowEpochMillis Host timestamp
     * @return durable prepared marker
     */
    default RequirementStageFinalization prepare(
            RequirementStageCommand command,
            String leaseOwner,
            long nowEpochMillis
    ) {
        return prepare(command, leaseOwner, RdTaskStatus.CREATED, nowEpochMillis);
    }

    /**
     * Finds the newest unfinished marker for a durable command identity.
     *
     * @param commandId stage command id
     * @return latest prepared marker when a prior attempt may require recovery
     */
    Optional<RequirementStageFinalization> findLatestPrepared(String commandId);

    /**
     * Records a non-mutating execution plan before any task snapshot can be changed. A v2 PI
     * remediation intent is linearized against its frozen source/target profile claims here;
     * finalization and recovery must not consult live profiles after this marker is recorded.
     *
     * @param marker prepared marker for the claimed command
     * @param stageCommand exact leased command
     * @param leaseOwner current lease owner
     * @param plan immutable proposed outcome
     * @param nowEpochMillis Host timestamp
     * @return outcome-recorded marker that recovery can replay without re-executing work
     */
    RequirementStageFinalization recordOutcome(
            RequirementStageFinalization marker,
            RequirementStageCommand stageCommand,
            String leaseOwner,
            RequirementStageExecutionPlan plan,
            long nowEpochMillis
    );

    /** Decodes and verifies the immutable plan persisted by an outcome-recorded marker. */
    RequirementStageExecutionPlan decodeOutcomePlan(RequirementStageFinalization marker);

    /**
     * Applies the command outcome and any continuation as one finalization boundary.
     *
     * @param command immutable finalization input
     * @return durable outcome with the accepted continuation when one was persisted
     */
    FinalizationResult finalize(FinalizationCommand command);

    /**
     * Atomically terminalizes a technically exhausted stage command, fails the fenced task,
     * inserts exact failure provenance stamped with the post-transition snapshot, and settles
     * only the command's own retry checkpoint.
     *
     * <p>Lock order (generic finalizer / exhaustion): source policy (only when a policy-control
     * command) -> command -> task -> checkpoint -> bindings/attempts. Never invert
     * {@code command -> task}. The operation never calls {@code findActiveByTask}; a blank
     * {@code retryCheckpointId} leaves every checkpoint untouched. Provenance insert conflict,
     * checkpoint settlement failure, or attempt cancellation failure rolls back the entire
     * unit, including command terminalization and the task failure CAS.
     *
     * <p>Resumable / idempotent semantics: when the supplied command is already
     * {@code DEAD_LETTERED} with that same disposition, command terminalization is treated as
     * already applied and the operation still performs the task CAS, provenance insert,
     * checkpoint settlement, and unused-attempt cancellation. When the task failure and its
     * provenance row are already durable for that command attempt, the operation is a no-op that
     * returns the existing durable state (no double-write against the unique
     * {@code (task_id, failed_task_version, failed_task_fencing_token)} or
     * {@code (failed_stage_command_id, failed_command_attempt_no)} constraints). Other terminal
     * command statuses ({@code SUCCEEDED}/{@code CANCELLED}) remain hard conflicts.
     *
     * <p>The default keeps bootstrap adapters source-compatible until PostgreSQL overrides it.
     *
     * @param command exhaustion input carrying a provenance draft without post-transition stamps
     * @return durable command, task, provenance, and optional settled checkpoint
     * @throws IllegalStateException when the lease is not owned for a non-resumable command, the
     *         command has a conflicting terminal status, the task CAS fails, or a disposition
     *         cannot be applied atomically
     * @throws UnsupportedOperationException until a concrete adapter overrides this default
     */
    default ExhaustionResult exhaustCommand(ExhaustionCommand command) {
        String commandId = command == null || command.stageCommand() == null
                ? ""
                : command.stageCommand().commandId();
        throw new UnsupportedOperationException("stage command exhaustion is not configured: " + commandId);
    }

    /** Describes how the finalizer must advance the advisory umbrella job. */
    enum JobDisposition {
        NONE,
        COMPLETE,
        FAIL_RETRYABLE,
        CANCEL
    }

    /**
     * Provenance fields known before the task CAS; the operation stamps outcome status,
     * failed task version, and fencing token from the post-transition snapshot.
     *
     * @param provenanceId durable provenance identity
     * @param failedStage exhausted command stage
     * @param failurePhase derived failure phase (never a silent CONTEXT fallback)
     * @param failedStageRunId bound or caller-supplied agent-stage attempt
     * @param failedRetrievalRunId bound or caller-supplied retrieval attempt
     * @param failedAiReviewRunId bound or caller-supplied AI-review attempt
     * @param sourcePolicyRunId inherited policy generation identity
     * @param sourcePlanDigest inherited frozen plan digest
     * @param publicationOperationId publication operation when phase is PR_PUBLICATION
     * @param failureKind structured failure kind such as TECHNICAL_EXHAUSTED
     */
    record ExhaustionProvenanceDraft(
            String provenanceId,
            String failedStage,
            TaskFailurePhase failurePhase,
            String failedStageRunId,
            String failedRetrievalRunId,
            String failedAiReviewRunId,
            String sourcePolicyRunId,
            String sourcePlanDigest,
            String publicationOperationId,
            String failureKind
    ) {
        /** Normalizes a provenance draft without post-transition stamps. */
        public ExhaustionProvenanceDraft {
            provenanceId = requireText(provenanceId, "provenanceId");
            failedStage = safe(failedStage);
            if (failurePhase == null) {
                throw new IllegalArgumentException("failurePhase must not be null");
            }
            failedStageRunId = safe(failedStageRunId);
            failedRetrievalRunId = safe(failedRetrievalRunId);
            failedAiReviewRunId = safe(failedAiReviewRunId);
            sourcePolicyRunId = safe(sourcePolicyRunId);
            sourcePlanDigest = safe(sourcePlanDigest);
            publicationOperationId = safe(publicationOperationId);
            failureKind = safe(failureKind);
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

    /**
     * Immutable input for one technical exhaustion transaction.
     *
     * @param stageCommand leased command at its technical attempt limit
     * @param leaseOwner current command lease owner
     * @param expectedTaskStatus exact fenced source task status
     * @param expectedTaskVersion exact fenced source task version
     * @param expectedTaskFencingToken exact fenced source fencing token
     * @param targetTaskStatus terminal task status ({@code FAILED_RETRYABLE} or {@code DEAD_LETTERED})
     * @param targetCheckpointStatus terminal checkpoint status when a checkpoint must settle
     * @param provenanceDraft pre-stamp provenance identity and route fields
     * @param errorMessage failure reason written onto command and task
     * @param resultJson optional structured task result payload
     * @param nowEpochMillis host timestamp
     */
    record ExhaustionCommand(
            RequirementStageCommand stageCommand,
            String leaseOwner,
            RdTaskStatus expectedTaskStatus,
            long expectedTaskVersion,
            long expectedTaskFencingToken,
            RdTaskStatus targetTaskStatus,
            TaskRetryCheckpointStatus targetCheckpointStatus,
            ExhaustionProvenanceDraft provenanceDraft,
            String errorMessage,
            String resultJson,
            long nowEpochMillis
    ) {
        /** Normalizes exhaustion inputs and rejects illegal terminal targets. */
        public ExhaustionCommand {
            if (stageCommand == null || expectedTaskStatus == null || targetTaskStatus == null
                    || provenanceDraft == null) {
                throw new IllegalArgumentException(
                        "stageCommand, expectedTaskStatus, targetTaskStatus, and provenanceDraft must not be null");
            }
            if (targetTaskStatus != RdTaskStatus.FAILED_RETRYABLE
                    && targetTaskStatus != RdTaskStatus.DEAD_LETTERED) {
                throw new IllegalArgumentException("exhaustion targetTaskStatus must be FAILED_RETRYABLE or DEAD_LETTERED");
            }
            if (targetCheckpointStatus == null) {
                targetCheckpointStatus = TaskRetryCheckpointStatus.FAILED_RETRYABLE;
            }
            if (!targetCheckpointStatus.isTerminal()) {
                throw new IllegalArgumentException("exhaustion targetCheckpointStatus must be terminal");
            }
            leaseOwner = leaseOwner == null ? "" : leaseOwner.strip();
            if (leaseOwner.isBlank()) {
                throw new IllegalArgumentException("leaseOwner must not be blank");
            }
            expectedTaskVersion = Math.max(0L, expectedTaskVersion);
            expectedTaskFencingToken = Math.max(0L, expectedTaskFencingToken);
            if (expectedTaskFencingToken <= 0L || stageCommand.fencingToken() <= 0L) {
                throw new IllegalArgumentException("exhaustion requires positive fencing tokens");
            }
            errorMessage = errorMessage == null ? "" : errorMessage.strip();
            resultJson = resultJson == null || resultJson.isBlank() ? "{}" : resultJson.strip();
            nowEpochMillis = Math.max(0L, nowEpochMillis);
        }
    }

    /**
     * Durable result of one exhaustion transaction.
     *
     * @param completedCommand terminalized stage command
     * @param failedTask post-transition task snapshot
     * @param provenance immutable provenance stamped with the post-transition identity
     * @param settledCheckpoint checkpoint settled by exact ID, or {@code null} when none was bound
     */
    record ExhaustionResult(
            RequirementStageCommand completedCommand,
            RdRequirementTask failedTask,
            TaskRetryFailureProvenance provenance,
            TaskRetryCheckpoint settledCheckpoint
    ) {
        /** Normalizes an exhaustion result. */
        public ExhaustionResult {
            if (completedCommand == null || failedTask == null || provenance == null) {
                throw new IllegalArgumentException("completedCommand, failedTask, and provenance must not be null");
            }
        }
    }

    /**
     * Immutable input for one stage finalization transaction.
     *
     * @param marker prepared marker being closed
     * @param stageCommand current leased command, which may be a later reclaim of the marker attempt
     * @param leaseOwner current command lease owner
     * @param outcome accepted task stage outcome
     * @param nextCommand planned continuation, or null for terminal/retry outcomes
     * @param umbrellaJob claimed legacy job, or null when absent
     * @param jobDisposition required umbrella job change
     * @param nowEpochMillis Host timestamp
     */
    record FinalizationCommand(
            RequirementStageFinalization marker,
            RequirementStageCommand stageCommand,
            String leaseOwner,
            RequirementStageExecutionPlan plan,
            RequirementStageCommand nextCommand,
            RequirementDeliveryJob umbrellaJob,
            JobDisposition jobDisposition,
            TaskMutationDisposition taskMutationDisposition,
            long nowEpochMillis
    ) {
        /** Compatibility constructor for existing focused tests while callers migrate to plans. */
        public FinalizationCommand(
                RequirementStageFinalization marker, RequirementStageCommand stageCommand, String leaseOwner,
                RequirementDeliveryResult outcome, RequirementStageCommand nextCommand,
                RequirementDeliveryJob umbrellaJob, JobDisposition jobDisposition,
                TaskMutationDisposition taskMutationDisposition, long nowEpochMillis
        ) {
            this(marker, stageCommand, leaseOwner, legacyPlan(marker, outcome), nextCommand, umbrellaJob,
                    jobDisposition, taskMutationDisposition, nowEpochMillis);
        }

        /** Normalizes nullable finalization inputs to explicit safe values. */
        public FinalizationCommand {
            if (marker == null || stageCommand == null || plan == null) {
                throw new IllegalArgumentException("marker, stageCommand, and plan must not be null");
            }
            if (marker.expectedFencingToken() <= 0L || stageCommand.fencingToken() <= 0L
                    || (nextCommand != null && nextCommand.fencingToken() <= 0L)) {
                throw new IllegalArgumentException("finalization commands require positive fencing tokens");
            }
            leaseOwner = leaseOwner == null ? "" : leaseOwner.strip();
            if (leaseOwner.isBlank()) {
                throw new IllegalArgumentException("leaseOwner must not be blank");
            }
            jobDisposition = jobDisposition == null ? JobDisposition.NONE : jobDisposition;
            taskMutationDisposition = taskMutationDisposition == null
                    ? TaskMutationDisposition.APPLY : taskMutationDisposition;
            nowEpochMillis = Math.max(0L, nowEpochMillis);
        }

        /** Derives the legacy result projection only for advisory job compatibility. */
        public RequirementDeliveryResult outcome() {
            var last = plan.mutations().isEmpty() ? null : plan.mutations().getLast();
            return new RequirementDeliveryResult(
                    plan.taskId(), plan.postStatus(), last == null ? "" : last.pullRequestUrl(),
                    last == null || last.executionResultJson().isBlank() ? "{}" : last.executionResultJson(),
                    last == null ? "" : last.errorMessage());
        }

        private static RequirementStageExecutionPlan legacyPlan(
                RequirementStageFinalization marker, RequirementDeliveryResult outcome
        ) {
            if (marker == null || outcome == null) {
                throw new IllegalArgumentException("marker and outcome must not be null");
            }
            return new RequirementStageExecutionPlan(1, marker.taskId(), marker.expectedTaskVersion(),
                    marker.expectedFencingToken(), marker.expectedTaskStatus(), java.util.List.of(
                    com.wish.rd.engine.requirement.job.model.RequirementTaskMutation.statusTransition(
                            marker.expectedTaskStatus(), outcome.status(), "", outcome.resultJson(),
                            outcome.pullRequestUrl(), outcome.errorMessage(), "")),
                    com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                    com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                    com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        }
    }

    /** Identifies whether this transaction must apply the task CAS or only close an already-applied legacy mutation. */
    enum TaskMutationDisposition {
        /** The outcome task snapshot and timeline event must be written in this transaction. */
        APPLY,
        /** Recovery observed the exact mutation already durable before the host finalizer was introduced. */
        ALREADY_APPLIED
    }

    /** Durable result of a finalization transaction. */
    record FinalizationResult(
            RequirementStageFinalization finalization,
            RequirementStageCommand completedCommand,
            RequirementStageCommand nextCommand
    ) {
        /** Normalizes a finalization result. */
        public FinalizationResult {
            if (finalization == null || completedCommand == null) {
                throw new IllegalArgumentException("finalization and completedCommand must not be null");
            }
        }
    }
}
