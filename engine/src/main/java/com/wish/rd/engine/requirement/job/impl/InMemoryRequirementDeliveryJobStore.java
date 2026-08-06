package com.wish.rd.engine.requirement.job.impl;

import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thread-safe in-memory lease store used by local development and focused tests. */
public final class InMemoryRequirementDeliveryJobStore implements RequirementDeliveryJobStore {

    private final Map<String, RequirementDeliveryJob> jobsById = new LinkedHashMap<>();
    private final Map<String, String> jobIdByTask = new LinkedHashMap<>();

    @Override
    public synchronized RequirementDeliveryJob enqueue(RequirementDeliveryJob job) {
        String existingId = jobIdByTask.get(job.taskId());
        if (existingId != null) {
            RequirementDeliveryJob existing = jobsById.get(existingId);
            if (existing.status() == RequirementDeliveryJobStatus.SUCCEEDED
                    || existing.status() == RequirementDeliveryJobStatus.DEAD_LETTERED
                    || existing.status() == RequirementDeliveryJobStatus.CANCELLED) {
                RequirementDeliveryJob requeued = existing.requeued(job.maxAttempts(), job.updateTimeEpochMillis());
                jobsById.put(existingId, requeued);
                return requeued;
            }
            return existing;
        }
        jobsById.put(job.jobId(), job);
        jobIdByTask.put(job.taskId(), job.jobId());
        return job;
    }

    @Override
    public synchronized Optional<RequirementDeliveryJob> findByTask(String taskId) {
        return Optional.ofNullable(jobIdByTask.get(safe(taskId))).map(jobsById::get);
    }

    @Override
    public synchronized Optional<RequirementDeliveryJob> claim(
            String taskId, String leaseOwner, long now, long leaseMillis) {
        RequirementDeliveryJob current = findByTask(taskId).orElse(null);
        if (current == null || !claimable(current, now) || current.isAttemptBudgetExhausted()) {
            return Optional.empty();
        }
        RequirementDeliveryJob claimed = current.claimed(requireOwner(leaseOwner), now + Math.max(1L, leaseMillis), now);
        jobsById.put(claimed.jobId(), claimed);
        return Optional.of(claimed);
    }

    @Override
    public synchronized RequirementDeliveryJob heartbeat(
            String jobId, String leaseOwner, long now, long leaseMillis) {
        RequirementDeliveryJob current = ownedRunning(jobId, leaseOwner);
        RequirementDeliveryJob updated = current.heartbeat(now + Math.max(1L, leaseMillis), now);
        jobsById.put(updated.jobId(), updated);
        return updated;
    }

    @Override
    public synchronized RequirementDeliveryJob complete(String jobId, String leaseOwner, long now) {
        RequirementDeliveryJob updated = ownedRunning(jobId, leaseOwner).succeeded(now);
        jobsById.put(updated.jobId(), updated);
        return updated;
    }

    @Override
    public synchronized RequirementDeliveryJob fail(
            String jobId, String leaseOwner, String errorMessage, long now) {
        RequirementDeliveryJob updated = ownedRunning(jobId, leaseOwner).failed(errorMessage, now);
        jobsById.put(updated.jobId(), updated);
        return updated;
    }

    @Override
    public synchronized List<RequirementDeliveryJob> recoverable(long now) {
        return jobsById.values().stream()
                .filter(job -> recoverableCandidate(job, now))
                .toList();
    }

    @Override
    public synchronized List<RequirementDeliveryJob> listInFlight(long now) {
        return jobsById.values().stream()
                .filter(job -> job.status() == RequirementDeliveryJobStatus.RUNNING)
                .filter(job -> !job.isExpiredRunning(now))
                .toList();
    }

    @Override
    public synchronized Optional<RequirementDeliveryJob> cancelByTask(String taskId, String reason, long now) {
        RequirementDeliveryJob current = findByTask(taskId).orElse(null);
        if (current == null || !cancellable(current.status())) {
            return Optional.empty();
        }
        RequirementDeliveryJob cancelled = current.cancelled(reason, now);
        jobsById.put(cancelled.jobId(), cancelled);
        return Optional.of(cancelled);
    }

    @Override
    public synchronized Optional<RequirementDeliveryJob> deadLetterExpiredExhausted(
            String taskId, long now, String reason) {
        RequirementDeliveryJob current = findByTask(taskId).orElse(null);
        if (current == null || !current.isExpiredRunning(now) || !current.isAttemptBudgetExhausted()) {
            return Optional.empty();
        }
        RequirementDeliveryJob dead = current.deadLettered(reason, now);
        jobsById.put(dead.jobId(), dead);
        return Optional.of(dead);
    }

    private boolean recoverableCandidate(RequirementDeliveryJob job, long now) {
        if (job.status() == RequirementDeliveryJobStatus.PENDING
                || job.status() == RequirementDeliveryJobStatus.FAILED_RETRYABLE) {
            return !job.isAttemptBudgetExhausted();
        }
        return job.isExpiredRunning(now);
    }

    private boolean claimable(RequirementDeliveryJob job, long now) {
        return job.status() == RequirementDeliveryJobStatus.PENDING
                || job.status() == RequirementDeliveryJobStatus.FAILED_RETRYABLE
                || job.isExpiredRunning(now);
    }

    private boolean cancellable(RequirementDeliveryJobStatus status) {
        return status == RequirementDeliveryJobStatus.PENDING
                || status == RequirementDeliveryJobStatus.RUNNING
                || status == RequirementDeliveryJobStatus.FAILED_RETRYABLE;
    }

    private RequirementDeliveryJob ownedRunning(String jobId, String leaseOwner) {
        RequirementDeliveryJob current = jobsById.get(safe(jobId));
        if (current == null) {
            throw new IllegalArgumentException("requirement delivery job not found: " + safe(jobId));
        }
        if (current.status() != RequirementDeliveryJobStatus.RUNNING
                || !current.leaseOwner().equals(requireOwner(leaseOwner))) {
            throw new IllegalStateException("requirement delivery job lease is not owned: " + current.jobId());
        }
        return current;
    }

    private String requireOwner(String owner) {
        String safe = safe(owner);
        if (safe.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        return safe;
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
