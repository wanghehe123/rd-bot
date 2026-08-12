package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RequirementDeliveryJobRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementDeliveryJobMapper;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus;
import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRequirementDeliveryJobStore implements RequirementDeliveryJobStore {

    private final RequirementDeliveryJobMapper mapper;

    public PostgresRequirementDeliveryJobStore(RequirementDeliveryJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RequirementDeliveryJob enqueue(RequirementDeliveryJob job) {
        mapper.enqueue(toRow(job));
        return findByTask(job.taskId()).orElseThrow();
    }

    @Override
    public Optional<RequirementDeliveryJob> findByTask(String taskId) {
        return Optional.ofNullable(mapper.findByTask(PostgresPersistenceSupport.parseId(taskId))).map(this::toJob);
    }

    @Override
    public Optional<RequirementDeliveryJob> claim(String taskId, String leaseOwner, long now, long leaseMillis) {
        OffsetDateTime timestamp = PostgresPersistenceSupport.toDateTime(now);
        return Optional.ofNullable(mapper.claim(
                PostgresPersistenceSupport.parseId(taskId), leaseOwner, timestamp,
                PostgresPersistenceSupport.toDateTime(now + Math.max(1L, leaseMillis)))).map(this::toJob);
    }

    @Override
    public RequirementDeliveryJob heartbeat(String jobId, String leaseOwner, long now, long leaseMillis) {
        return required(mapper.heartbeat(
                PostgresPersistenceSupport.parseId(jobId), leaseOwner, PostgresPersistenceSupport.toDateTime(now),
                PostgresPersistenceSupport.toDateTime(now + Math.max(1L, leaseMillis))), jobId);
    }

    @Override
    public RequirementDeliveryJob complete(String jobId, String leaseOwner, long now) {
        return required(mapper.complete(
                PostgresPersistenceSupport.parseId(jobId), leaseOwner, PostgresPersistenceSupport.toDateTime(now)), jobId);
    }

    @Override
    public RequirementDeliveryJob fail(String jobId, String leaseOwner, String errorMessage, long now) {
        return required(mapper.fail(
                PostgresPersistenceSupport.parseId(jobId), leaseOwner, errorMessage == null ? "" : errorMessage,
                PostgresPersistenceSupport.toDateTime(now)), jobId);
    }

    @Override
    public List<RequirementDeliveryJob> recoverable(long now) {
        return recoverable(now, 256);
    }

    @Override
    public List<RequirementDeliveryJob> recoverable(long now, int limit) {
        return mapper.recoverable(
                        PostgresPersistenceSupport.toDateTime(now), Math.max(1, limit))
                .stream().map(this::toJob).toList();
    }

    @Override
    public List<RequirementDeliveryJob> listInFlight(long now) {
        return mapper.listInFlight(PostgresPersistenceSupport.toDateTime(now)).stream().map(this::toJob).toList();
    }

    @Override
    public Optional<RequirementDeliveryJob> cancelByTask(String taskId, String reason, long now) {
        return Optional.ofNullable(mapper.cancelByTask(
                PostgresPersistenceSupport.parseId(taskId),
                reason == null ? "" : reason,
                PostgresPersistenceSupport.toDateTime(now))).map(this::toJob);
    }

    @Override
    public Optional<RequirementDeliveryJob> deadLetterExpiredExhausted(String taskId, long now, String reason) {
        return Optional.ofNullable(mapper.deadLetterExpiredExhausted(
                PostgresPersistenceSupport.parseId(taskId),
                PostgresPersistenceSupport.toDateTime(now),
                reason == null ? "" : reason)).map(this::toJob);
    }

    private RequirementDeliveryJob required(RequirementDeliveryJobRow row, String jobId) {
        if (row == null) {
            throw new IllegalStateException("requirement delivery job lease update failed: " + jobId);
        }
        return toJob(row);
    }

    private RequirementDeliveryJobRow toRow(RequirementDeliveryJob job) {
        RequirementDeliveryJobRow row = new RequirementDeliveryJobRow();
        row.id = PostgresPersistenceSupport.parseId(job.jobId());
        row.taskId = PostgresPersistenceSupport.parseId(job.taskId());
        row.status = job.status().name();
        row.attemptNo = job.attemptNo();
        row.maxAttempts = job.maxAttempts();
        row.leaseOwner = job.leaseOwner();
        row.leaseUntil = job.leaseUntilEpochMillis() <= 0L
                ? null : PostgresPersistenceSupport.toDateTime(job.leaseUntilEpochMillis());
        row.errorMessage = job.errorMessage();
        row.createdAt = PostgresPersistenceSupport.toDateTime(job.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(job.updateTimeEpochMillis());
        return row;
    }

    private RequirementDeliveryJob toJob(RequirementDeliveryJobRow row) {
        return new RequirementDeliveryJob(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                RequirementDeliveryJobStatus.valueOf(row.status),
                row.attemptNo,
                row.maxAttempts,
                row.leaseOwner,
                row.leaseUntil == null ? 0L : PostgresPersistenceSupport.toEpochMillis(row.leaseUntil),
                row.errorMessage,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt));
    }
}
