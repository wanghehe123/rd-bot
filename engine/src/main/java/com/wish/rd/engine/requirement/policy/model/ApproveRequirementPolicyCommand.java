package com.wish.rd.engine.requirement.policy.model;

/**
 * Digest- and fence-bound request to approve one waiting requirement-policy ledger generation.
 *
 * <p>The Host validates every value against the locked ledger and task snapshot before any state
 * changes. This record deliberately models only the affirmative decision; denial is a separate
 * policy transition.
 */
public record ApproveRequirementPolicyCommand(
        String taskId,
        String policyRunId,
        long expectedTaskVersion,
        long expectedFencingToken,
        String planDigest,
        String policyDigest,
        String approvalRequestId,
        String decision,
        String note,
        RequirementPolicyRetryContext retryContext
) {
    private static final String DIGEST_PATTERN = "sha256:[0-9a-f]{64}";

    /** Validates the immutable approval input at the engine boundary. */
    public ApproveRequirementPolicyCommand {
        retryContext = java.util.Objects.requireNonNull(retryContext, "retryContext must not be null");
        taskId = require(taskId, "taskId");
        policyRunId = require(policyRunId, "policyRunId");
        if (expectedTaskVersion < 0L) {
            throw new IllegalArgumentException("expectedTaskVersion must not be negative");
        }
        if (expectedFencingToken <= 0L) {
            throw new IllegalArgumentException("expectedFencingToken must be positive");
        }
        planDigest = requireDigest(planDigest, "planDigest");
        policyDigest = requireDigest(policyDigest, "policyDigest");
        retryContext.requireSourcePlanDigest(planDigest);
        approvalRequestId = require(approvalRequestId, "approvalRequestId");
        decision = require(decision, "decision").toUpperCase(java.util.Locale.ROOT);
        if (!"APPROVED".equals(decision)) {
            throw new IllegalArgumentException("decision must be APPROVED");
        }
        note = note == null ? "" : note.strip();
    }

    /** Compatibility constructor for normal policy operations, with explicit empty retry context. */
    public ApproveRequirementPolicyCommand(
            String taskId, String policyRunId, long expectedTaskVersion, long expectedFencingToken,
            String planDigest, String policyDigest, String approvalRequestId, String decision, String note
    ) {
        this(taskId, policyRunId, expectedTaskVersion, expectedFencingToken, planDigest, policyDigest,
                approvalRequestId, decision, note, RequirementPolicyRetryContext.empty());
    }

    private static String requireDigest(String value, String field) {
        String normalized = require(value, field);
        if (!normalized.matches(DIGEST_PATTERN)) {
            throw new IllegalArgumentException(field + " must be sha256: followed by 64 lowercase hexadecimal characters");
        }
        return normalized;
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
