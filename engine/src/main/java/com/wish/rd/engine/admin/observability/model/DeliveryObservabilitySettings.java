package com.wish.rd.engine.admin.observability.model;

import java.time.Duration;

/**
 * Query limits for delivery observability. Values come from configuration, not hard-coded SLOs.
 *
 * @param maxPageSize maximum tasks/failures page size
 * @param minP99Samples minimum samples before P99 is published
 * @param staleAfter max age of a reusable successful snapshot
 * @param clockSkew allowed query-now skew
 * @param maxTimeseriesPoints maximum timeseries buckets
 */
public record DeliveryObservabilitySettings(
        int maxPageSize,
        int minP99Samples,
        Duration staleAfter,
        Duration clockSkew,
        int maxTimeseriesPoints
) {

    public DeliveryObservabilitySettings {
        if (maxPageSize < 1) {
            throw new IllegalArgumentException("maxPageSize must be positive");
        }
        if (minP99Samples < 1) {
            throw new IllegalArgumentException("minP99Samples must be positive");
        }
        staleAfter = staleAfter == null ? Duration.ofMinutes(1) : staleAfter;
        clockSkew = clockSkew == null ? Duration.ofSeconds(5) : clockSkew;
        if (maxTimeseriesPoints < 1) {
            throw new IllegalArgumentException("maxTimeseriesPoints must be positive");
        }
    }

    /**
     * @return safe defaults used by tests and zero-config startup
     */
    public static DeliveryObservabilitySettings defaults() {
        return new DeliveryObservabilitySettings(50, 30, Duration.ofMinutes(1), Duration.ofSeconds(5), 48);
    }
}
