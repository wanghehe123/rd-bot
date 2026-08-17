package com.wish.rd.bootstrap.observability;

import com.wish.rd.engine.admin.observability.model.DeliveryObservabilitySettings;
import com.wish.rd.engine.admin.observability.model.DeliverySloSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bound query limits for delivery observability. Environment variables supply the values;
 * these defaults keep zero-config startup safe.
 */
@ConfigurationProperties(prefix = "rd.observability.delivery")
public class DeliveryObservabilityProperties {

    private String defaultWindow = "24h";
    private int maxPageSize = 50;
    private int minP99Samples = 30;
    private Duration snapshotStaleAfter = Duration.ofMinutes(1);
    private Duration clockSkew = Duration.ofSeconds(5);
    private int maxTimeseriesPoints = 48;
    private Duration queryTimeout = Duration.ofSeconds(2);
    private Slo slo = new Slo();

    public String getDefaultWindow() {
        return defaultWindow;
    }

    public void setDefaultWindow(String defaultWindow) {
        this.defaultWindow = defaultWindow == null || defaultWindow.isBlank() ? "24h" : defaultWindow.strip();
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = Math.max(1, maxPageSize);
    }

    public int getMinP99Samples() {
        return minP99Samples;
    }

    public void setMinP99Samples(int minP99Samples) {
        this.minP99Samples = Math.max(1, minP99Samples);
    }

    public Duration getSnapshotStaleAfter() {
        return snapshotStaleAfter;
    }

    public void setSnapshotStaleAfter(Duration snapshotStaleAfter) {
        this.snapshotStaleAfter = snapshotStaleAfter == null ? Duration.ofMinutes(1) : snapshotStaleAfter;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew == null ? Duration.ofSeconds(5) : clockSkew;
    }

    public int getMaxTimeseriesPoints() {
        return maxTimeseriesPoints;
    }

    public void setMaxTimeseriesPoints(int maxTimeseriesPoints) {
        this.maxTimeseriesPoints = Math.max(1, maxTimeseriesPoints);
    }

    public Duration getQueryTimeout() {
        return queryTimeout;
    }

    public void setQueryTimeout(Duration queryTimeout) {
        this.queryTimeout = queryTimeout == null ? Duration.ofSeconds(2) : queryTimeout;
    }

    /**
     * @return engine settings
     */
    public DeliveryObservabilitySettings toSettings() {
        return new DeliveryObservabilitySettings(
                maxPageSize, minP99Samples, snapshotStaleAfter, clockSkew, maxTimeseriesPoints);
    }

    public Slo getSlo() {
        return slo;
    }

    public void setSlo(Slo slo) {
        this.slo = slo == null ? new Slo() : slo;
    }

    /**
     * @return observation-only SLO settings from configuration
     */
    public DeliverySloSettings toSloSettings() {
        return slo.toSettings();
    }

    /**
     * Nested SLO thresholds. Defaults stay observation-only with notifications off.
     */
    public static class Slo {
        private boolean observationOnly = true;
        private boolean notificationsEnabled = false;
        private int minDays = 7;
        private int minTerminalSamples = 30;
        private Duration consecutiveDuration = Duration.ofMinutes(15);
        private double oldestQueueSeconds = 300D;
        private double endToEndP95Seconds = 1_800D;
        private double humanInterventionRate = 0.20D;
        private double unknownFailureRate = 0.30D;
        private double estimatedCostCny = 50D;
        private long leaseLost = 1L;
        private long queueRejections = 1L;

        public boolean isObservationOnly() {
            return observationOnly;
        }

        public void setObservationOnly(boolean observationOnly) {
            this.observationOnly = observationOnly;
        }

        public boolean isNotificationsEnabled() {
            return notificationsEnabled;
        }

        public void setNotificationsEnabled(boolean notificationsEnabled) {
            this.notificationsEnabled = notificationsEnabled;
        }

        public int getMinDays() {
            return minDays;
        }

        public void setMinDays(int minDays) {
            this.minDays = Math.max(1, minDays);
        }

        public int getMinTerminalSamples() {
            return minTerminalSamples;
        }

        public void setMinTerminalSamples(int minTerminalSamples) {
            this.minTerminalSamples = Math.max(1, minTerminalSamples);
        }

        public Duration getConsecutiveDuration() {
            return consecutiveDuration;
        }

        public void setConsecutiveDuration(Duration consecutiveDuration) {
            this.consecutiveDuration = consecutiveDuration == null ? Duration.ofMinutes(15) : consecutiveDuration;
        }

        public double getOldestQueueSeconds() {
            return oldestQueueSeconds;
        }

        public void setOldestQueueSeconds(double oldestQueueSeconds) {
            this.oldestQueueSeconds = oldestQueueSeconds;
        }

        public double getEndToEndP95Seconds() {
            return endToEndP95Seconds;
        }

        public void setEndToEndP95Seconds(double endToEndP95Seconds) {
            this.endToEndP95Seconds = endToEndP95Seconds;
        }

        public double getHumanInterventionRate() {
            return humanInterventionRate;
        }

        public void setHumanInterventionRate(double humanInterventionRate) {
            this.humanInterventionRate = humanInterventionRate;
        }

        public double getUnknownFailureRate() {
            return unknownFailureRate;
        }

        public void setUnknownFailureRate(double unknownFailureRate) {
            this.unknownFailureRate = unknownFailureRate;
        }

        public double getEstimatedCostCny() {
            return estimatedCostCny;
        }

        public void setEstimatedCostCny(double estimatedCostCny) {
            this.estimatedCostCny = estimatedCostCny;
        }

        public long getLeaseLost() {
            return leaseLost;
        }

        public void setLeaseLost(long leaseLost) {
            this.leaseLost = leaseLost;
        }

        public long getQueueRejections() {
            return queueRejections;
        }

        public void setQueueRejections(long queueRejections) {
            this.queueRejections = queueRejections;
        }

        private DeliverySloSettings toSettings() {
            return new DeliverySloSettings(
                    observationOnly,
                    notificationsEnabled,
                    minDays,
                    minTerminalSamples,
                    consecutiveDuration,
                    oldestQueueSeconds,
                    endToEndP95Seconds,
                    humanInterventionRate,
                    unknownFailureRate,
                    estimatedCostCny,
                    leaseLost,
                    queueRejections
            );
        }
    }
}
