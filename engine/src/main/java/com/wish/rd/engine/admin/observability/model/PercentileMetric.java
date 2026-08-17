package com.wish.rd.engine.admin.observability.model;

import java.util.List;

/**
 * Latency percentiles for one phase or role. P99 is omitted when samples are insufficient.
 *
 * @param available false when the source is down
 * @param noSample true when the window has zero eligible samples
 * @param sampleCount eligible samples
 * @param p50Seconds P50 or NaN when noSample
 * @param p95Seconds P95 or NaN when noSample
 * @param p99Seconds P99 or NaN when insufficient / noSample
 * @param meanSeconds mean or NaN when noSample
 * @param p99Insufficient true when sampleCount &lt; configured minimum
 */
public record PercentileMetric(
        boolean available,
        boolean noSample,
        long sampleCount,
        double p50Seconds,
        double p95Seconds,
        double p99Seconds,
        double meanSeconds,
        boolean p99Insufficient
) {

    /**
     * @return unavailable metric
     */
    public static PercentileMetric unavailable() {
        return new PercentileMetric(false, false, 0L, Double.NaN, Double.NaN, Double.NaN, Double.NaN, true);
    }

    /**
     * Builds percentiles from duration samples in milliseconds.
     *
     * @param available source health
     * @param samples sorted or unsorted durations in millis
     * @param minP99Samples P99 gate
     * @return metric
     */
    public static PercentileMetric fromMillis(boolean available, List<Long> samples, int minP99Samples) {
        if (!available) {
            return unavailable();
        }
        List<Long> values = samples == null ? List.of() : samples.stream()
                .filter(value -> value != null && value >= 0L)
                .sorted()
                .toList();
        if (values.isEmpty()) {
            return new PercentileMetric(true, true, 0L, Double.NaN, Double.NaN, Double.NaN, Double.NaN, true);
        }
        boolean insufficient = values.size() < minP99Samples;
        return new PercentileMetric(
                true,
                false,
                values.size(),
                percentile(values, 0.50D) / 1000.0D,
                percentile(values, 0.95D) / 1000.0D,
                insufficient ? Double.NaN : percentile(values, 0.99D) / 1000.0D,
                values.stream().mapToLong(Long::longValue).average().orElse(Double.NaN) / 1000.0D,
                insufficient
        );
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}
