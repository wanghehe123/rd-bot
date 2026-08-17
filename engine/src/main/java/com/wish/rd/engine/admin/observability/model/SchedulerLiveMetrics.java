package com.wish.rd.engine.admin.observability.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.wish.rd.engine.scheduling.RequirementDeliveryMetrics;

/**
 * Process-window scheduler counters. Durable backlog lives on the overview itself.
 *
 * @param available live snapshot was loaded
 * @param warmUp true when this process has not observed a queue-wait sample yet
 * @param claimed claim counter
 * @param retries retry counter
 * @param leaseLost lease-loss counter
 * @param queueRejections thread-pool / queue rejection counter
 * @param queueWait claim−created histogram
 * @param service complete−claim histogram
 */
public record SchedulerLiveMetrics(
        boolean available,
        boolean warmUp,
        long claimed,
        long retries,
        long leaseLost,
        long queueRejections,
        @JsonIgnore DurationHistogram queueWait,
        @JsonIgnore DurationHistogram service
) {

    public SchedulerLiveMetrics {
        claimed = Math.max(0L, claimed);
        retries = Math.max(0L, retries);
        leaseLost = Math.max(0L, leaseLost);
        queueRejections = Math.max(0L, queueRejections);
        queueWait = queueWait == null ? DurationHistogram.empty() : queueWait;
        service = service == null ? DurationHistogram.empty() : service;
    }

    /**
     * @return missing live scheduler
     */
    public static SchedulerLiveMetrics unavailable() {
        return new SchedulerLiveMetrics(
                false, true, 0L, 0L, 0L, 0L,
                DurationHistogram.empty(), DurationHistogram.empty());
    }

    /**
     * @param snapshot process-window snapshot
     * @return live metrics or unavailable
     */
    public static SchedulerLiveMetrics from(RequirementDeliveryMetrics.Snapshot snapshot) {
        if (snapshot == null) {
            return unavailable();
        }
        return new SchedulerLiveMetrics(
                true,
                snapshot.processWindowWarmUp(),
                snapshot.claimed(),
                snapshot.retries(),
                snapshot.leaseLost(),
                snapshot.queueRejections(),
                DurationHistogram.fromExclusiveMap(
                        snapshot.queueWaitSampleCount(),
                        0D,
                        snapshot.queueWaitBuckets()),
                DurationHistogram.fromExclusiveMap(
                        exclusiveCount(snapshot.serviceBuckets()),
                        0D,
                        snapshot.serviceBuckets())
        );
    }

    private static long exclusiveCount(java.util.Map<Double, Long> exclusive) {
        if (exclusive == null || exclusive.isEmpty()) {
            return 0L;
        }
        return exclusive.values().stream().mapToLong(value -> value == null ? 0L : value).sum();
    }
}
