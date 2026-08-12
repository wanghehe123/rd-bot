package com.wish.rd.engine.requirement.policy.model;

/**
 * Read-only policy evaluation over the exact plan snapshot persisted by {@code PLAN_GENERATED}.
 *
 * <p>The proposal is safe to compute before opening the policy transaction. Its canonical plan
 * and decision digests are later bound to one deterministic policy-ledger generation.
 */
public record RequirementPolicyEvaluationProposal(
        String taskId,
        long taskVersion,
        long fencingToken,
        String planJson,
        String planDigest,
        String policyJson,
        String policyDigest,
        String policyAction,
        RequirementPolicyRetryContext retryContext
) {
    /** Validates the frozen plan and canonical decision at the engine boundary. */
    public RequirementPolicyEvaluationProposal {
        retryContext = java.util.Objects.requireNonNull(retryContext, "retryContext must not be null");
        taskId = require(taskId, "taskId");
        if (taskVersion < 0L) {
            throw new IllegalArgumentException("taskVersion must not be negative");
        }
        if (fencingToken <= 0L) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        planJson = require(planJson, "planJson");
        String canonicalPlan = RequirementPolicyRun.canonicalizeJson(planJson);
        if (!canonicalPlan.equals(planJson)) {
            throw new IllegalArgumentException("planJson must already be RFC 8785 canonical");
        }
        if (!RequirementPolicyRun.canonicalJsonDigest(planJson).equals(planDigest)) {
            throw new IllegalArgumentException("planDigest does not match canonical planJson");
        }
        retryContext.requireSourcePlanDigest(planDigest);
        // Reuse the durable command contract for the policy JSON/action/digest invariants.
        new RecordRequirementPolicyDecisionCommand(
                "proposal", taskId, taskVersion, fencingToken, planDigest,
                policyJson, policyDigest, policyAction);
    }

    /** Compatibility constructor for normal policy operations, with explicit empty retry context. */
    public RequirementPolicyEvaluationProposal(
            String taskId, long taskVersion, long fencingToken, String planJson, String planDigest,
            String policyJson, String policyDigest, String policyAction
    ) {
        this(taskId, taskVersion, fencingToken, planJson, planDigest, policyJson, policyDigest,
                policyAction, RequirementPolicyRetryContext.empty());
    }

    /** Binds this proposal to the deterministic ledger id owned by its evaluate command. */
    public RecordRequirementPolicyDecisionCommand toRecordCommand(String policyRunId) {
        return new RecordRequirementPolicyDecisionCommand(
                policyRunId, taskId, taskVersion, fencingToken, planDigest,
                policyJson, policyDigest, policyAction, retryContext);
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
