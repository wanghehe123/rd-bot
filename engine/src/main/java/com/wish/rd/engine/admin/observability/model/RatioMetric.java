package com.wish.rd.engine.admin.observability.model;

/**
 * Ratio that always exposes numerator, denominator and sample count.
 *
 * @param available source health
 * @param noSample true when denominator is 0
 * @param numerator count
 * @param denominator count
 * @param sampleCount usually equal to denominator
 * @param value numerator/denominator or NaN when noSample/unavailable
 */
public record RatioMetric(
        boolean available,
        boolean noSample,
        long numerator,
        long denominator,
        long sampleCount,
        double value
) {

    /**
     * @return unavailable ratio
     */
    public static RatioMetric unavailable() {
        return new RatioMetric(false, false, 0L, 0L, 0L, Double.NaN);
    }

    /**
     * @param available source health
     * @param numerator numerator
     * @param denominator denominator
     * @return ratio
     */
    public static RatioMetric of(boolean available, long numerator, long denominator) {
        if (!available) {
            return unavailable();
        }
        long safeNumerator = Math.max(0L, numerator);
        long safeDenominator = Math.max(0L, denominator);
        if (safeDenominator == 0L) {
            return new RatioMetric(true, true, safeNumerator, 0L, 0L, Double.NaN);
        }
        return new RatioMetric(
                true,
                false,
                safeNumerator,
                safeDenominator,
                safeDenominator,
                safeNumerator * 1.0D / safeDenominator
        );
    }
}
