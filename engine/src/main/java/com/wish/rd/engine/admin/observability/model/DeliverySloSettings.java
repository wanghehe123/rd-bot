package com.wish.rd.engine.admin.observability.model;

import java.time.Duration;

/**
 * Observation-only SLO gates. Threshold numbers come from configuration, never from
 * a hard-coded local sample.
 *
 * @param observationOnly true when candidates are recorded but never enforced
 * @param notificationsEnabled true only after an explicit approval to send alerts
 * @param minDays minimum baseline age before a window is SLO-eligible
 * @param minTerminalSamples minimum judged terminal tasks
 * @param consecutiveDuration how long a breach must persist before firing
 * @param oldestQueueSeconds queue-age candidate threshold
 * @param endToEndP95Seconds e2e P95 candidate threshold
 * @param humanInterventionRate human-rate candidate threshold
 * @param unknownFailureRate unknown-failure candidate threshold
 * @param estimatedCostCny estimated-cost candidate threshold
 * @param leaseLost lease-loss candidate threshold
 * @param queueRejections thread-pool rejection candidate threshold
 */
public record DeliverySloSettings(
        boolean observationOnly,
        boolean notificationsEnabled,
        int minDays,
        int minTerminalSamples,
        Duration consecutiveDuration,
        double oldestQueueSeconds,
        double endToEndP95Seconds,
        double humanInterventionRate,
        double unknownFailureRate,
        double estimatedCostCny,
        long leaseLost,
        long queueRejections
) {

    public DeliverySloSettings {
        consecutiveDuration = consecutiveDuration == null ? Duration.ofMinutes(15) : consecutiveDuration;
        minDays = Math.max(1, minDays);
        minTerminalSamples = Math.max(1, minTerminalSamples);
        oldestQueueSeconds = Math.max(0D, oldestQueueSeconds);
        endToEndP95Seconds = Math.max(0D, endToEndP95Seconds);
        humanInterventionRate = clampRate(humanInterventionRate);
        unknownFailureRate = clampRate(unknownFailureRate);
        estimatedCostCny = Math.max(0D, estimatedCostCny);
        leaseLost = Math.max(0L, leaseLost);
        queueRejections = Math.max(0L, queueRejections);
    }

    /**
     * Safe observation-only defaults. These are configuration placeholders, not a published SLO.
     *
     * @return defaults
     */
    public static DeliverySloSettings observationOnlyDefaults() {
        return new DeliverySloSettings(
                true,
                false,
                7,
                30,
                Duration.ofMinutes(15),
                300D,
                1_800D,
                0.20D,
                0.30D,
                50D,
                1L,
                1L
        );
    }

    private static double clampRate(double value) {
        if (Double.isNaN(value) || value < 0D) {
            return 0D;
        }
        return Math.min(1D, value);
    }
}
