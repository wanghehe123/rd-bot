package com.wish.rd.engine.requirement.publication.model;

/**
 * Immutable external publication ledger snapshot.
 *
 * @param id                     publication id
 * @param operationId            unique operation identity
 * @param taskId                 RD task id
 * @param stageRunId             publishing stage run id
 * @param status                 ledger status
 * @param baseBranch             target branch
 * @param workBranch             candidate work branch
 * @param candidatePatchSha256   candidate patch identity
 * @param remoteHeadSha          confirmed remote branch head
 * @param pullRequestUrl         confirmed PR url
 * @param pullRequestNumber      confirmed PR number
 * @param version                CAS version
 * @param lastError              latest remote/diagnostic error
 * @param nextReconcileAtEpochMillis next reconcile schedule
 * @param createTimeEpochMillis  create time
 * @param updateTimeEpochMillis  update time
 */
public record RequirementPublication(
        String id,
        String operationId,
        String taskId,
        String stageRunId,
        RequirementPublicationStatus status,
        String baseBranch,
        String workBranch,
        String candidatePatchSha256,
        String remoteHeadSha,
        String pullRequestUrl,
        int pullRequestNumber,
        int version,
        String lastError,
        long nextReconcileAtEpochMillis,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public RequirementPublication {
        id = require(id, "id");
        operationId = require(operationId, "operationId");
        taskId = require(taskId, "taskId");
        stageRunId = safe(stageRunId);
        status = status == null ? RequirementPublicationStatus.PREPARED : status;
        baseBranch = require(baseBranch, "baseBranch");
        workBranch = require(workBranch, "workBranch");
        candidatePatchSha256 = require(candidatePatchSha256, "candidatePatchSha256");
        remoteHeadSha = safe(remoteHeadSha);
        pullRequestUrl = safe(pullRequestUrl);
        pullRequestNumber = Math.max(0, pullRequestNumber);
        version = Math.max(1, version);
        lastError = safe(lastError);
        nextReconcileAtEpochMillis = Math.max(0L, nextReconcileAtEpochMillis);
        createTimeEpochMillis = Math.max(0L, createTimeEpochMillis);
        updateTimeEpochMillis = Math.max(createTimeEpochMillis, updateTimeEpochMillis);
    }

    /**
     * Creates the initial prepared intent.
     */
    public static RequirementPublication prepared(
            String id,
            String operationId,
            String taskId,
            String stageRunId,
            String baseBranch,
            String workBranch,
            String candidatePatchSha256,
            long now
    ) {
        return new RequirementPublication(
                id,
                operationId,
                taskId,
                stageRunId,
                RequirementPublicationStatus.PREPARED,
                baseBranch,
                workBranch,
                candidatePatchSha256,
                "",
                "",
                0,
                1,
                "",
                0L,
                now,
                now
        );
    }

    /**
     * Advances to branch confirmed.
     */
    public RequirementPublication withBranchConfirmed(String remoteHeadSha, long now) {
        return transition(
                RequirementPublicationStatus.BRANCH_CONFIRMED,
                safe(remoteHeadSha),
                pullRequestUrl,
                pullRequestNumber,
                "",
                0L,
                now
        );
    }

    /**
     * Advances to pull request confirmed.
     */
    public RequirementPublication withPullRequestConfirmed(String pullRequestUrl, int pullRequestNumber, long now) {
        return transition(
                RequirementPublicationStatus.PR_CONFIRMED,
                remoteHeadSha,
                require(pullRequestUrl, "pullRequestUrl"),
                Math.max(1, pullRequestNumber),
                "",
                0L,
                now
        );
    }

    /**
     * Marks an ambiguous remote write that must be reconciled before replay.
     */
    public RequirementPublication withUnknownRemoteResult(String error, long nextReconcileAt, long now) {
        return transition(
                RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT,
                remoteHeadSha,
                pullRequestUrl,
                pullRequestNumber,
                require(error, "lastError"),
                Math.max(now, nextReconcileAt),
                now
        );
    }

    /**
     * Marks the publication committed after the task snapshot transaction succeeds.
     */
    public RequirementPublication withCommitted(long now) {
        return transition(
                RequirementPublicationStatus.COMMITTED,
                remoteHeadSha,
                pullRequestUrl,
                pullRequestNumber,
                "",
                0L,
                now
        );
    }

    /**
     * Resets UNKNOWN_REMOTE_RESULT back to PREPARED after reconcile proves the remote
     * write never landed, clearing remote evidence and reconcile schedule.
     */
    public RequirementPublication withResetPrepared(long now) {
        return transition(
                RequirementPublicationStatus.PREPARED,
                "",
                "",
                0,
                "",
                0L,
                now
        );
    }

    /**
     * Escalates to human review when remote state conflicts or cannot be auto-resolved.
     */
    public RequirementPublication withNeedsHuman(String reason, long now) {
        return transition(
                RequirementPublicationStatus.NEEDS_HUMAN,
                remoteHeadSha,
                pullRequestUrl,
                pullRequestNumber,
                require(reason, "lastError"),
                0L,
                now
        );
    }

    /**
     * Updates the next reconcile schedule without changing status or remote evidence.
     */
    public RequirementPublication withNextReconcileAt(long nextReconcileAt, long now) {
        return transition(
                status,
                remoteHeadSha,
                pullRequestUrl,
                pullRequestNumber,
                lastError,
                Math.max(now, nextReconcileAt),
                now
        );
    }

    private RequirementPublication transition(
            RequirementPublicationStatus nextStatus,
            String nextRemoteHeadSha,
            String nextPullRequestUrl,
            int nextPullRequestNumber,
            String nextError,
            long nextReconcileAt,
            long now
    ) {
        return new RequirementPublication(
                id,
                operationId,
                taskId,
                stageRunId,
                nextStatus,
                baseBranch,
                workBranch,
                candidatePatchSha256,
                nextRemoteHeadSha,
                nextPullRequestUrl,
                nextPullRequestNumber,
                version + 1,
                nextError,
                nextReconcileAt,
                createTimeEpochMillis,
                now
        );
    }

    private static String require(String value, String field) {
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
