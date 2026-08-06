package com.wish.rd.engine.requirement.job;

import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;

import java.util.List;
import java.util.Optional;

/** Persistence and lease port for durable requirement delivery jobs. */
public interface RequirementDeliveryJobStore {

    RequirementDeliveryJob enqueue(RequirementDeliveryJob job);

    Optional<RequirementDeliveryJob> findByTask(String taskId);

    Optional<RequirementDeliveryJob> claim(String taskId, String leaseOwner, long now, long leaseMillis);

    RequirementDeliveryJob heartbeat(String jobId, String leaseOwner, long now, long leaseMillis);

    RequirementDeliveryJob complete(String jobId, String leaseOwner, long now);

    RequirementDeliveryJob fail(String jobId, String leaseOwner, String errorMessage, long now);

    List<RequirementDeliveryJob> recoverable(long now);

    /**
     * Returns non-expired RUNNING jobs used for fair-schedule in-flight accounting.
     * Expired leases are excluded (they appear in {@link #recoverable(long)} instead).
     *
     * @param now epoch millis
     * @return active in-flight jobs
     */
    List<RequirementDeliveryJob> listInFlight(long now);

    /**
     * Cancels a non-terminal job for operator stop, regardless of lease owner.
     *
     * @param taskId task id
     * @param reason operator reason
     * @param now epoch millis
     * @return cancelled job when one existed in a cancellable state
     */
    Optional<RequirementDeliveryJob> cancelByTask(String taskId, String reason, long now);

    /**
     * Marks an expired RUNNING job dead-lettered once attempts are exhausted.
     *
     * @param taskId task id
     * @param now epoch millis
     * @param reason audit reason
     * @return dead-lettered job when the exhausted-expired precondition matched
     */
    Optional<RequirementDeliveryJob> deadLetterExpiredExhausted(String taskId, long now, String reason);
}
