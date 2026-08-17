package com.wish.rd.engine.admin.observability.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Project-scoped delivery overview for one allowlisted window.
 *
 * @param projectId blank when all projects
 * @param window canonical window token
 * @param windowStartEpochMillis window start
 * @param windowEndEpochMillis window end
 * @param generatedAtEpochMillis response time
 * @param acceptedCount tasks accepted in window
 * @param terminalCount terminal tasks in window
 * @param runningCount running tasks overlapping the window
 * @param successRate terminal success / terminal judged
 * @param qaPassRate finished QA success / finished QA
 * @param prCreationRate terminal with PR / terminal
 * @param humanInterventionRate FAILED_NEEDS_HUMAN / terminal
 * @param retryRate retried stage runs / finished stage runs
 * @param unknownFailureRate unknown category / failed terminal
 * @param endToEndLatency completed e2e
 * @param phaseLatency phase name → percentiles
 * @param capacities resource gauges
 * @param queueBacklog durable waiting commands
 * @param oldestQueueAgeSeconds durable oldest wait; NaN when unavailable
 * @param invalidObservations dropped samples
 * @param dataQuality per-source freshness
 * @param durationHistograms phase or role histograms in seconds
 * @param schedulerLive process-window scheduler counters
 * @param usageBuckets deduplicated token/cost buckets
 * @param stageTotals role/status counts
 * @param failureCategories folded failure categories
 */
public record DeliveryObservabilityOverview(
        String projectId,
        String window,
        long windowStartEpochMillis,
        long windowEndEpochMillis,
        long generatedAtEpochMillis,
        long acceptedCount,
        long terminalCount,
        long runningCount,
        RatioMetric successRate,
        RatioMetric qaPassRate,
        RatioMetric prCreationRate,
        RatioMetric humanInterventionRate,
        RatioMetric retryRate,
        RatioMetric unknownFailureRate,
        PercentileMetric endToEndLatency,
        Map<String, PercentileMetric> phaseLatency,
        List<CapacityMetric> capacities,
        long queueBacklog,
        double oldestQueueAgeSeconds,
        long invalidObservations,
        List<DataQualityStatus> dataQuality,
        @JsonIgnore Map<String, DurationHistogram> durationHistograms,
        SchedulerLiveMetrics schedulerLive,
        List<UsageBucket> usageBuckets,
        List<RoleStatusCount> stageTotals,
        Map<String, Long> failureCategories
) {

    public DeliveryObservabilityOverview {
        projectId = projectId == null ? "" : projectId;
        window = window == null ? DeliveryObservabilityWindow.ONE_DAY.token() : window;
        phaseLatency = phaseLatency == null ? Map.of() : Map.copyOf(phaseLatency);
        capacities = capacities == null ? List.of() : List.copyOf(capacities);
        dataQuality = dataQuality == null ? List.of() : List.copyOf(dataQuality);
        durationHistograms = durationHistograms == null ? Map.of() : Map.copyOf(durationHistograms);
        schedulerLive = schedulerLive == null ? SchedulerLiveMetrics.unavailable() : schedulerLive;
        usageBuckets = usageBuckets == null ? List.of() : List.copyOf(usageBuckets);
        stageTotals = stageTotals == null ? List.of() : List.copyOf(stageTotals);
        failureCategories = failureCategories == null ? Map.of() : Map.copyOf(failureCategories);
        successRate = successRate == null ? RatioMetric.unavailable() : successRate;
        qaPassRate = qaPassRate == null ? RatioMetric.unavailable() : qaPassRate;
        prCreationRate = prCreationRate == null ? RatioMetric.unavailable() : prCreationRate;
        humanInterventionRate = humanInterventionRate == null ? RatioMetric.unavailable() : humanInterventionRate;
        retryRate = retryRate == null ? RatioMetric.unavailable() : retryRate;
        unknownFailureRate = unknownFailureRate == null ? RatioMetric.unavailable() : unknownFailureRate;
        endToEndLatency = endToEndLatency == null ? PercentileMetric.unavailable() : endToEndLatency;
    }

    /**
     * @param query query
     * @param generatedAt generated time
     * @param quality data quality
     * @return unavailable overview without fake zeros
     */
    public static DeliveryObservabilityOverview unavailable(
            DeliveryObservabilityQuery query,
            Instant generatedAt,
            List<DataQualityStatus> quality
    ) {
        DeliveryObservabilityQuery safe = query;
        Instant now = generatedAt == null ? Instant.EPOCH : generatedAt;
        return new DeliveryObservabilityOverview(
                safe.projectId(),
                safe.window().token(),
                safe.windowStart().toEpochMilli(),
                safe.now().toEpochMilli(),
                now.toEpochMilli(),
                0L, 0L, 0L,
                RatioMetric.unavailable(),
                RatioMetric.unavailable(),
                RatioMetric.unavailable(),
                RatioMetric.unavailable(),
                RatioMetric.unavailable(),
                RatioMetric.unavailable(),
                PercentileMetric.unavailable(),
                Map.of(),
                List.of(),
                0L,
                Double.NaN,
                0L,
                quality,
                Map.of(),
                SchedulerLiveMetrics.unavailable(),
                List.of(),
                List.of(),
                Map.of()
        );
    }
}
