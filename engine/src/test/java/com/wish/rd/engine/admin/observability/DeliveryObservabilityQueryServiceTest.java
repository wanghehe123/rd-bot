package com.wish.rd.engine.admin.observability;

import com.wish.rd.engine.admin.observability.model.DataQualityStatus;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StageObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StatusEventObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.TaskObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityOverview;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilitySettings;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityWindow;
import com.wish.rd.engine.scheduling.RequirementDeliveryMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryObservabilityQueryServiceTest {

    private static final Instant NOW = DeliveryObservabilityFixtures.WINDOW_END;

    @Test
    void blankProjectIdMeansAllProjectsAndAllSentinelIsRejected() {
        DeliveryObservabilityQuery all = DeliveryObservabilityQuery.overview("", "24h", NOW);
        assertTrue(all.allProjects());
        assertEquals(DeliveryObservabilityWindow.ONE_DAY, all.window());
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryObservabilityQuery.overview("all", "24h", NOW));
    }

    @Test
    void rejectsUnknownWindowFutureTimeAndUnboundedPage() {
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryObservabilityQuery.overview("project-a", "2h", NOW));
        DeliveryObservabilityQuery future = DeliveryObservabilityQuery.overview(
                "project-a", "24h", NOW.plus(Duration.ofHours(2)));
        DeliveryObservabilityQueryService service = service(catalogPort());
        assertThrows(IllegalArgumentException.class, () -> service.overview(future, NOW));
        DeliveryObservabilityQuery hugePage = new DeliveryObservabilityQuery(
                "project-a", DeliveryObservabilityWindow.ONE_DAY, NOW, "", "", "", "", 1, 500);
        assertThrows(IllegalArgumentException.class, () -> service.overview(hugePage, NOW));
    }

    @Test
    void successRateUsesTerminalDenominatorAndExcludesRunning() {
        DeliveryObservabilityOverview overview = service(catalogPort())
                .overview(DeliveryObservabilityQuery.overview("", "24h", NOW), NOW);

        assertEquals(6L, overview.acceptedCount());
        assertEquals(5L, overview.terminalCount());
        assertEquals(1L, overview.runningCount());
        assertEquals(4L, overview.successRate().numerator());
        assertEquals(5L, overview.successRate().denominator());
        assertEquals(0.8D, overview.successRate().value(), 0.000_001D);
        assertFalse(overview.successRate().noSample());
        assertEquals(5L, overview.endToEndLatency().sampleCount());
        assertEquals(2L, overview.qaPassRate().numerator());
        assertEquals(3L, overview.qaPassRate().denominator());
        assertEquals(2L, overview.prCreationRate().numerator());
        assertEquals(5L, overview.prCreationRate().denominator());
        assertEquals(1L, overview.humanInterventionRate().numerator());
        assertEquals(5L, overview.humanInterventionRate().denominator());
        assertEquals(1L, overview.retryRate().numerator());
        assertEquals(9L, overview.retryRate().denominator());
        assertEquals(1L, overview.unknownFailureRate().numerator());
        assertEquals(1L, overview.unknownFailureRate().denominator());
        assertTrue(overview.durationHistograms().get("end_to_end").count() >= 1L);
        assertFalse(overview.usageBuckets().isEmpty());
    }

    @Test
    void emptyWindowIsNoSampleNotHealthyZeroLatency() {
        DeliveryObservabilityOverview overview = service(emptyPort())
                .overview(DeliveryObservabilityQuery.overview("", "24h", NOW), NOW);

        assertTrue(overview.successRate().noSample());
        assertTrue(Double.isNaN(overview.successRate().value()));
        assertTrue(overview.endToEndLatency().noSample());
        assertEquals(0L, overview.endToEndLatency().sampleCount());
        assertTrue(Double.isNaN(overview.endToEndLatency().p50Seconds()));
        assertTrue(overview.dataQuality().stream().anyMatch(DataQualityStatus::available));
    }

    @Test
    void ledgerFailureWithoutCacheIsUnavailableNotZero() {
        DeliveryObservabilityOverview overview = service(failedPort())
                .overview(DeliveryObservabilityQuery.overview("project-a", "24h", NOW), NOW);

        assertFalse(overview.successRate().available());
        assertTrue(Double.isNaN(overview.successRate().value()));
        assertTrue(Double.isNaN(overview.endToEndLatency().p50Seconds()));
        assertTrue(overview.dataQuality().stream().anyMatch(item ->
                "delivery_ledger".equals(item.source()) && !item.available() && !item.stale()));
    }

    @Test
    void ledgerFailureReusesFreshSnapshotAndMarksItStale() {
        FakePort port = catalogPort();
        DeliveryObservabilityQueryService service = service(port);
        DeliveryObservabilityQuery query = DeliveryObservabilityQuery.overview("", "24h", NOW);
        DeliveryObservabilityOverview first = service.overview(query, NOW);
        assertEquals(5L, first.terminalCount());

        port.failNext();
        DeliveryObservabilityOverview stale = service.overview(query, NOW.plusSeconds(30));
        assertEquals(5L, stale.terminalCount());
        assertEquals(0.8D, stale.successRate().value(), 0.000_001D);
        assertTrue(stale.dataQuality().stream().anyMatch(item ->
                "delivery_ledger".equals(item.source()) && item.stale() && !item.available()));

        port.failNext();
        DeliveryObservabilityOverview expired = service.overview(query, NOW.plus(Duration.ofMinutes(2)));
        assertFalse(expired.successRate().available());
        assertTrue(Double.isNaN(expired.successRate().value()));
    }

    @Test
    void contextPhaseUsesAdjacentEnteredAtInsteadOfZeroDurationMs() {
        DeliveryObservabilityOverview overview = service(catalogPort())
                .overview(DeliveryObservabilityQuery.overview("project-a", "24h", NOW), NOW);
        assertTrue(DeliveryObservabilityFixtures.successTask().statusEvents().stream()
                .allMatch(event -> event.durationMs() == 0L));
        assertTrue(overview.phaseLatency().get("context").sampleCount() >= 1L);
        assertEquals(180.0D, overview.phaseLatency().get("context").p50Seconds(), 0.001D);
    }

    @Test
    void timeseriesIsBoundedAndFailuresTasksStayPaginated() {
        DeliveryObservabilityQueryService queryService = service(catalogPort());
        DeliveryObservabilityQuery query = DeliveryObservabilityQuery.overview("", "24h", NOW);

        var series = queryService.timeseries(query, NOW);
        assertTrue(series.points().size() <= 48);
        assertTrue(series.points().stream().mapToLong(point -> point.accepted()).sum() >= 1L);

        var failures = queryService.failures(query, NOW);
        assertTrue(failures.items().stream().anyMatch(row -> "UNKNOWN".equals(row.category())));
        assertTrue(failures.items().stream().noneMatch(row -> row.category().contains("secret")));

        var tasks = queryService.tasks(query, NOW);
        assertTrue(tasks.total() >= 1L);
        assertEquals("/admin/traces/1001", tasks.items().stream()
                .filter(row -> "1001".equals(row.taskId()))
                .findFirst()
                .orElseThrow()
                .tracePath());
        assertTrue(tasks.items().stream().noneMatch(row -> row.title().contains("PROMPT")));
    }

    @Test
    void committedWithPrAndNoCompletedEventIsInProgressNotSuccess() {
        DeliveryObservabilityOverview overview = overviewOf(
                DeliveryObservabilityFixtures.committedNoCompleteTask());

        assertEquals(0L, overview.successRate().numerator());
        assertEquals(0L, overview.terminalCount());
        assertEquals(1L, overview.runningCount());
        assertTrue(overview.successRate().noSample());
        assertEquals(0L, overview.prCreationRate().numerator());
    }

    @Test
    void mergedWithCompletedStatusEventIsSuccess() {
        DeliveryObservabilityOverview overview = overviewOf(
                DeliveryObservabilityFixtures.mergedWithCompleteTask());

        assertEquals(1L, overview.successRate().numerator());
        assertEquals(1L, overview.successRate().denominator());
        assertEquals(1L, overview.terminalCount());
        assertEquals(0L, overview.runningCount());
        assertEquals(1.0D, overview.successRate().value(), 0.000_001D);
    }

    @Test
    void mergedWithOnlyCommittedHistoryIsPublicationTerminalNotSuccessOrFailure() {
        DeliveryObservabilityOverview overview = overviewOf(
                DeliveryObservabilityFixtures.mergedCommittedOnlyTask());

        assertEquals(1L, overview.terminalCount());
        assertEquals(0L, overview.runningCount());
        assertEquals(0L, overview.successRate().numerator());
        assertEquals(0L, overview.successRate().denominator());
        assertTrue(overview.successRate().noSample());
        assertEquals(0L, overview.unknownFailureRate().numerator());
        assertEquals(0L, overview.unknownFailureRate().denominator());
        assertTrue(overview.unknownFailureRate().noSample());
        assertTrue(overview.failureCategories().isEmpty());
    }

    @Test
    void waitingUserInputWithTerminalAtIsRunningNotSuccessFailureOrTerminal() {
        DeliveryObservabilityOverview overview = overviewOf(
                DeliveryObservabilityFixtures.waitingUserInputTask());

        assertEquals(0L, overview.successRate().numerator());
        assertEquals(0L, overview.terminalCount());
        assertEquals(1L, overview.runningCount());
        assertTrue(overview.successRate().noSample());
        assertTrue(overview.unknownFailureRate().noSample());
        assertTrue(overview.failureCategories().isEmpty());
    }

    @Test
    void waitingApprovalWithTerminalAtIsRunning() {
        DeliveryObservabilityOverview overview = overviewOf(
                DeliveryObservabilityFixtures.waitingApprovalTask());

        assertEquals(0L, overview.terminalCount());
        assertEquals(1L, overview.runningCount());
        assertEquals(0L, overview.successRate().numerator());
        assertTrue(overview.successRate().noSample());
    }

    @Test
    void inMemoryOverviewP95StaysUnderProvisionalBudget() {
        DeliveryObservabilityQueryService queryService = service(catalogPort());
        DeliveryObservabilityQuery query = DeliveryObservabilityQuery.overview("", "24h", NOW);
        java.util.List<Long> samples = new java.util.ArrayList<>();
        for (int index = 0; index < 40; index++) {
            long started = System.nanoTime();
            queryService.overview(query, NOW);
            queryService.timeseries(query, NOW);
            queryService.failures(query, NOW);
            samples.add(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
        java.util.Collections.sort(samples);
        long p95 = samples.get((int) Math.ceil(0.95D * samples.size()) - 1);
        assertTrue(p95 < 500L, "in-memory fixture p95 was " + p95 + " ms");
    }

    private static DeliveryObservabilityOverview overviewOf(
            DeliveryObservabilityFixtures.TaskFixture... fixtures
    ) {
        DeliveryObservabilityFixtures.Catalog catalog = new DeliveryObservabilityFixtures.Catalog(
                List.of(fixtures), List.of());
        return service(new FakePort(toLedger(catalog, NOW), false))
                .overview(DeliveryObservabilityQuery.overview("", "24h", NOW), NOW);
    }

    private static DeliveryObservabilityQueryService service(DeliveryObservabilitySnapshotPort port) {
        return new DeliveryObservabilityQueryService(port, DeliveryObservabilitySettings.defaults());
    }

    private static FakePort catalogPort() {
        return new FakePort(toLedger(DeliveryObservabilityFixtures.catalog(), NOW), false);
    }

    private static FakePort emptyPort() {
        return new FakePort(toLedger(DeliveryObservabilityFixtures.emptyWindow(), NOW), false);
    }

    private static FakePort failedPort() {
        return new FakePort(DeliveryLedgerSnapshot.failed(NOW, "delivery ledger unavailable"), false);
    }

    private static DeliveryLedgerSnapshot toLedger(
            DeliveryObservabilityFixtures.Catalog catalog,
            Instant generatedAt
    ) {
        List<TaskObservation> tasks = catalog.tasks().stream()
                .map(DeliveryObservabilityQueryServiceTest::toTask)
                .toList();
        return new DeliveryLedgerSnapshot(true, generatedAt, "", tasks, List.of());
    }

    private static TaskObservation toTask(DeliveryObservabilityFixtures.TaskFixture fixture) {
        List<StatusEventObservation> events = fixture.statusEvents().stream()
                .map(event -> new StatusEventObservation(event.status(), event.enteredAt(), event.durationMs()))
                .toList();
        List<StageObservation> stages = fixture.stageRuns().stream()
                .map(stage -> new StageObservation(
                        stage.stageRunId(), stage.role(), stage.status(), stage.attemptNo(),
                        stage.startedAt(), stage.finishedAt(),
                        stage.providerAttempts().isEmpty() ? "" : stage.providerAttempts().getFirst().errorCategory(),
                        stage.providerAttempts().stream()
                                .map(attempt -> new DeliveryLedgerSnapshot.AttemptUsage(
                                        attempt.attemptId(),
                                        attempt.runtime(),
                                        attempt.provider(),
                                        attempt.inputTokens(),
                                        attempt.outputTokens(),
                                        attempt.cacheTokens(),
                                        parseCost(attempt.estimatedCostCny()),
                                        attempt.usageAvailable(),
                                        -1L,
                                        -1L
                                ))
                                .toList()))
                .toList();
        return new TaskObservation(
                fixture.taskId(), fixture.projectId(), fixture.title(), fixture.status(),
                fixture.acceptedAt(), fixture.terminalAt(), fixture.pullRequestUrl(),
                fixture.errorCategory(), events, stages
        );
    }

    private static double parseCost(String estimatedCostCny) {
        if (estimatedCostCny == null || estimatedCostCny.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(estimatedCostCny.strip());
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static final class FakePort implements DeliveryObservabilitySnapshotPort {
        private final DeliveryLedgerSnapshot snapshot;
        private boolean failNext;

        private FakePort(DeliveryLedgerSnapshot snapshot, boolean unused) {
            this.snapshot = snapshot;
        }

        private void failNext() {
            failNext = true;
        }

        @Override
        public DeliveryLedgerSnapshot loadLedger(DeliveryObservabilityQuery query) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("delivery ledger unavailable");
            }
            return snapshot;
        }

        @Override
        public RequirementDeliveryMetrics.Snapshot loadScheduler() {
            return null;
        }
    }
}
