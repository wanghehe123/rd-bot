package com.wish.rd.engine.admin.observability;

import com.wish.rd.engine.admin.observability.model.CapacityMetric;
import com.wish.rd.engine.admin.observability.model.DataQualityStatus;
import com.wish.rd.engine.admin.observability.model.DeliveryAlertCandidate;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityOverview;
import com.wish.rd.engine.admin.observability.model.DeliverySloReport;
import com.wish.rd.engine.admin.observability.model.DeliverySloSettings;
import com.wish.rd.engine.admin.observability.model.PercentileMetric;
import com.wish.rd.engine.admin.observability.model.RatioMetric;
import com.wish.rd.engine.admin.observability.model.UsageBucket;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Observation-only SLO evaluator. It reads an overview snapshot and never mutates
 * delivery, scheduling, or capacity.
 */
@Service
public final class DeliverySloEvaluationService {

    private final DeliverySloSettings settings;
    private final DeliverySloAlertSinkPort sink;
    private final Map<String, Instant> firstBreachedAt = new ConcurrentHashMap<>();

    /**
     * @param settings thresholds from configuration
     * @param sink optional notification sink; null becomes a no-op
     */
    public DeliverySloEvaluationService(DeliverySloSettings settings, DeliverySloAlertSinkPort sink) {
        this.settings = settings == null ? DeliverySloSettings.observationOnlyDefaults() : settings;
        this.sink = sink == null ? DeliverySloAlertSinkPort.noop() : sink;
    }

    /**
     * Evaluates candidates against one overview. Unavailable data never fires.
     *
     * @param overview query snapshot
     * @param now trusted clock
     * @param baselineStartedAt start of the observation baseline
     * @return report
     */
    public DeliverySloReport evaluate(
            DeliveryObservabilityOverview overview,
            Instant now,
            Instant baselineStartedAt
    ) {
        Instant clock = Objects.requireNonNull(now, "now must not be null");
        Instant baseline = baselineStartedAt == null ? clock : baselineStartedAt;
        if (overview == null || !ledgerAvailable(overview)) {
            firstBreachedAt.clear();
            long ageDays = Math.max(0L, Duration.between(baseline, clock).toDays());
            return new DeliverySloReport(
                    settings.observationOnly(),
                    settings.notificationsEnabled(),
                    false,
                    "unavailable data does not trigger alerts",
                    ageDays,
                    0L,
                    List.of(),
                    List.of()
            );
        }
        long ageDays = Math.max(0L, Duration.between(baseline, clock).toDays());
        long terminalSamples = overview.successRate().denominator();
        String ineligible = eligibilityReason(ageDays, terminalSamples);
        boolean eligible = ineligible.isBlank();
        List<DeliveryAlertCandidate> candidates = new ArrayList<>();
        addRatio(candidates, overview, "human_intervention_rate", overview.humanInterventionRate(),
                settings.humanInterventionRate(), "/admin/rd-tasks?status=FAILED_NEEDS_HUMAN");
        addRatio(candidates, overview, "unknown_failure_rate", overview.unknownFailureRate(),
                settings.unknownFailureRate(), "/admin/observability/delivery/failures");
        addPercentile(candidates, overview, "end_to_end_p95_seconds", overview.endToEndLatency(),
                settings.endToEndP95Seconds(), "/admin/observability?window=" + overview.window());
        addObserved(candidates, overview, "oldest_queue_seconds",
                overview.oldestQueueAgeSeconds(), settings.oldestQueueSeconds(),
                "/admin/observability", "oldest durable queue age");
        addSaturation(candidates, overview);
        addScheduler(candidates, overview, "lease_lost", overview.schedulerLive().leaseLost(),
                settings.leaseLost());
        addScheduler(candidates, overview, "queue_rejections", overview.schedulerLive().queueRejections(),
                settings.queueRejections());
        addCost(candidates, overview);
        addCollection(candidates, overview);

        List<DeliveryAlertCandidate> resolved = new ArrayList<>();
        List<DeliveryAlertCandidate> firing = new ArrayList<>();
        for (DeliveryAlertCandidate candidate : candidates) {
            DeliveryAlertCandidate next = applyDuration(candidate, clock, eligible);
            resolved.add(next);
            if (next.firing() && !next.suppressed()) {
                firing.add(next);
            }
        }
        if (settings.notificationsEnabled() && !settings.observationOnly() && eligible) {
            firing.forEach(sink::emit);
        }
        return new DeliverySloReport(
                settings.observationOnly(),
                settings.notificationsEnabled(),
                eligible,
                ineligible,
                ageDays,
                terminalSamples,
                resolved,
                firing
        );
    }

    private DeliveryAlertCandidate applyDuration(
            DeliveryAlertCandidate candidate,
            Instant now,
            boolean eligible
    ) {
        boolean unavailable = Double.isNaN(candidate.observedValue());
        boolean breached = !unavailable && candidate.observedValue() >= candidate.threshold();
        if (!breached) {
            firstBreachedAt.remove(candidate.id());
            return withFlags(candidate, false, true);
        }
        Instant first = firstBreachedAt.computeIfAbsent(candidate.id(), ignored -> now);
        boolean sustained = !Duration.between(first, now).minus(settings.consecutiveDuration()).isNegative();
        boolean suppressed = settings.observationOnly() || !eligible || unavailable;
        return withFlags(candidate, sustained && !suppressed, suppressed);
    }

    private String eligibilityReason(long ageDays, long terminalSamples) {
        if (ageDays < settings.minDays()) {
            return "baseline window is shorter than configured minDays";
        }
        if (terminalSamples < settings.minTerminalSamples()) {
            return "terminal samples are below the configured minimum";
        }
        return "";
    }

    private static boolean ledgerAvailable(DeliveryObservabilityOverview overview) {
        return overview.dataQuality().stream()
                .filter(item -> "delivery_ledger".equals(item.source()))
                .findFirst()
                .map(DataQualityStatus::available)
                .orElse(overview.successRate().available());
    }

    private static void addRatio(
            List<DeliveryAlertCandidate> candidates,
            DeliveryObservabilityOverview overview,
            String metric,
            RatioMetric ratio,
            double threshold,
            String drillDown
    ) {
        double observed = ratio == null || !ratio.available() || ratio.noSample() ? Double.NaN : ratio.value();
        candidates.add(candidate(overview, metric, observed, threshold, drillDown,
                metric + " observed against configured threshold"));
    }

    private static void addPercentile(
            List<DeliveryAlertCandidate> candidates,
            DeliveryObservabilityOverview overview,
            String metric,
            PercentileMetric percentile,
            double threshold,
            String drillDown
    ) {
        double observed = percentile == null || !percentile.available() || percentile.noSample()
                ? Double.NaN
                : percentile.p95Seconds();
        candidates.add(candidate(overview, metric, observed, threshold, drillDown,
                "end-to-end P95 against the configured baseline band"));
    }

    private static void addObserved(
            List<DeliveryAlertCandidate> candidates,
            DeliveryObservabilityOverview overview,
            String metric,
            double observed,
            double threshold,
            String drillDown,
            String summary
    ) {
        candidates.add(candidate(overview, metric, observed, threshold, drillDown, summary));
    }

    private static void addSaturation(
            List<DeliveryAlertCandidate> candidates,
            DeliveryObservabilityOverview overview
    ) {
        boolean saturated = overview.capacities().stream().anyMatch(CapacityMetric::saturated);
        candidates.add(candidate(overview, "resource_saturation", saturated ? 1D : 0D, 1D,
                "/admin/observability", "a scheduled resource is at capacity"));
    }

    private static void addScheduler(
            List<DeliveryAlertCandidate> candidates,
            DeliveryObservabilityOverview overview,
            String metric,
            long observed,
            long threshold
    ) {
        if (!overview.schedulerLive().available()) {
            candidates.add(candidate(overview, metric, Double.NaN, threshold,
                    "/admin/observability", metric + " unavailable because scheduler live metrics are missing"));
            return;
        }
        candidates.add(candidate(overview, metric, observed, threshold,
                "/admin/observability", metric + " process-window counter"));
    }

    private void addCost(List<DeliveryAlertCandidate> candidates, DeliveryObservabilityOverview overview) {
        double cost = overview.usageBuckets().stream()
                .filter(UsageBucket::costAvailable)
                .mapToDouble(UsageBucket::estimatedCostCny)
                .sum();
        boolean any = overview.usageBuckets().stream().anyMatch(UsageBucket::costAvailable);
        candidates.add(candidate(overview, "estimated_cost_cny", any ? cost : Double.NaN,
                settings.estimatedCostCny(), "/admin/observability", "estimated Provider cost in CNY"));
    }

    private static void addCollection(
            List<DeliveryAlertCandidate> candidates,
            DeliveryObservabilityOverview overview
    ) {
        boolean failed = overview.dataQuality().stream()
                .anyMatch(item -> !item.available() && !item.stale());
        boolean stale = overview.dataQuality().stream().anyMatch(DataQualityStatus::stale);
        candidates.add(candidate(overview, "collection_failed", failed ? 1D : 0D, 1D,
                "/admin/observability", "a delivery collector failed"));
        candidates.add(candidate(overview, "collection_stale", stale ? 1D : 0D, 1D,
                "/admin/observability", "a delivery collector snapshot is stale"));
    }

    private static DeliveryAlertCandidate withFlags(
            DeliveryAlertCandidate candidate,
            boolean firing,
            boolean suppressed
    ) {
        return new DeliveryAlertCandidate(
                candidate.id(), candidate.metric(), candidate.severity(),
                firing, suppressed, candidate.observedValue(), candidate.threshold(),
                candidate.window(), candidate.projectId(), candidate.drillDownPath(),
                candidate.summary()
        );
    }

    private static DeliveryAlertCandidate candidate(
            DeliveryObservabilityOverview overview,
            String metric,
            double observed,
            double threshold,
            String drillDown,
            String summary
    ) {
        return new DeliveryAlertCandidate(
                metric,
                metric,
                "WARNING",
                false,
                true,
                observed,
                threshold,
                overview.window(),
                overview.projectId(),
                drillDown,
                summary
        );
    }
}
