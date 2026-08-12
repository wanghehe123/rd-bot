package com.wish.rd.engine.requirement.job.model;

import com.wish.rd.rag.runtime.RdTaskTransitionPolicy;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskType;

/**
 * One immutable task snapshot mutation planned by external stage work.
 *
 * @param kind status transition or explicit same-status snapshot update
 * @param fromStatus expected status before this mutation
 * @param toStatus status after this mutation
 * @param promptSnapshot optional prompt payload
 * @param executionResultJson replacement result JSON; blank preserves the prior snapshot value
 * @param pullRequestUrl optional pull-request URL
 * @param errorMessage optional error detail
 * @param eventMessage timeline message
 */
public record RequirementTaskMutation(
        Kind kind,
        RdTaskStatus fromStatus,
        RdTaskStatus toStatus,
        String promptSnapshot,
        String executionResultJson,
        String pullRequestUrl,
        String errorMessage,
        String eventMessage
) {

    /** Separates legal graph edges from intentionally same-status payload writes. */
    public enum Kind {
        /** A status graph edge validated by {@link RdTaskTransitionPolicy}. */
        STATUS_TRANSITION,
        /** A same-status snapshot payload update; it is not represented as a graph edge. */
        SNAPSHOT_UPDATE
    }

    /** Normalizes payloads and validates the declared mutation kind. */
    public RequirementTaskMutation {
        kind = kind == null ? Kind.STATUS_TRANSITION : kind;
        if (fromStatus == null || toStatus == null) {
            throw new IllegalArgumentException("mutation statuses must not be null");
        }
        promptSnapshot = safe(promptSnapshot);
        executionResultJson = safe(executionResultJson);
        pullRequestUrl = safe(pullRequestUrl);
        errorMessage = safe(errorMessage);
        eventMessage = safe(eventMessage);
        if (kind == Kind.SNAPSHOT_UPDATE) {
            if (fromStatus != toStatus) {
                throw new IllegalArgumentException("snapshot update must preserve task status");
            }
        } else {
            if (fromStatus == toStatus) {
                throw new IllegalArgumentException("status transition must change task status");
            }
            RdTaskTransitionPolicy.ensureTransition(RdTaskType.REQUIREMENT, fromStatus, toStatus);
        }
    }

    /** Creates a graph-validated status transition. */
    public static RequirementTaskMutation statusTransition(
            RdTaskStatus fromStatus, RdTaskStatus toStatus, String promptSnapshot,
            String executionResultJson, String pullRequestUrl, String errorMessage, String eventMessage
    ) {
        return new RequirementTaskMutation(Kind.STATUS_TRANSITION, fromStatus, toStatus, promptSnapshot,
                executionResultJson, pullRequestUrl, errorMessage, eventMessage);
    }

    /** Creates an explicit same-status payload update. */
    public static RequirementTaskMutation snapshotUpdate(
            RdTaskStatus status, String promptSnapshot, String executionResultJson,
            String pullRequestUrl, String errorMessage, String eventMessage
    ) {
        return new RequirementTaskMutation(Kind.SNAPSHOT_UPDATE, status, status, promptSnapshot,
                executionResultJson, pullRequestUrl, errorMessage, eventMessage);
    }

    /**
     * Returns a nullable prompt replacement for task-snapshot mapper binding.
     *
     * <p>A blank plan payload preserves the stored prompt and must therefore bind {@code null}.
     *
     * @return nonblank replacement, or null to preserve the stored prompt
     */
    public String promptSnapshotReplacementOrNull() {
        return preserveAsNull(promptSnapshot);
    }

    /**
     * Returns a nullable execution-result replacement for task-snapshot mapper binding.
     *
     * <p>A blank plan payload preserves the stored result and must therefore bind {@code null}.
     *
     * @return nonblank replacement, or null to preserve the stored execution result
     */
    public String executionResultReplacementOrNull() {
        return preserveAsNull(executionResultJson);
    }

    /**
     * Returns a nullable pull-request URL replacement for task-snapshot mapper binding.
     *
     * <p>A blank plan payload preserves the stored URL and must therefore bind {@code null}.
     *
     * @return nonblank replacement, or null to preserve the stored pull-request URL
     */
    public String pullRequestUrlReplacementOrNull() {
        return preserveAsNull(pullRequestUrl);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String preserveAsNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

}
