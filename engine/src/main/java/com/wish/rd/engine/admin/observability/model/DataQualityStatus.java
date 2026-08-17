package com.wish.rd.engine.admin.observability.model;

import java.time.Instant;

/**
 * Freshness of one observability contributor.
 *
 * @param source collector name
 * @param available last attempt succeeded
 * @param generatedAtEpochMillis this snapshot time
 * @param lastSuccessEpochMillis last successful snapshot; 0 if never
 * @param stale true when reused past the freshness budget
 * @param warning operator-facing warning, never a raw exception
 */
public record DataQualityStatus(
        String source,
        boolean available,
        long generatedAtEpochMillis,
        long lastSuccessEpochMillis,
        boolean stale,
        String warning
) {

    public DataQualityStatus {
        source = source == null ? "unknown" : source.strip();
        warning = warning == null ? "" : warning;
    }

    /**
     * @param source collector
     * @param generatedAt now
     * @param warning warning
     * @return failed collection with no reusable snapshot
     */
    public static DataQualityStatus failed(String source, Instant generatedAt, String warning) {
        long generated = generatedAt == null ? 0L : generatedAt.toEpochMilli();
        return new DataQualityStatus(source, false, generated, 0L, false, warning);
    }

    /**
     * @param source collector
     * @param generatedAt snapshot time
     * @return healthy collection
     */
    public static DataQualityStatus ok(String source, Instant generatedAt) {
        long generated = generatedAt == null ? 0L : generatedAt.toEpochMilli();
        return new DataQualityStatus(source, true, generated, generated, false, "");
    }
}
