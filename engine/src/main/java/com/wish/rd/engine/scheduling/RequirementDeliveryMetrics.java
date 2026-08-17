package com.wish.rd.engine.scheduling;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded scheduler metrics owned by the dispatch loop.
 *
 * <p>Queue wait and service time use a fixed histogram. Process metrics never
 * retain per-task or per-project maps. Fair scheduling must not read this object
 * to decide admission.
 */
public final class RequirementDeliveryMetrics {

    /** Frozen WP-2 duration buckets in seconds, plus a trailing +Inf bucket. */
    public static final double[] DURATION_BUCKET_SECONDS = {
            0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300, 600, 1800, 3600
    };

    private final long processStartedAtEpochMillis;
    private final LongAdder enqueued = new LongAdder();
    private final LongAdder claimed = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder leaseLost = new LongAdder();
    private final LongAdder queueRejections = new LongAdder();
    private final LongAdder inFlightSamples = new LongAdder();
    private final LongAdder projectWaitSamples = new LongAdder();
    private final LongAdder projectWaitMillis = new LongAdder();
    private final LongAdder serviceMillis = new LongAdder();
    private final LongAdder queueWaitSamples = new LongAdder();
    private final LongAdder serviceSamples = new LongAdder();
    private final EnumMap<ScheduleResourceClass, LongAdder> resourceClaims =
            new EnumMap<>(ScheduleResourceClass.class);
    private final EnumMap<ScheduleResourceClass, Double> resourceUtilizationTotals =
            new EnumMap<>(ScheduleResourceClass.class);
    private final EnumMap<ScheduleResourceClass, Long> resourceUtilizationSamples =
            new EnumMap<>(ScheduleResourceClass.class);
    private final long[] queueWaitBuckets = new long[DURATION_BUCKET_SECONDS.length + 1];
    private final long[] serviceBuckets = new long[DURATION_BUCKET_SECONDS.length + 1];
    private final EnumMap<ScheduleResourceClass, Integer> currentInFlightByResource =
            new EnumMap<>(ScheduleResourceClass.class);
    private final EnumMap<ScheduleResourceClass, Integer> currentCapacityByResource =
            new EnumMap<>(ScheduleResourceClass.class);
    private int currentInFlight;
    private long oldestQueueAgeMillis;

    /**
     * Creates metrics using the current wall clock as process start.
     */
    public RequirementDeliveryMetrics() {
        this(System.currentTimeMillis());
    }

    /**
     * Creates metrics with an explicit process-start timestamp for restart tests.
     *
     * @param processStartedAtEpochMillis process start time
     */
    public RequirementDeliveryMetrics(long processStartedAtEpochMillis) {
        this.processStartedAtEpochMillis = processStartedAtEpochMillis;
        for (ScheduleResourceClass resource : ScheduleResourceClass.values()) {
            resourceClaims.put(resource, new LongAdder());
            currentInFlightByResource.put(resource, 0);
            currentCapacityByResource.put(resource, 0);
        }
    }

    /**
     * Records a newly visible command and its queue age.
     *
     * @param command pending command
     * @param nowEpochMillis observation time
     */
    public synchronized void recordEnqueued(RequirementStageCommand command, long nowEpochMillis) {
        if (command == null) {
            return;
        }
        enqueued.increment();
        recordQueueWait(command.createdAtEpochMillis(), nowEpochMillis);
    }

    /**
     * Records a successful claim and the wait from enqueue to claim.
     *
     * @param command claimed command
     * @param nowEpochMillis claim time
     */
    public synchronized void recordClaimed(RequirementStageCommand command, long nowEpochMillis) {
        if (command == null) {
            return;
        }
        claimed.increment();
        for (ScheduleResourceClass resource : FairRequirementDeliveryClaimPlanner.resourceRequirementsFor(command)) {
            resourceClaims.getOrDefault(resource, resourceClaims.get(ScheduleResourceClass.GENERIC))
                    .increment();
        }
        projectWaitSamples.increment();
        projectWaitMillis.add(Math.max(0L, nowEpochMillis - command.createdAtEpochMillis()));
        recordQueueWait(command.createdAtEpochMillis(), nowEpochMillis);
    }

    /**
     * Records a completion without a service interval.
     */
    public void recordCompleted() {
        completed.increment();
    }

    /**
     * Records completion and the observed service interval.
     *
     * @param command completed command
     * @param nowEpochMillis completion time
     */
    public synchronized void recordCompleted(RequirementStageCommand command, long nowEpochMillis) {
        completed.increment();
        if (command != null) {
            long service = Math.max(0L, nowEpochMillis - command.updatedAtEpochMillis());
            serviceMillis.add(service);
            recordDuration(serviceBuckets, serviceSamples, service);
        }
    }

    /**
     * Records a retry.
     */
    public void recordRetry() {
        retries.increment();
    }

    /**
     * Records a lost lease.
     */
    public void recordLeaseLost() {
        leaseLost.increment();
    }

    /**
     * Records a queue or thread-pool rejection. Must not change command results.
     */
    public void recordQueueRejected() {
        queueRejections.increment();
    }

    /**
     * Records a scalar in-flight observation.
     *
     * @param count current in-flight commands
     */
    public synchronized void recordInFlight(int count) {
        currentInFlight = Math.max(0, count);
        inFlightSamples.increment();
    }

    /**
     * Records per-resource utilization as the fraction of the configured quota in use.
     *
     * @param inFlightByResource current in-flight by resource
     * @param limits configured caps; unused for admission
     */
    public synchronized void recordInFlight(
            Map<ScheduleResourceClass, Integer> inFlightByResource,
            FairScheduleLimits limits
    ) {
        if (limits == null) {
            return;
        }
        int total = 0;
        for (ScheduleResourceClass resource : ScheduleResourceClass.values()) {
            int current = Math.max(0, inFlightByResource == null
                    ? 0 : inFlightByResource.getOrDefault(resource, 0));
            total += current;
            int capacity = switch (resource) {
                case DOCKER -> limits.maxDocker();
                case BROWSER_QA -> limits.maxBrowserQa();
                case PROVIDER -> limits.maxProvider();
                case GENERIC -> limits.batchSize();
            };
            currentInFlightByResource.put(resource, current);
            currentCapacityByResource.put(resource, capacity);
            double ratio = capacity <= 0 ? 0D : Math.min(1D, (double) current / capacity);
            resourceUtilizationTotals.merge(resource, ratio, Double::sum);
            resourceUtilizationSamples.merge(resource, 1L, Long::sum);
        }
        currentInFlight = total;
        inFlightSamples.increment();
    }

    /**
     * Returns an immutable process-window snapshot.
     *
     * @return snapshot
     */
    public synchronized Snapshot snapshot() {
        long waitSamples = queueWaitSamples.sum();
        return new Snapshot(
                enqueued.sum(), claimed.sum(), completed.sum(), retries.sum(), leaseLost.sum(),
                queueRejections.sum(), percentileMillis(queueWaitBuckets, waitSamples, 0.50d),
                percentileMillis(queueWaitBuckets, waitSamples, 0.95d),
                oldestQueueAgeMillis, waitRatio(), currentInFlight, resourceCounts(resourceClaims),
                resourceUtilization(), Map.of(), processStartedAtEpochMillis, waitSamples == 0L,
                waitSamples, copyBuckets(queueWaitBuckets), copyBuckets(serviceBuckets),
                Map.copyOf(currentInFlightByResource), Map.copyOf(currentCapacityByResource)
        );
    }

    /**
     * @return number of retained queue-wait buckets including +Inf
     */
    public int queueWaitBucketCount() {
        return queueWaitBuckets.length;
    }

    private double waitRatio() {
        long waiting = projectWaitMillis.sum();
        long observed = waiting + serviceMillis.sum();
        return observed == 0L ? 0D : Math.min(1D, (double) waiting / observed);
    }

    private Map<ScheduleResourceClass, Double> resourceUtilization() {
        Map<ScheduleResourceClass, Double> result = new EnumMap<>(ScheduleResourceClass.class);
        for (ScheduleResourceClass resource : ScheduleResourceClass.values()) {
            long samples = resourceUtilizationSamples.getOrDefault(resource, 0L);
            result.put(resource, samples == 0L
                    ? 0D : resourceUtilizationTotals.getOrDefault(resource, 0D) / samples);
        }
        return Map.copyOf(result);
    }

    private void recordQueueWait(long createdAtEpochMillis, long nowEpochMillis) {
        long age = Math.max(0L, nowEpochMillis - createdAtEpochMillis);
        recordDuration(queueWaitBuckets, queueWaitSamples, age);
        oldestQueueAgeMillis = Math.max(oldestQueueAgeMillis, age);
    }

    private static void recordDuration(long[] buckets, LongAdder samples, long durationMillis) {
        samples.increment();
        double seconds = durationMillis / 1000.0D;
        int index = buckets.length - 1;
        for (int i = 0; i < DURATION_BUCKET_SECONDS.length; i++) {
            if (seconds <= DURATION_BUCKET_SECONDS[i]) {
                index = i;
                break;
            }
        }
        buckets[index]++;
    }

    private static long percentileMillis(long[] buckets, long samples, double percentile) {
        if (samples <= 0L) {
            return 0L;
        }
        long target = Math.max(1L, (long) Math.ceil(percentile * samples));
        long cumulative = 0L;
        for (int i = 0; i < DURATION_BUCKET_SECONDS.length; i++) {
            cumulative += buckets[i];
            if (cumulative >= target) {
                return Math.round(DURATION_BUCKET_SECONDS[i] * 1000.0D);
            }
        }
        return Long.MAX_VALUE;
    }

    private static Map<ScheduleResourceClass, Long> resourceCounts(
            Map<ScheduleResourceClass, LongAdder> values
    ) {
        Map<ScheduleResourceClass, Long> result = new EnumMap<>(ScheduleResourceClass.class);
        values.forEach((key, value) -> result.put(key, value.sum()));
        return Map.copyOf(result);
    }

    private static Map<Double, Long> copyBuckets(long[] buckets) {
        Map<Double, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < DURATION_BUCKET_SECONDS.length; i++) {
            result.put(DURATION_BUCKET_SECONDS[i], buckets[i]);
        }
        result.put(Double.POSITIVE_INFINITY, buckets[buckets.length - 1]);
        return Map.copyOf(result);
    }

    /**
     * Immutable process-window scheduler snapshot.
     *
     * @param enqueued enqueue counter
     * @param claimed claim counter
     * @param completed completion counter
     * @param retries retry counter
     * @param leaseLost lease-loss counter
     * @param queueRejections rejection counter
     * @param queueAgeP50Millis histogram P50 of queue wait
     * @param queueAgeP95Millis histogram P95 of queue wait
     * @param oldestQueueAgeMillis max observed wait in this process
     * @param projectWaitRatio wait / (wait+service)
     * @param inFlightSamples current in-flight gauge
     * @param resourceClaims cumulative claims by resource
     * @param resourceUtilization mean utilization by resource
     * @param projectClaims always empty; project dimensions are forbidden
     * @param processStartedAtEpochMillis process start
     * @param processWindowWarmUp true when no queue-wait samples exist yet
     * @param queueWaitSampleCount histogram observations
     * @param queueWaitBuckets le → count
     * @param serviceBuckets le → count
     * @param currentInFlightByResource current in-flight
     * @param currentCapacityByResource last observed caps
     */
    public record Snapshot(
            long enqueued,
            long claimed,
            long completed,
            long retries,
            long leaseLost,
            long queueRejections,
            long queueAgeP50Millis,
            long queueAgeP95Millis,
            long oldestQueueAgeMillis,
            double projectWaitRatio,
            long inFlightSamples,
            Map<ScheduleResourceClass, Long> resourceClaims,
            Map<ScheduleResourceClass, Double> resourceUtilization,
            Map<String, Long> projectClaims,
            long processStartedAtEpochMillis,
            boolean processWindowWarmUp,
            long queueWaitSampleCount,
            Map<Double, Long> queueWaitBuckets,
            Map<Double, Long> serviceBuckets,
            Map<ScheduleResourceClass, Integer> currentInFlightByResource,
            Map<ScheduleResourceClass, Integer> currentCapacityByResource
    ) {
        public Snapshot {
            resourceClaims = resourceClaims == null ? Map.of() : Map.copyOf(resourceClaims);
            resourceUtilization = resourceUtilization == null ? Map.of() : Map.copyOf(resourceUtilization);
            projectClaims = Map.of();
            queueWaitBuckets = queueWaitBuckets == null ? Map.of() : Map.copyOf(queueWaitBuckets);
            serviceBuckets = serviceBuckets == null ? Map.of() : Map.copyOf(serviceBuckets);
            currentInFlightByResource = currentInFlightByResource == null
                    ? Map.of() : Map.copyOf(currentInFlightByResource);
            currentCapacityByResource = currentCapacityByResource == null
                    ? Map.of() : Map.copyOf(currentCapacityByResource);
        }
    }
}
