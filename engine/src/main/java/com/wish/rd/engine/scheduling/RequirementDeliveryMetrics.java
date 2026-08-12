package com.wish.rd.engine.scheduling;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead scheduler metrics owned by the dispatch loop. The snapshot is intentionally
 * dependency-free so the bootstrap Prometheus adapter can expose it without coupling engine to a
 * metrics SDK.
 */
public final class RequirementDeliveryMetrics {

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
    private final EnumMap<ScheduleResourceClass, LongAdder> resourceClaims =
            new EnumMap<>(ScheduleResourceClass.class);
    private final EnumMap<ScheduleResourceClass, Double> resourceUtilizationTotals =
            new EnumMap<>(ScheduleResourceClass.class);
    private final EnumMap<ScheduleResourceClass, Long> resourceUtilizationSamples =
            new EnumMap<>(ScheduleResourceClass.class);
    private final List<Long> queueAgeMillis = new ArrayList<>();
    private final Map<String, LongAdder> projectClaims = new HashMap<>();
    private long oldestQueueAgeMillis;

    public RequirementDeliveryMetrics() {
        for (ScheduleResourceClass resource : ScheduleResourceClass.values()) {
            resourceClaims.put(resource, new LongAdder());
        }
    }

    public synchronized void recordEnqueued(RequirementStageCommand command, long nowEpochMillis) {
        if (command == null) {
            return;
        }
        enqueued.increment();
        recordQueueAge(command.createdAtEpochMillis(), nowEpochMillis);
    }

    public synchronized void recordClaimed(RequirementStageCommand command, long nowEpochMillis) {
        if (command == null) {
            return;
        }
        claimed.increment();
        for (ScheduleResourceClass resource : FairRequirementDeliveryClaimPlanner.resourceRequirementsFor(command)) {
            resourceClaims.getOrDefault(resource, resourceClaims.get(ScheduleResourceClass.GENERIC))
                    .increment();
        }
        projectClaims.computeIfAbsent(command.projectId(), ignored -> new LongAdder()).increment();
        projectWaitSamples.increment();
        projectWaitMillis.add(Math.max(0L, nowEpochMillis - command.createdAtEpochMillis()));
        recordQueueAge(command.createdAtEpochMillis(), nowEpochMillis);
    }

    public void recordCompleted() {
        completed.increment();
    }

    /** Records completion and the observed service interval for project wait-ratio accounting. */
    public void recordCompleted(RequirementStageCommand command, long nowEpochMillis) {
        completed.increment();
        if (command != null) {
            serviceMillis.add(Math.max(0L, nowEpochMillis - command.updatedAtEpochMillis()));
        }
    }

    public void recordRetry() {
        retries.increment();
    }

    public void recordLeaseLost() {
        leaseLost.increment();
    }

    public void recordQueueRejected() {
        queueRejections.increment();
    }

    public void recordInFlight(int count) {
        inFlightSamples.add(Math.max(0, count));
    }

    /** Records per-resource utilization as the fraction of the configured quota in use. */
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
            double ratio = capacity <= 0 ? 0D : Math.min(1D, (double) current / capacity);
            resourceUtilizationTotals.merge(resource, ratio, Double::sum);
            resourceUtilizationSamples.merge(resource, 1L, Long::sum);
        }
        inFlightSamples.add(total);
    }

    public synchronized Snapshot snapshot() {
        List<Long> ages = new ArrayList<>(queueAgeMillis);
        Collections.sort(ages);
        return new Snapshot(
                enqueued.sum(), claimed.sum(), completed.sum(), retries.sum(), leaseLost.sum(),
                queueRejections.sum(), percentile(ages, 0.50d), percentile(ages, 0.95d),
                oldestQueueAgeMillis, waitRatio(), inFlightSamples.sum(), resourceCounts(resourceClaims),
                resourceUtilization(), projectCounts(projectClaims));
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

    private void recordQueueAge(long createdAtEpochMillis, long nowEpochMillis) {
        long age = Math.max(0L, nowEpochMillis - createdAtEpochMillis);
        queueAgeMillis.add(age);
        oldestQueueAgeMillis = Math.max(oldestQueueAgeMillis, age);
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(values.size() - 1, index)));
    }

    private static Map<ScheduleResourceClass, Long> resourceCounts(
            Map<ScheduleResourceClass, LongAdder> values
    ) {
        Map<ScheduleResourceClass, Long> result = new EnumMap<>(ScheduleResourceClass.class);
        values.forEach((key, value) -> result.put(key, value.sum()));
        return Map.copyOf(result);
    }

    private static Map<String, Long> projectCounts(Map<String, LongAdder> values) {
        Map<String, Long> result = new HashMap<>();
        values.forEach((key, value) -> result.put(key, value.sum()));
        return Map.copyOf(result);
    }

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
            Map<String, Long> projectClaims
    ) {
    }
}
