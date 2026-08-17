package com.wish.rd.engine.admin.observability.model;

import com.wish.rd.engine.scheduling.RequirementDeliveryMetrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed-bucket duration histogram in seconds. Bucket counts are cumulative,
 * matching Prometheus histogram exposition.
 *
 * @param count observation count
 * @param sumSeconds sum of samples in seconds
 * @param cumulativeBuckets {@code le} bound → cumulative count, including {@code +Inf}
 */
public record DurationHistogram(
        long count,
        double sumSeconds,
        Map<Double, Long> cumulativeBuckets
) {

    public DurationHistogram {
        count = Math.max(0L, count);
        sumSeconds = Math.max(0D, sumSeconds);
        cumulativeBuckets = cumulativeBuckets == null || cumulativeBuckets.isEmpty()
                ? emptyBuckets()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(cumulativeBuckets));
    }

    /**
     * @return zero-count histogram that still exposes the frozen bucket shape
     */
    public static DurationHistogram empty() {
        return fromMillis(List.of());
    }

    /**
     * Builds a histogram from millisecond samples. Negative and null values are dropped.
     *
     * @param samples durations in milliseconds
     * @return histogram
     */
    public static DurationHistogram fromMillis(List<Long> samples) {
        long[] exclusive = new long[RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS.length + 1];
        long count = 0L;
        double sum = 0D;
        if (samples != null) {
            for (Long sample : samples) {
                if (sample == null || sample < 0L) {
                    continue;
                }
                count++;
                double seconds = sample / 1000.0D;
                sum += seconds;
                exclusive[bucketIndex(seconds)]++;
            }
        }
        return fromExclusive(count, sum, exclusive);
    }

    /**
     * Converts exclusive per-bucket counts (including a {@code +Inf} overflow) into
     * a cumulative Prometheus histogram.
     *
     * @param count observation count
     * @param sumSeconds sum in seconds
     * @param exclusive exclusive counts keyed by {@code le}, including {@link Double#POSITIVE_INFINITY}
     * @return histogram
     */
    public static DurationHistogram fromExclusiveMap(long count, double sumSeconds, Map<Double, Long> exclusive) {
        long[] buckets = new long[RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS.length + 1];
        if (exclusive != null) {
            for (int index = 0; index < RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS.length; index++) {
                buckets[index] = Math.max(0L,
                        exclusive.getOrDefault(RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS[index], 0L));
            }
            buckets[buckets.length - 1] = Math.max(0L, exclusive.getOrDefault(Double.POSITIVE_INFINITY, 0L));
        }
        return fromExclusive(count, sumSeconds, buckets);
    }

    private static DurationHistogram fromExclusive(long count, double sumSeconds, long[] exclusive) {
        Map<Double, Long> cumulative = new LinkedHashMap<>();
        long running = 0L;
        for (int index = 0; index < RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS.length; index++) {
            running += exclusive[index];
            cumulative.put(RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS[index], running);
        }
        running += exclusive[exclusive.length - 1];
        cumulative.put(Double.POSITIVE_INFINITY, running);
        return new DurationHistogram(count, sumSeconds, cumulative);
    }

    private static int bucketIndex(double seconds) {
        for (int index = 0; index < RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS.length; index++) {
            if (seconds <= RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS[index]) {
                return index;
            }
        }
        return RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS.length;
    }

    private static Map<Double, Long> emptyBuckets() {
        Map<Double, Long> buckets = new LinkedHashMap<>();
        for (double bound : RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS) {
            buckets.put(bound, 0L);
        }
        buckets.put(Double.POSITIVE_INFINITY, 0L);
        return java.util.Collections.unmodifiableMap(buckets);
    }
}
