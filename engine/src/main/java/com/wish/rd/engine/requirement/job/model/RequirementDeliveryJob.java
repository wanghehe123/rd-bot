package com.wish.rd.engine.requirement.job.model;

/** Immutable durable-dispatch job snapshot. */
public record RequirementDeliveryJob(
        String jobId,
        String taskId,
        RequirementDeliveryJobStatus status,
        int attemptNo,
        int maxAttempts,
        String leaseOwner,
        long leaseUntilEpochMillis,
        String errorMessage,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {
    public RequirementDeliveryJob {
        jobId = require(jobId, "jobId");
        taskId = require(taskId, "taskId");
        status = status == null ? RequirementDeliveryJobStatus.PENDING : status;
        attemptNo = Math.max(0, attemptNo);
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        leaseOwner = safe(leaseOwner);
        errorMessage = safe(errorMessage);
    }

    public static RequirementDeliveryJob pending(String jobId, String taskId, int maxAttempts, long now) {
        return new RequirementDeliveryJob(
                jobId, taskId, RequirementDeliveryJobStatus.PENDING, 0, maxAttempts,
                "", 0L, "", now, now);
    }

    public RequirementDeliveryJob claimed(String owner, long leaseUntil, long now) {
        return new RequirementDeliveryJob(
                jobId, taskId, RequirementDeliveryJobStatus.RUNNING, attemptNo + 1, maxAttempts,
                owner, leaseUntil, "", createTimeEpochMillis, now);
    }

    public RequirementDeliveryJob heartbeat(long leaseUntil, long now) {
        return new RequirementDeliveryJob(
                jobId, taskId, status, attemptNo, maxAttempts, leaseOwner, leaseUntil,
                errorMessage, createTimeEpochMillis, now);
    }

    public RequirementDeliveryJob succeeded(long now) {
        return finished(RequirementDeliveryJobStatus.SUCCEEDED, "", now);
    }

    public RequirementDeliveryJob failed(String error, long now) {
        RequirementDeliveryJobStatus target = attemptNo >= maxAttempts
                ? RequirementDeliveryJobStatus.DEAD_LETTERED
                : RequirementDeliveryJobStatus.FAILED_RETRYABLE;
        return finished(target, error, now);
    }

    public RequirementDeliveryJob cancelled(String reason, long now) {
        return finished(RequirementDeliveryJobStatus.CANCELLED, reason, now);
    }

    public RequirementDeliveryJob deadLettered(String reason, long now) {
        return finished(RequirementDeliveryJobStatus.DEAD_LETTERED, reason, now);
    }

    public RequirementDeliveryJob requeued(int nextMaxAttempts, long now) {
        return new RequirementDeliveryJob(
                jobId, taskId, RequirementDeliveryJobStatus.PENDING, 0, Math.max(1, nextMaxAttempts),
                "", 0L, "", createTimeEpochMillis, now);
    }

    public boolean isExpiredRunning(long now) {
        return status == RequirementDeliveryJobStatus.RUNNING && leaseUntilEpochMillis <= now;
    }

    public boolean isAttemptBudgetExhausted() {
        return attemptNo >= maxAttempts;
    }

    private RequirementDeliveryJob finished(RequirementDeliveryJobStatus target, String error, long now) {
        return new RequirementDeliveryJob(
                jobId, taskId, target, attemptNo, maxAttempts, "", 0L, error, createTimeEpochMillis, now);
    }

    private static String require(String value, String name) {
        String safe = safe(value);
        if (safe.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return safe;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
