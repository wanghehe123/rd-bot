package com.wish.rd.engine.requirement.verify.model;

/**
 * Immutable snapshot of one host BUILD/STATIC verification attempt.
 *
 * <p>The requirement delivery orchestrator will create a run after
 * {@code CODING_AGENT} succeeds and before {@code QA_AGENT} is dispatched.
 * Persistence, ports, and HTTP adapters arrive in later tasks; this record is
 * the domain truth shape. A terminal status is never reopened in place —
 * remediations create {@code attemptNo + 1} with {@code parentRunId} pointing here.
 *
 * @param runId                  unique verification run id
 * @param taskId                 owning RD task id
 * @param codingStageRunId       coding {@code AgentStageRun} this run verifies
 * @param parentRunId            previous verification run, or blank for the first attempt
 * @param attemptNo              1-based attempt number
 * @param status                 current lifecycle status
 * @param docsOnly               whether the candidate change-set is documentation-only
 * @param failureCategory        machine-readable failure class, or blank
 * @param errorMessage           operator-facing error, or blank
 * @param remediationCount       cheap remediations already consumed before this run
 * @param createdAtEpochMillis   create time
 * @param startedAtEpochMillis   start time, or 0 if not started
 * @param finishedAtEpochMillis  finish time, or 0 if not finished
 */
public record HostVerificationRun(
        String runId,
        String taskId,
        String codingStageRunId,
        String parentRunId,
        int attemptNo,
        HostVerificationStatus status,
        boolean docsOnly,
        String failureCategory,
        String errorMessage,
        int remediationCount,
        long createdAtEpochMillis,
        long startedAtEpochMillis,
        long finishedAtEpochMillis
) {

    public HostVerificationRun {
        runId = requireText(runId, "runId");
        taskId = requireText(taskId, "taskId");
        codingStageRunId = requireText(codingStageRunId, "codingStageRunId");
        parentRunId = normalize(parentRunId);
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be >= 1");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        failureCategory = normalize(failureCategory);
        errorMessage = normalize(errorMessage);
        if (remediationCount < 0) {
            throw new IllegalArgumentException("remediationCount must be >= 0");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
