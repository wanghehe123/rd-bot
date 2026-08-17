package com.wish.rd.engine.admin.observability.model;

import java.util.List;

/**
 * Windowed throughput timeseries with a bounded number of buckets.
 *
 * @param projectId blank when all projects
 * @param window canonical window token
 * @param windowStartEpochMillis window start
 * @param windowEndEpochMillis window end
 * @param generatedAtEpochMillis response time
 * @param bucketSeconds bucket width
 * @param points chronological points, never more than the configured max
 * @param dataQuality contributor freshness
 */
public record DeliveryObservabilityTimeseries(
        String projectId,
        String window,
        long windowStartEpochMillis,
        long windowEndEpochMillis,
        long generatedAtEpochMillis,
        long bucketSeconds,
        List<TimeseriesPoint> points,
        List<DataQualityStatus> dataQuality
) {

    public DeliveryObservabilityTimeseries {
        projectId = projectId == null ? "" : projectId;
        window = window == null ? DeliveryObservabilityWindow.ONE_DAY.token() : window;
        points = points == null ? List.of() : List.copyOf(points);
        dataQuality = dataQuality == null ? List.of() : List.copyOf(dataQuality);
        bucketSeconds = Math.max(1L, bucketSeconds);
    }

    /**
     * One timeseries bucket.
     *
     * @param startEpochMillis bucket start
     * @param accepted accepted in bucket
     * @param success terminal success in bucket
     * @param failure terminal failure in bucket
     */
    public record TimeseriesPoint(long startEpochMillis, long accepted, long success, long failure) {
        public TimeseriesPoint {
            accepted = Math.max(0L, accepted);
            success = Math.max(0L, success);
            failure = Math.max(0L, failure);
        }
    }
}
