package com.wish.rd.engine.admin.observability;

import com.wish.rd.engine.admin.observability.model.CapacityMetric;
import com.wish.rd.engine.admin.observability.model.DataQualityStatus;
import com.wish.rd.engine.admin.observability.model.DeliveryAlertCandidate;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityOverview;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import com.wish.rd.engine.admin.observability.model.DeliverySloReport;
import com.wish.rd.engine.admin.observability.model.DeliverySloSettings;
import com.wish.rd.engine.admin.observability.model.PercentileMetric;
import com.wish.rd.engine.admin.observability.model.RatioMetric;
import com.wish.rd.engine.admin.observability.model.SchedulerLiveMetrics;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliverySloEvaluationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

    @Test
    void observationOnlyAndInsufficientBaselineNeverFireOrNotify() {
        RecordingSink sink = new RecordingSink();
        DeliverySloEvaluationService service = new DeliverySloEvaluationService(
                DeliverySloSettings.observationOnlyDefaults(), sink);
        DeliveryObservabilityOverview overview = breachedOverview(30);

        DeliverySloReport shortWindow = service.evaluate(overview, NOW, NOW.minus(Duration.ofDays(1)));
        assertFalse(shortWindow.eligible());
        assertTrue(shortWindow.observationOnly());
        assertTrue(shortWindow.firing().isEmpty());
        assertEquals(0, sink.emits.get());

        DeliverySloReport fewSamples = service.evaluate(breachedOverview(5), NOW, NOW.minus(Duration.ofDays(10)));
        assertFalse(fewSamples.eligible());
        assertTrue(fewSamples.ineligibilityReason().contains("terminal samples"));
        assertEquals(0, sink.emits.get());
    }

    @Test
    void unavailableDataDoesNotTriggerAlerts() {
        RecordingSink sink = new RecordingSink();
        DeliverySloEvaluationService service = new DeliverySloEvaluationService(enforcementSettings(), sink);
        DeliverySloReport report = service.evaluate(
                DeliveryObservabilityOverview.unavailable(
                        DeliveryObservabilityQuery.overview("", "24h", NOW),
                        NOW,
                        List.of(DataQualityStatus.failed("delivery_ledger", NOW, "down"))),
                NOW,
                NOW.minus(Duration.ofDays(10))
        );
        assertFalse(report.eligible());
        assertTrue(report.candidates().isEmpty());
        assertTrue(report.firing().isEmpty());
        assertEquals(0, sink.emits.get());
    }

    @Test
    void sustainedBreachFiresThenRecoversWithoutMutatingDelivery() {
        RecordingSink sink = new RecordingSink();
        DeliverySloEvaluationService service = new DeliverySloEvaluationService(enforcementSettings(), sink);
        Instant baseline = NOW.minus(Duration.ofDays(10));
        DeliveryObservabilityOverview breached = breachedOverview(40);

        DeliverySloReport first = service.evaluate(breached, NOW, baseline);
        assertTrue(first.eligible());
        assertTrue(first.firing().isEmpty(), "consecutive duration has not elapsed");
        assertEquals(0, sink.emits.get());

        DeliverySloReport firing = service.evaluate(breached, NOW.plus(Duration.ofMinutes(15)), baseline);
        assertFalse(firing.firing().isEmpty());
        assertTrue(firing.firing().stream().anyMatch(item -> "human_intervention_rate".equals(item.id())));
        assertTrue(firing.firing().getFirst().drillDownPath().startsWith("/admin/"));
        assertEquals(firing.firing().size(), sink.emits.get());

        DeliverySloReport recovered = service.evaluate(healthyOverview(40), NOW.plus(Duration.ofMinutes(20)), baseline);
        assertTrue(recovered.firing().isEmpty());
        assertTrue(recovered.candidates().stream().noneMatch(DeliveryAlertCandidate::firing));
    }

    @Test
    void sourceDoesNotCallSubmitRetryApproveCapacityOrLeaseMutation() throws Exception {
        Path source = Path.of("src/main/java/com/wish/rd/engine/admin/observability/DeliverySloEvaluationService.java");
        String text = Files.readString(source);
        for (String forbidden : List.of(
                "submitResult", "submit(", "retryTask", "retry(", "approve(",
                "renewLease", "setCapacity", "advanceStatus", "claimCommand", "completeCommand"
        )) {
            assertFalse(text.contains(forbidden), "SLO evaluator must not call " + forbidden);
        }
        assertTrue(text.contains("DeliveryObservabilityOverview"));
        assertTrue(text.contains("observationOnly"));
    }

    private static DeliverySloSettings enforcementSettings() {
        return new DeliverySloSettings(
                false, true, 7, 30, Duration.ofMinutes(15),
                300D, 10D, 0.10D, 0.10D, 1D, 1L, 1L
        );
    }

    private static DeliveryObservabilityOverview breachedOverview(long judged) {
        return overview(judged, 0.5D, 120D, 400D, true);
    }

    private static DeliveryObservabilityOverview healthyOverview(long judged) {
        return overview(judged, 0.0D, 1D, 1D, false);
    }

    private static DeliveryObservabilityOverview overview(
            long judged,
            double humanRate,
            double p95,
            double oldestQueue,
            boolean saturated
    ) {
        Instant generated = NOW;
        return new DeliveryObservabilityOverview(
                "",
                "24h",
                generated.minus(Duration.ofHours(24)).toEpochMilli(),
                generated.toEpochMilli(),
                generated.toEpochMilli(),
                judged,
                judged,
                0L,
                RatioMetric.of(true, judged, judged),
                RatioMetric.of(true, judged, judged),
                RatioMetric.of(true, 0, judged),
                RatioMetric.of(true, Math.round(humanRate * judged), judged),
                RatioMetric.of(true, 0, judged),
                RatioMetric.of(true, 0, 1),
                new PercentileMetric(true, false, judged, p95 / 2D, p95, p95, p95, false),
                Map.of(),
                List.of(new CapacityMetric("DOCKER", saturated ? 4 : 1, 4, saturated)),
                1L,
                oldestQueue,
                0L,
                List.of(DataQualityStatus.ok("delivery_ledger", generated)),
                Map.of(),
                new SchedulerLiveMetrics(true, false, 1L, 0L, 0L, 0L, null, null),
                List.of(),
                List.of(),
                Map.of()
        );
    }

    private static final class RecordingSink implements DeliverySloAlertSinkPort {
        private final AtomicInteger emits = new AtomicInteger();
        private final List<DeliveryAlertCandidate> seen = new ArrayList<>();

        @Override
        public void emit(DeliveryAlertCandidate candidate) {
            emits.incrementAndGet();
            seen.add(candidate);
        }
    }
}
