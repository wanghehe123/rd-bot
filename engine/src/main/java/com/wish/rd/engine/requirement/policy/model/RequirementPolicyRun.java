package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;
import java.util.Objects;

/**
 * Immutable, authoritative policy decision generation for one requirement task.
 *
 * <p>Approval and consumption bind to this record's plan and policy digests; they never infer
 * authorization from a task execution result or historical timeline event.
 */
public record RequirementPolicyRun(
        String id,
        String taskId,
        long sourceTaskVersion,
        long sourceFencingToken,
        String planJson,
        String planDigest,
        String policyJson,
        String policyDigest,
        String policyAction,
        RequirementPolicyRunState state,
        long boundTaskVersion,
        long boundFencingToken,
        Long approvalExpectedTaskVersion,
        Long approvalExpectedFencingToken,
        String approvalRequestId,
        String approvedBy,
        String note,
        long approvedAtEpochMillis,
        String approvalResumeCommandId,
        String consumedByCommandId,
        long consumedAtEpochMillis,
        long ledgerVersion,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {
    private static final String SHA_256_PREFIX = "sha256:";
    private static final String SHA_256_PATTERN = "sha256:[0-9a-f]{64}";

    /** Validates ledger invariants at the domain boundary. */
    public RequirementPolicyRun {
        id = required(id, "id");
        taskId = required(taskId, "taskId");
        if (sourceTaskVersion < 0L) {
            throw new IllegalArgumentException("sourceTaskVersion must not be negative");
        }
        requirePositive(sourceFencingToken, "sourceFencingToken");
        planJson = required(planJson, "planJson");
        planDigest = requireDigest(planDigest, "planDigest");
        if (!planDigest.equals(canonicalJsonDigest(planJson))) {
            throw new IllegalArgumentException("planDigest does not match RFC 8785 canonical planJson");
        }
        policyJson = safe(policyJson);
        policyDigest = safe(policyDigest);
        policyAction = safe(policyAction);
        state = Objects.requireNonNull(state, "state");
        if (policyJson.isBlank() != policyDigest.isBlank()) {
            throw new IllegalArgumentException("policyJson and policyDigest must be supplied together");
        }
        if (!policyJson.isBlank()) {
            policyDigest = requireDigest(policyDigest, "policyDigest");
            if (!policyDigest.equals(canonicalJsonDigest(policyJson))) {
                throw new IllegalArgumentException("policyDigest does not match RFC 8785 canonical policyJson");
            }
            policyAction = required(policyAction, "policyAction");
        }
        requirePositive(boundFencingToken, "boundFencingToken");
        if (boundTaskVersion < 0L || ledgerVersion < 0L) {
            throw new IllegalArgumentException("versions must not be negative");
        }
        approvalRequestId = safe(approvalRequestId);
        approvedBy = safe(approvedBy);
        note = safe(note);
        approvalResumeCommandId = safe(approvalResumeCommandId);
        consumedByCommandId = safe(consumedByCommandId);
        if (approvedAtEpochMillis < 0L || consumedAtEpochMillis < 0L
                || createdAtEpochMillis < 0L || updatedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("ledger timestamps must not be negative");
        }
        validateApprovalExpectedPair(approvalExpectedTaskVersion, approvalExpectedFencingToken);
        validateStateFields(state, policyJson, policyAction, sourceTaskVersion, sourceFencingToken,
                boundTaskVersion, boundFencingToken,
                approvalExpectedTaskVersion, approvalExpectedFencingToken, approvalRequestId, approvedBy,
                approvedAtEpochMillis, approvalResumeCommandId, consumedByCommandId, consumedAtEpochMillis);
    }

    /**
     * Canonicalizes I-JSON under RFC 8785 and returns its lowercase SHA-256 digest.
     *
     * @param json source JSON
     * @return {@code sha256:} plus 64 lowercase hexadecimal characters
     * @throws IllegalArgumentException when input is invalid or outside I-JSON
     */
    public static String canonicalJsonDigest(String json) {
        return CanonicalJsonSha256.digest(json);
    }

    /** Canonicalizes a JSON document using the vetted RFC 8785 JCS implementation. */
    public static String canonicalizeJson(String json) {
        return CanonicalJsonSha256.canonicalize(json);
    }

    /**
     * Terminalizes this active policy generation for an authorized POLICY retry without changing
     * any historical decision, approval, or consumption evidence.
     *
     * @param nowEpochMillis audit timestamp for the CAS successor
     * @return the immutable superseded ledger generation
     */
    public RequirementPolicyRun superseded(long nowEpochMillis) {
        if (!state.canBeSuperseded()) {
            throw new IllegalStateException("policy generation cannot be superseded from " + state);
        }
        return new RequirementPolicyRun(
                id, taskId, sourceTaskVersion, sourceFencingToken, planJson, planDigest,
                policyJson, policyDigest, policyAction, RequirementPolicyRunState.SUPERSEDED,
                boundTaskVersion, boundFencingToken, approvalExpectedTaskVersion,
                approvalExpectedFencingToken, approvalRequestId, approvedBy, note,
                approvedAtEpochMillis, approvalResumeCommandId, consumedByCommandId,
                consumedAtEpochMillis, Math.addExact(ledgerVersion, 1L), createdAtEpochMillis,
                Math.max(0L, nowEpochMillis));
    }

    private static void validateStateFields(
            RequirementPolicyRunState state, String policyJson, String policyAction,
            long sourceTaskVersion, long sourceFencingToken,
            long boundTaskVersion, long boundFencingToken,
            Long approvalExpectedTaskVersion, Long approvalExpectedFencingToken,
            String approvalRequestId, String approvedBy, long approvedAt,
            String approvalResumeCommandId, String consumedByCommandId, long consumedAt
    ) {
        boolean policyPresent = !policyJson.isBlank();
        boolean approved = !approvalRequestId.isBlank() || !approvedBy.isBlank() || approvedAt > 0L;
        boolean consumed = !consumedByCommandId.isBlank() || consumedAt > 0L;
        boolean approvalExpected = approvalExpectedTaskVersion != null;
        switch (state) {
            case PLAN_READY -> require(!policyPresent && !approvalExpected && !approved && approvalResumeCommandId.isBlank()
                    && !consumed && boundTaskVersion == sourceTaskVersion && boundFencingToken == sourceFencingToken,
                    "PLAN_READY must bind exactly its source generation and contain no decision, approval, or consumption");
            case POLICY_DECIDED -> require(policyPresent && !approvalExpected && !approved && approvalResumeCommandId.isBlank()
                    && !consumed && boundTaskVersion == Math.addExact(sourceTaskVersion, 1L)
                    && boundFencingToken == Math.addExact(sourceFencingToken, 1L),
                    "POLICY_DECIDED requires only its source-plus-one policy decision");
            case WAITING_APPROVAL -> require(policyPresent && "WAITING_APPROVAL".equals(policyAction) && !approvalExpected && !approved
                    && approvalResumeCommandId.isBlank() && !consumed
                    && boundTaskVersion == Math.addExact(sourceTaskVersion, 2L)
                    && boundFencingToken == Math.addExact(sourceFencingToken, 2L),
                    "WAITING_APPROVAL requires a waiting policy and no approval or consumption");
            case APPROVED -> require(policyPresent && "WAITING_APPROVAL".equals(policyAction)
                    && approvalExpected && hasApprovalDerivedPair(boundTaskVersion, boundFencingToken,
                    approvalExpectedTaskVersion, approvalExpectedFencingToken, 1L)
                    && !approvalRequestId.isBlank() && !approvedBy.isBlank()
                    && approvedAt > 0L && !approvalResumeCommandId.isBlank() && !consumed
                    && boundTaskVersion == Math.addExact(sourceTaskVersion, 3L)
                    && boundFencingToken == Math.addExact(sourceFencingToken, 3L),
                    "APPROVED requires policy, complete approval, and its resume command");
            case APPLIED -> require(policyPresent && !consumedByCommandId.isBlank() && consumedAt > 0L
                    && ((approvalExpected && "WAITING_APPROVAL".equals(policyAction)
                    && hasApprovalDerivedPair(boundTaskVersion, boundFencingToken,
                    approvalExpectedTaskVersion, approvalExpectedFencingToken, 2L)
                    && !approvalRequestId.isBlank() && !approvedBy.isBlank() && approvedAt > 0L
                    && !approvalResumeCommandId.isBlank()
                    && boundTaskVersion == Math.addExact(sourceTaskVersion, 4L)
                    && boundFencingToken == Math.addExact(sourceFencingToken, 4L))
                    || (!approvalExpected && "ALLOWED".equals(policyAction)
                    && !approved && approvalRequestId.isBlank() && approvedBy.isBlank()
                    && approvedAt == 0L && approvalResumeCommandId.isBlank()
                    && boundTaskVersion == Math.addExact(sourceTaskVersion, 2L)
                    && boundFencingToken == Math.addExact(sourceFencingToken, 2L))),
                    "APPLIED requires consumption and either complete approval or no approval");
            case DENIED -> require(policyPresent && !approvalExpected && !approved
                    && approvalResumeCommandId.isBlank() && consumed
                    && ("NEED_INFO".equals(policyAction) || "UNSAFE".equals(policyAction))
                    && boundTaskVersion == Math.addExact(sourceTaskVersion, 2L)
                    && boundFencingToken == Math.addExact(sourceFencingToken, 2L),
                    "DENIED requires a consumed need-info or unsafe policy");
            case SUPERSEDED -> require(isSupersededActiveAuditSnapshot(
                    policyPresent, policyAction, sourceTaskVersion, sourceFencingToken,
                    boundTaskVersion, boundFencingToken, approvalExpectedTaskVersion,
                    approvalExpectedFencingToken, approvalRequestId, approvedBy, approvedAt,
                    approvalResumeCommandId, consumedByCommandId, consumedAt),
                    "SUPERSEDED must retain one eligible active policy audit snapshot");
        }
    }

    private static boolean isSupersededActiveAuditSnapshot(
            boolean policyPresent, String policyAction, long sourceTaskVersion,
            long sourceFencingToken, long boundTaskVersion, long boundFencingToken,
            Long approvalExpectedTaskVersion, Long approvalExpectedFencingToken,
            String approvalRequestId, String approvedBy, long approvedAt,
            String approvalResumeCommandId, String consumedByCommandId, long consumedAt
    ) {
        boolean approved = !approvalRequestId.isBlank() || !approvedBy.isBlank() || approvedAt > 0L;
        boolean consumed = !consumedByCommandId.isBlank() || consumedAt > 0L;
        boolean approvalExpected = approvalExpectedTaskVersion != null;
        boolean planReady = !policyPresent && !approvalExpected && !approved && !consumed
                && approvalResumeCommandId.isBlank()
                && boundTaskVersion == sourceTaskVersion && boundFencingToken == sourceFencingToken;
        boolean policyDecided = policyPresent && !approvalExpected && !approved && !consumed
                && approvalResumeCommandId.isBlank()
                && boundTaskVersion == Math.addExact(sourceTaskVersion, 1L)
                && boundFencingToken == Math.addExact(sourceFencingToken, 1L);
        boolean waitingApproval = policyPresent && "WAITING_APPROVAL".equals(policyAction)
                && !approvalExpected && !approved && !consumed && approvalResumeCommandId.isBlank()
                && boundTaskVersion == Math.addExact(sourceTaskVersion, 2L)
                && boundFencingToken == Math.addExact(sourceFencingToken, 2L);
        boolean approvedSnapshot = policyPresent && "WAITING_APPROVAL".equals(policyAction)
                && approvalExpected && hasApprovalDerivedPair(boundTaskVersion, boundFencingToken,
                approvalExpectedTaskVersion, approvalExpectedFencingToken, 1L)
                && !approvalRequestId.isBlank() && !approvedBy.isBlank() && approvedAt > 0L
                && !approvalResumeCommandId.isBlank() && !consumed
                && boundTaskVersion == Math.addExact(sourceTaskVersion, 3L)
                && boundFencingToken == Math.addExact(sourceFencingToken, 3L);
        return planReady || policyDecided || waitingApproval || approvedSnapshot;
    }

    private static boolean hasApprovalDerivedPair(
            long boundTaskVersion, long boundFencingToken,
            Long approvalExpectedTaskVersion, Long approvalExpectedFencingToken, long increments
    ) {
        return approvalExpectedTaskVersion != null
                && approvalExpectedFencingToken != null
                && boundTaskVersion == Math.addExact(approvalExpectedTaskVersion, increments)
                && boundFencingToken == Math.addExact(approvalExpectedFencingToken, increments)
                && boundFencingToken > 0L;
    }

    private static void validateApprovalExpectedPair(Long taskVersion, Long fencingToken) {
        if ((taskVersion == null) != (fencingToken == null)) {
            throw new IllegalArgumentException("approval expected task version and fencing token must be supplied together");
        }
        if (taskVersion != null && taskVersion < 0L) {
            throw new IllegalArgumentException("approvalExpectedTaskVersion must not be negative");
        }
        if (fencingToken != null && fencingToken <= 0L) {
            throw new IllegalArgumentException("approvalExpectedFencingToken must be positive");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String requireDigest(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches(SHA_256_PATTERN)) {
            throw new IllegalArgumentException(field + " must be sha256: followed by 64 lowercase hexadecimal characters");
        }
        return normalized;
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0L) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static String required(String value, String field) {
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
