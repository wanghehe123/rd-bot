package com.wish.rd.engine.requirement.job.model;

import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.util.List;

/**
 * Durable, schema-versioned plan produced by non-mutating stage execution and committed by Host.
 *
 * @param schemaVersion durable plan schema version
 * @param taskId task identity
 * @param expectedVersion task version captured by the leased command
 * @param expectedFencingToken fencing token captured by the leased command
 * @param expectedStatus task status captured by the leased command
 * @param mutations ordered immutable task mutations
 * @param commandDisposition required current-command outcome
 * @param continuation optional continuation identity
 * @param externalEffectReceipt durable external-effect evidence
 */
public record RequirementStageExecutionPlan(
        int schemaVersion,
        String taskId,
        long expectedVersion,
        long expectedFencingToken,
        RdTaskStatus expectedStatus,
        List<RequirementTaskMutation> mutations,
        CommandDisposition commandDisposition,
        ContinuationSpec continuation,
        ExternalEffectReceipt externalEffectReceipt
) {

    /** Current durable JSON schema accepted by the Host stage finalizer. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Normalizes the plan and verifies task-edge continuity before it becomes durable. */
    public RequirementStageExecutionPlan {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported stage execution plan schemaVersion: " + schemaVersion);
        }
        taskId = require(taskId, "taskId");
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (expectedFencingToken <= 0L) {
            throw new IllegalArgumentException("expectedFencingToken must be positive");
        }
        if (expectedStatus == null) {
            throw new IllegalArgumentException("expectedStatus must not be null");
        }
        mutations = mutations == null ? List.of() : List.copyOf(mutations);
        if (mutations.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("mutations must not contain null");
        }
        commandDisposition = commandDisposition == null ? CommandDisposition.RETRYABLE_TECHNICAL_FAILURE
                : commandDisposition;
        continuation = continuation == null ? ContinuationSpec.terminal() : continuation;
        if (externalEffectReceipt == null) {
            throw new IllegalArgumentException("externalEffectReceipt must be explicit");
        }
        if ((commandDisposition == CommandDisposition.TERMINAL_FAILURE
                || commandDisposition == CommandDisposition.RETRYABLE_TECHNICAL_FAILURE)
                && !continuation.isTerminal()) {
            throw new IllegalArgumentException("failed command disposition requires terminal continuation");
        }
        validateChain(expectedStatus, mutations);
    }

    /**
     * Returns the version after every planned snapshot mutation has committed.
     *
     * @return expected version plus mutation count
     */
    public long postVersion() {
        return expectedVersion + mutations.size();
    }

    /**
     * Returns the fencing token after every planned snapshot mutation has committed.
     *
     * <p>The repository's fenced PostgreSQL update increments {@code fencing_token} with every
     * successful snapshot update, including explicit same-status updates.
     *
     * @return expected fencing token plus mutation count
     */
    public long postFencingToken() {
        return expectedFencingToken + mutations.size();
    }

    /**
     * Returns the status after the final mutation, or the expected status for an empty plan.
     *
     * @return post-plan task status
     */
    public RdTaskStatus postStatus() {
        return mutations.isEmpty() ? expectedStatus : mutations.getLast().toStatus();
    }

    private static void validateChain(RdTaskStatus expectedStatus, List<RequirementTaskMutation> mutations) {
        RdTaskStatus previous = expectedStatus;
        for (RequirementTaskMutation mutation : mutations) {
            if (mutation.fromStatus() != previous) {
                throw new IllegalArgumentException("mutation chain is discontinuous: expected " + previous
                        + " but found " + mutation.fromStatus());
            }
            previous = mutation.toStatus();
        }
    }

    private static String require(String value, String name) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
