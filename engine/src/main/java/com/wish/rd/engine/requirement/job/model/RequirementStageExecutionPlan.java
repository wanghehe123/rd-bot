package com.wish.rd.engine.requirement.job.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wish.rd.engine.requirement.audit.AuditedStateMutation;
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
 * @param piQaRemediationIntent optional immutable PI QA remediation intent
 * @param auditedStateMutation optional audited-state writeback (schema v3)
 * @param managerDecision optional Host Manager decision frozen with this plan
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
        ExternalEffectReceipt externalEffectReceipt,
        PiQaRemediationIntent piQaRemediationIntent,
        AuditedStateMutation auditedStateMutation,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        com.wish.rd.engine.requirement.manager.ManagerDecision managerDecision
) {

    public static final int LEGACY_SCHEMA_VERSION = 1;
    public static final int SCHEMA_VERSION_V2 = 2;
    /** Current durable JSON schema accepted by the Host stage finalizer. */
    public static final int CURRENT_SCHEMA_VERSION = 3;

    /** Normalizes the plan and verifies task-edge continuity before it becomes durable. */
    public RequirementStageExecutionPlan {
        if (schemaVersion != LEGACY_SCHEMA_VERSION
                && schemaVersion != SCHEMA_VERSION_V2
                && schemaVersion != CURRENT_SCHEMA_VERSION) {
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
        if (schemaVersion == LEGACY_SCHEMA_VERSION && piQaRemediationIntent != null) {
            throw new IllegalArgumentException("legacy stage execution plan must not carry remediation intent");
        }
        if (schemaVersion < CURRENT_SCHEMA_VERSION && auditedStateMutation != null) {
            throw new IllegalArgumentException("schema v1/v2 stage execution plan must not carry audited state mutation");
        }
        if (piQaRemediationIntent != null
                && (!taskId.equals(piQaRemediationIntent.sourceTaskId())
                || expectedVersion != piQaRemediationIntent.sourceTaskVersion()
                || expectedFencingToken != piQaRemediationIntent.sourceFencingToken())) {
            throw new IllegalArgumentException("remediation intent source task identity mismatch");
        }
        if ((commandDisposition == CommandDisposition.TERMINAL_FAILURE
                || commandDisposition == CommandDisposition.RETRYABLE_TECHNICAL_FAILURE)
                && !continuation.isTerminal()) {
            throw new IllegalArgumentException("failed command disposition requires terminal continuation");
        }
        validateChain(expectedStatus, mutations);
    }

    /**
     * Returns a copy whose frozen remediation intent is the Host-assigned linearization result.
     *
     * @param intent assigned intent, or {@code null} to clear it
     * @return plan carrying {@code intent}
     */
    public RequirementStageExecutionPlan withRemediationIntent(PiQaRemediationIntent intent) {
        return new RequirementStageExecutionPlan(
                schemaVersion, taskId, expectedVersion, expectedFencingToken, expectedStatus,
                mutations, commandDisposition, continuation, externalEffectReceipt, intent,
                auditedStateMutation, managerDecision);
    }

    /**
     * Returns a copy carrying the Host Manager decision.
     *
     * @param decision decision, or {@code null} to clear it
     * @return plan carrying {@code decision}
     */
    public RequirementStageExecutionPlan withManagerDecision(
            com.wish.rd.engine.requirement.manager.ManagerDecision decision
    ) {
        return new RequirementStageExecutionPlan(
                schemaVersion, taskId, expectedVersion, expectedFencingToken, expectedStatus,
                mutations, commandDisposition, continuation, externalEffectReceipt,
                piQaRemediationIntent, auditedStateMutation, decision);
    }

    /**
     * Returns a schema-v3 copy carrying audited-state writeback.
     *
     * @param mutation writeback, or {@code null} to clear it
     * @return plan carrying {@code mutation}
     */
    public RequirementStageExecutionPlan withAuditedStateMutation(AuditedStateMutation mutation) {
        return new RequirementStageExecutionPlan(
                CURRENT_SCHEMA_VERSION, taskId, expectedVersion, expectedFencingToken, expectedStatus,
                mutations, commandDisposition, continuation, externalEffectReceipt, piQaRemediationIntent,
                mutation, managerDecision);
    }

    /** Backward-compatible constructor used by non-remediation plan producers. */
    public RequirementStageExecutionPlan(
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
        this(schemaVersion, taskId, expectedVersion, expectedFencingToken, expectedStatus, mutations,
                commandDisposition, continuation, externalEffectReceipt, null, null, null);
    }

    /** Backward-compatible constructor used by remediation plan producers. */
    public RequirementStageExecutionPlan(
            int schemaVersion,
            String taskId,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus,
            List<RequirementTaskMutation> mutations,
            CommandDisposition commandDisposition,
            ContinuationSpec continuation,
            ExternalEffectReceipt externalEffectReceipt,
            PiQaRemediationIntent piQaRemediationIntent
    ) {
        this(schemaVersion, taskId, expectedVersion, expectedFencingToken, expectedStatus, mutations,
                commandDisposition, continuation, externalEffectReceipt, piQaRemediationIntent, null, null);
    }

    /** Backward-compatible constructor used by Manager plan producers. */
    public RequirementStageExecutionPlan(
            int schemaVersion,
            String taskId,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus,
            List<RequirementTaskMutation> mutations,
            CommandDisposition commandDisposition,
            ContinuationSpec continuation,
            ExternalEffectReceipt externalEffectReceipt,
            PiQaRemediationIntent piQaRemediationIntent,
            AuditedStateMutation auditedStateMutation
    ) {
        this(schemaVersion, taskId, expectedVersion, expectedFencingToken, expectedStatus, mutations,
                commandDisposition, continuation, externalEffectReceipt, piQaRemediationIntent,
                auditedStateMutation, null);
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
