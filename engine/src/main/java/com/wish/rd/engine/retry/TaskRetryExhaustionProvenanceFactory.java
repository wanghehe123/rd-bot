package com.wish.rd.engine.retry;

import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort.ExhaustionProvenanceDraft;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;

import java.util.Objects;

/**
 * Builds the pre-stamp failure provenance draft for a technically exhausted stage command.
 *
 * <p>Phase derivation reuses {@link TaskRetryRoutePlanner#phaseForStage(String, TaskFailurePhase)}
 * so exhaustion never silently degrades an unmappable stage to {@code CONTEXT}.
 */
public final class TaskRetryExhaustionProvenanceFactory {

    private final TaskRetryCheckpointStore checkpointStore;
    private final TaskRetryAttemptBindingStore bindingStore;

    /**
     * Creates the factory with checkpoint and binding lookup ports.
     *
     * @param checkpointStore exact checkpoint store used for source lineage
     * @param bindingStore checkpoint-scoped attempt binding store
     */
    public TaskRetryExhaustionProvenanceFactory(
            TaskRetryCheckpointStore checkpointStore,
            TaskRetryAttemptBindingStore bindingStore
    ) {
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
        this.bindingStore = Objects.requireNonNull(bindingStore, "bindingStore must not be null");
    }

    /**
     * Caller-supplied ledger and attempt context used when the command is not checkpoint-bound
     * or when a binding does not cover every attempt identity field.
     *
     * @param sourcePolicyRunId policy generation identity
     * @param sourcePlanDigest frozen plan digest
     * @param publicationOperationId publication operation identity
     * @param failedStageRunId agent-stage attempt identity
     * @param failedRetrievalRunId retrieval attempt identity
     * @param failedAiReviewRunId AI-review attempt identity
     */
    public record ExecutionContext(
            String sourcePolicyRunId,
            String sourcePlanDigest,
            String publicationOperationId,
            String failedStageRunId,
            String failedRetrievalRunId,
            String failedAiReviewRunId
    ) {
        /** Normalizes nullable execution context fields. */
        public ExecutionContext {
            sourcePolicyRunId = safe(sourcePolicyRunId);
            sourcePlanDigest = safe(sourcePlanDigest);
            publicationOperationId = safe(publicationOperationId);
            failedStageRunId = safe(failedStageRunId);
            failedRetrievalRunId = safe(failedRetrievalRunId);
            failedAiReviewRunId = safe(failedAiReviewRunId);
        }

        /** Returns an empty execution context. */
        public static ExecutionContext empty() {
            return new ExecutionContext("", "", "", "", "", "");
        }

        private static String safe(String value) {
            return value == null ? "" : value.strip();
        }
    }

    /**
     * Builds the provenance draft for one exhausted command.
     *
     * @param exhaustedCommand command that reached its technical attempt limit
     * @param provenanceId durable provenance identity chosen by the caller
     * @param failureKind structured failure kind such as {@code TECHNICAL_EXHAUSTED}
     * @param executionContext caller-supplied ledger/attempt context for non-bound fields
     * @return draft without post-transition version, fence, or outcome status
     * @throws IllegalStateException when the stage cannot be mapped to a failure phase
     */
    public ExhaustionProvenanceDraft draftFor(
            RequirementStageCommand exhaustedCommand,
            String provenanceId,
            String failureKind,
            ExecutionContext executionContext
    ) {
        if (exhaustedCommand == null) {
            throw new IllegalArgumentException("exhaustedCommand must not be null");
        }
        ExecutionContext context = executionContext == null ? ExecutionContext.empty() : executionContext;
        TaskRetryCheckpoint checkpoint = null;
        if (!exhaustedCommand.retryCheckpointId().isBlank()) {
            checkpoint = checkpointStore.find(exhaustedCommand.retryCheckpointId()).orElseThrow(
                    () -> new IllegalStateException("retry checkpoint not found: "
                            + exhaustedCommand.retryCheckpointId()));
        }
        TaskFailurePhase sourceLineagePhase = checkpoint == null ? null : checkpoint.failurePhase();
        TaskFailurePhase failurePhase = TaskRetryRoutePlanner.phaseForStage(
                exhaustedCommand.stage(), sourceLineagePhase);

        String sourcePolicyRunId = checkpoint != null ? checkpoint.sourcePolicyRunId() : context.sourcePolicyRunId();
        String sourcePlanDigest = checkpoint != null ? checkpoint.sourcePlanDigest() : context.sourcePlanDigest();
        String publicationOperationId = checkpoint != null
                ? checkpoint.publicationOperationId()
                : context.publicationOperationId();

        String failedStageRunId = context.failedStageRunId();
        String failedRetrievalRunId = context.failedRetrievalRunId();
        String failedAiReviewRunId = context.failedAiReviewRunId();
        if (!exhaustedCommand.targetRetryBindingId().isBlank()) {
            TaskRetryAttemptBinding binding = bindingStore.findById(exhaustedCommand.targetRetryBindingId())
                    .orElseThrow(() -> new IllegalStateException(
                            "retry target binding not found: " + exhaustedCommand.targetRetryBindingId()));
            if (checkpoint != null && !binding.checkpointId().equals(checkpoint.checkpointId())) {
                throw new IllegalStateException("retry target binding is foreign to exhausted command: "
                        + binding.bindingId());
            }
            if (binding.kind() == TaskRetryAttemptKind.AGENT_STAGE) {
                failedStageRunId = binding.attemptId();
            } else if (binding.kind() == TaskRetryAttemptKind.RETRIEVAL) {
                failedRetrievalRunId = binding.attemptId();
            } else if (binding.kind() == TaskRetryAttemptKind.AI_REVIEW) {
                failedAiReviewRunId = binding.attemptId();
            }
        }
        if (checkpoint != null) {
            if (failedStageRunId.isBlank()) {
                failedStageRunId = checkpoint.failedStageRunId();
            }
            if (failedRetrievalRunId.isBlank()) {
                failedRetrievalRunId = checkpoint.failedRetrievalRunId();
            }
            if (failedAiReviewRunId.isBlank()) {
                failedAiReviewRunId = checkpoint.failedAiReviewRunId();
            }
        }

        String failedStage = exhaustedCommand.stage();
        if (failurePhase == TaskFailurePhase.PR_PUBLICATION) {
            if (publicationOperationId.isBlank()) {
                throw new IllegalStateException("publication exhaustion requires operation id: "
                        + exhaustedCommand.commandId());
            }
            failedStage = "PUBLICATION:" + publicationOperationId;
        }

        return new ExhaustionProvenanceDraft(
                provenanceId,
                failedStage,
                failurePhase,
                failedStageRunId,
                failedRetrievalRunId,
                failedAiReviewRunId,
                sourcePolicyRunId,
                sourcePlanDigest,
                publicationOperationId,
                failureKind);
    }
}
