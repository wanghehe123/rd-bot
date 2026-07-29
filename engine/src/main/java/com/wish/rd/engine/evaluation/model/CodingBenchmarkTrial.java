package com.wish.rd.engine.evaluation.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable control-plane snapshot for one coding benchmark case/arm attempt.
 *
 * <p>The evaluation campaign scheduler and PostgreSQL store use this record to preserve the
 * one-result contract for `(campaignId, caseId, arm, replicateNo)`.</p>
 */
public record CodingBenchmarkTrial(
        String trialId,
        String campaignId,
        String caseId,
        CodingBenchmarkArm arm,
        int replicateNo,
        CodingBenchmarkTrialStatus status,
        CodingBenchmarkVerdict verdict,
        int attemptNo,
        long version,
        String leaseOwner,
        long leaseExpiresAtEpochMillis,
        String errorCategory,
        String errorMessage,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    /** Normalizes externally persisted values and rejects an invalid logical trial identity. */
    public CodingBenchmarkTrial {
        trialId = requireId(trialId, "trial id");
        campaignId = requireId(campaignId, "campaign id");
        caseId = requireId(caseId, "case id");
        arm = Objects.requireNonNull(arm, "arm must not be null");
        if (replicateNo < 0 || replicateNo > 1) {
            throw new IllegalArgumentException("replicate number must be 0 or 1");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        verdict = verdict == null ? CodingBenchmarkVerdict.PENDING : verdict;
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attempt number must be at least 1");
        }
        if (version < 0L || leaseExpiresAtEpochMillis < 0L || createdAtEpochMillis < 0L || updatedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("trial timestamps and version must not be negative");
        }
        leaseOwner = safe(leaseOwner);
        errorCategory = safe(errorCategory);
        errorMessage = safe(errorMessage);
    }

    /**
     * Creates the first immutable snapshot before a worker claims the trial.
     *
     * @param trialId generated logical trial ID
     * @param campaignId parent evaluation campaign ID
     * @param caseId frozen benchmark case ID
     * @param arm pre-registered workflow arm
     * @param replicateNo zero for the formal matrix and one for a sentinel rerun
     * @param now current epoch milliseconds
     * @return a queued, pending first attempt
     */
    public static CodingBenchmarkTrial queued(
            String trialId,
            String campaignId,
            String caseId,
            CodingBenchmarkArm arm,
            int replicateNo,
            long now
    ) {
        return new CodingBenchmarkTrial(
                trialId, campaignId, caseId, arm, replicateNo,
                CodingBenchmarkTrialStatus.QUEUED, CodingBenchmarkVerdict.PENDING,
                1, 0L, "", 0L, "", "", now, now
        );
    }

    private static String requireId(String value, String field) {
        String normalized = safe(value);
        if (!ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
