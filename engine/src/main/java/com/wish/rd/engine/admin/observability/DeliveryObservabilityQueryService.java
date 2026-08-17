package com.wish.rd.engine.admin.observability;

import com.wish.rd.engine.admin.observability.model.CapacityMetric;
import com.wish.rd.engine.admin.observability.model.DataQualityStatus;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.CommandObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StageObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.TaskObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryFailurePage;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityOverview;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilitySettings;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityTimeseries;
import com.wish.rd.engine.admin.observability.model.DeliveryTaskPage;
import com.wish.rd.engine.admin.observability.model.DurationHistogram;
import com.wish.rd.engine.admin.observability.model.PercentileMetric;
import com.wish.rd.engine.admin.observability.model.RatioMetric;
import com.wish.rd.engine.admin.observability.model.RoleStatusCount;
import com.wish.rd.engine.admin.observability.model.SchedulerLiveMetrics;
import com.wish.rd.engine.admin.observability.model.UsageBucket;
import com.wish.rd.engine.scheduling.RequirementDeliveryMetrics;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Delivery observability query boundary. Denominators, freshness and P99 gates live here.
 */
@Service
public final class DeliveryObservabilityQueryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryObservabilityQueryService.class);

    private static final Set<String> SUCCESS = Set.of("COMPLETED", "COMMITTED", "MERGED");
    private static final Set<String> FAILURE = Set.of(
            "REJECTED", "FAILED_RETRYABLE", "FAILED_NEEDS_HUMAN", "DEAD_LETTERED");
    private static final Set<String> TERMINAL = Set.of(
            "COMPLETED", "COMMITTED", "MERGED", "REJECTED", "FAILED_RETRYABLE",
            "FAILED_NEEDS_HUMAN", "DEAD_LETTERED", "CANCELLED");
    private static final List<String> PHASES = List.of(
            DeliveryPhaseDurationCalculator.PHASE_MATERIAL,
            DeliveryPhaseDurationCalculator.PHASE_CONTEXT,
            DeliveryPhaseDurationCalculator.PHASE_PLAN,
            DeliveryPhaseDurationCalculator.PHASE_POLICY,
            DeliveryPhaseDurationCalculator.PHASE_EXECUTION,
            DeliveryPhaseDurationCalculator.PHASE_VALIDATION,
            DeliveryPhaseDurationCalculator.PHASE_PUBLICATION,
            DeliveryPhaseDurationCalculator.PHASE_REPORTING
    );

    private final DeliveryObservabilitySnapshotPort snapshotPort;
    private final DeliveryObservabilitySettings settings;
    private final DeliveryPhaseDurationCalculator phaseCalculator;
    private final AtomicReference<CachedOverview> lastSuccess = new AtomicReference<>();

    /**
     * Creates the Spring-managed query service.
     *
     * @param snapshotPort ledger/scheduler port
     * @param settings query limits; null uses defaults
     */
    public DeliveryObservabilityQueryService(
            DeliveryObservabilitySnapshotPort snapshotPort,
            DeliveryObservabilitySettings settings
    ) {
        this.snapshotPort = Objects.requireNonNull(snapshotPort, "snapshotPort must not be null");
        this.settings = settings == null ? DeliveryObservabilitySettings.defaults() : settings;
        this.phaseCalculator = new DeliveryPhaseDurationCalculator();
    }

    /**
     * Builds an overview for the normalized query.
     *
     * @param query query
     * @param clockNow trusted clock
     * @return overview
     */
    public DeliveryObservabilityOverview overview(DeliveryObservabilityQuery query, Instant clockNow) {
        DeliveryObservabilityQuery safe = Objects.requireNonNull(query, "query must not be null");
        Instant now = Objects.requireNonNull(clockNow, "clockNow must not be null");
        safe.rejectFuture(now, settings.clockSkew());
        safe.rejectUnboundedPage(settings.maxPageSize());
        try {
            DeliveryLedgerSnapshot ledger = snapshotPort.loadLedger(safe);
            if (ledger == null || !ledger.available()) {
                return reuseOrUnavailable(safe, now, ledger == null ? "delivery ledger unavailable" : ledger.warning());
            }
            DeliveryObservabilityOverview overview = build(safe, ledger, snapshotPort.loadScheduler(), now);
            lastSuccess.set(new CachedOverview(overview, now));
            return overview;
        } catch (RuntimeException exception) {
            log.warn("delivery observability overview failed after ledger load", exception);
            return reuseOrUnavailable(safe, now, "delivery ledger query failed");
        }
    }

    /**
     * Builds a bounded throughput timeseries for the same query window.
     *
     * @param query query
     * @param clockNow trusted clock
     * @return timeseries
     */
    public DeliveryObservabilityTimeseries timeseries(DeliveryObservabilityQuery query, Instant clockNow) {
        Prepared prepared = prepare(query, clockNow);
        if (prepared.ledger() == null || !prepared.ledger().available()) {
            return emptyTimeseries(prepared.query(), prepared.now(), prepared.warning());
        }
        Instant start = prepared.query().windowStart();
        Instant end = prepared.query().now();
        long windowMillis = Math.max(1L, Duration.between(start, end).toMillis());
        int buckets = Math.max(1, Math.min(settings.maxTimeseriesPoints(), 48));
        long bucketMillis = Math.max(1L, windowMillis / buckets);
        long[] accepted = new long[buckets];
        long[] success = new long[buckets];
        long[] failure = new long[buckets];
        for (TaskObservation task : scoped(prepared.query(), prepared.ledger())) {
            if (inWindow(task.acceptedAt(), start, end)) {
                accepted[bucketIndex(task.acceptedAt(), start, bucketMillis, buckets)]++;
            }
            if (task.terminal() && TERMINAL.contains(status(task)) && inWindow(task.terminalAt(), start, end)) {
                int index = bucketIndex(task.terminalAt(), start, bucketMillis, buckets);
                if (SUCCESS.contains(status(task))) {
                    success[index]++;
                } else if (FAILURE.contains(status(task))) {
                    failure[index]++;
                }
            }
        }
        List<DeliveryObservabilityTimeseries.TimeseriesPoint> points = new ArrayList<>();
        for (int index = 0; index < buckets; index++) {
            points.add(new DeliveryObservabilityTimeseries.TimeseriesPoint(
                    start.toEpochMilli() + index * bucketMillis,
                    accepted[index], success[index], failure[index]));
        }
        return new DeliveryObservabilityTimeseries(
                prepared.query().projectId(),
                prepared.query().window().token(),
                start.toEpochMilli(),
                end.toEpochMilli(),
                prepared.now().toEpochMilli(),
                bucketMillis / 1000L,
                points,
                List.of(DataQualityStatus.ok("delivery_ledger", prepared.ledger().generatedAt()))
        );
    }

    /**
     * Paginates folded failure categories.
     *
     * @param query query
     * @param clockNow trusted clock
     * @return failure page
     */
    public DeliveryFailurePage failures(DeliveryObservabilityQuery query, Instant clockNow) {
        Prepared prepared = prepare(query, clockNow);
        if (prepared.ledger() == null || !prepared.ledger().available()) {
            return new DeliveryFailurePage(
                    prepared.query().projectId(), prepared.query().window().token(),
                    prepared.now().toEpochMilli(), prepared.query().page(), prepared.query().pageSize(),
                    0L, List.of(),
                    List.of(DataQualityStatus.failed("delivery_ledger", prepared.now(), prepared.warning())));
        }
        Instant start = prepared.query().windowStart();
        Instant end = prepared.query().now();
        List<TaskObservation> failed = scoped(prepared.query(), prepared.ledger()).stream()
                .filter(task -> task.terminal() && FAILURE.contains(status(task)) && inWindow(task.terminalAt(), start, end))
                .filter(task -> prepared.query().failureCategory().isBlank()
                        || foldFailure(task.errorCategory()).equals(prepared.query().failureCategory()))
                .toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TaskObservation task : failed) {
            counts.merge(foldFailure(task.errorCategory()), 1L, Long::sum);
        }
        List<Map.Entry<String, Long>> ranked = counts.entrySet().stream()
                .sorted((left, right) -> {
                    int byCount = Long.compare(right.getValue(), left.getValue());
                    return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
                })
                .toList();
        int from = Math.max(0, (prepared.query().page() - 1) * prepared.query().pageSize());
        List<DeliveryFailurePage.FailureRow> items = ranked.stream()
                .skip(from)
                .limit(prepared.query().pageSize())
                .map(entry -> new DeliveryFailurePage.FailureRow(
                        entry.getKey(),
                        entry.getValue(),
                        failed.isEmpty() ? Double.NaN : entry.getValue() * 1.0D / failed.size()))
                .toList();
        return new DeliveryFailurePage(
                prepared.query().projectId(), prepared.query().window().token(),
                prepared.now().toEpochMilli(), prepared.query().page(), prepared.query().pageSize(),
                ranked.size(), items,
                List.of(DataQualityStatus.ok("delivery_ledger", prepared.ledger().generatedAt()))
        );
    }

    /**
     * Paginates task summaries for drill-down.
     *
     * @param query query
     * @param clockNow trusted clock
     * @return task page
     */
    public DeliveryTaskPage tasks(DeliveryObservabilityQuery query, Instant clockNow) {
        Prepared prepared = prepare(query, clockNow);
        if (prepared.ledger() == null || !prepared.ledger().available()) {
            return new DeliveryTaskPage(
                    prepared.query().projectId(), prepared.query().window().token(),
                    prepared.now().toEpochMilli(), prepared.query().page(), prepared.query().pageSize(),
                    0L, List.of(),
                    List.of(DataQualityStatus.failed("delivery_ledger", prepared.now(), prepared.warning())));
        }
        Instant start = prepared.query().windowStart();
        Instant end = prepared.query().now();
        List<TaskObservation> matches = scoped(prepared.query(), prepared.ledger()).stream()
                .filter(task -> (task.terminal() && inWindow(task.terminalAt(), start, end))
                        || (!task.terminal() && task.acceptedAt() != null && task.acceptedAt().isBefore(end)))
                .filter(task -> matchesFilters(prepared.query(), task))
                .sorted((left, right) -> {
                    Instant leftTime = left.terminalAt() == null ? left.acceptedAt() : left.terminalAt();
                    Instant rightTime = right.terminalAt() == null ? right.acceptedAt() : right.terminalAt();
                    int byTime = Comparator.nullsLast(Comparator.<Instant>naturalOrder().reversed())
                            .compare(leftTime, rightTime);
                    return byTime != 0 ? byTime : safe(left.taskId()).compareTo(safe(right.taskId()));
                })
                .toList();
        int from = Math.max(0, (prepared.query().page() - 1) * prepared.query().pageSize());
        List<DeliveryTaskPage.DeliveryTaskRow> items = matches.stream()
                .skip(from)
                .limit(prepared.query().pageSize())
                .map(DeliveryObservabilityQueryService::toRow)
                .toList();
        return new DeliveryTaskPage(
                prepared.query().projectId(), prepared.query().window().token(),
                prepared.now().toEpochMilli(), prepared.query().page(), prepared.query().pageSize(),
                matches.size(), items,
                List.of(DataQualityStatus.ok("delivery_ledger", prepared.ledger().generatedAt()))
        );
    }

    private Prepared prepare(DeliveryObservabilityQuery query, Instant clockNow) {
        DeliveryObservabilityQuery safe = Objects.requireNonNull(query, "query must not be null");
        Instant now = Objects.requireNonNull(clockNow, "clockNow must not be null");
        safe.rejectFuture(now, settings.clockSkew());
        safe.rejectUnboundedPage(settings.maxPageSize());
        try {
            DeliveryLedgerSnapshot ledger = snapshotPort.loadLedger(safe);
            return new Prepared(safe, now, ledger, ledger == null ? "delivery ledger unavailable" : ledger.warning());
        } catch (RuntimeException exception) {
            return new Prepared(safe, now, null, "delivery ledger query failed");
        }
    }

    private static List<TaskObservation> scoped(DeliveryObservabilityQuery query, DeliveryLedgerSnapshot ledger) {
        return ledger.tasks().stream()
                .filter(task -> query.allProjects() || query.projectId().equals(task.projectId()))
                .toList();
    }

    private static boolean matchesFilters(DeliveryObservabilityQuery query, TaskObservation task) {
        if (!query.failureCategory().isBlank()
                && !foldFailure(task.errorCategory()).equals(query.failureCategory())) {
            return false;
        }
        if (!query.role().isBlank()
                && task.stages().stream().noneMatch(stage -> query.role().equals(stage.role()))) {
            return false;
        }
        if (!query.runtime().isBlank()
                && task.stages().stream().flatMap(stage -> stage.attempts().stream())
                .noneMatch(attempt -> query.runtime().equals(foldRuntime(attempt.runtime())))) {
            return false;
        }
        if (!query.provider().isBlank()
                && task.stages().stream().flatMap(stage -> stage.attempts().stream())
                .noneMatch(attempt -> query.provider().equalsIgnoreCase(foldProvider(attempt.provider())))) {
            return false;
        }
        return true;
    }

    private static DeliveryTaskPage.DeliveryTaskRow toRow(TaskObservation task) {
        double duration = Double.NaN;
        if (task.acceptedAt() != null && task.terminalAt() != null) {
            long millis = Duration.between(task.acceptedAt(), task.terminalAt()).toMillis();
            if (millis >= 0L) {
                duration = millis / 1000.0D;
            }
        }
        String role = task.stages().isEmpty() ? "" : safe(task.stages().getLast().role());
        String category = FAILURE.contains(status(task)) ? foldFailure(task.errorCategory()) : "";
        return new DeliveryTaskPage.DeliveryTaskRow(
                task.taskId(), task.title(), task.projectId(), task.status(), role, duration, category,
                "/admin/traces/" + task.taskId(),
                "/admin/rd-tasks/" + task.taskId()
        );
    }

    private static DeliveryObservabilityTimeseries emptyTimeseries(
            DeliveryObservabilityQuery query,
            Instant now,
            String warning
    ) {
        return new DeliveryObservabilityTimeseries(
                query.projectId(), query.window().token(),
                query.windowStart().toEpochMilli(), query.now().toEpochMilli(),
                now.toEpochMilli(), 1L, List.of(),
                List.of(DataQualityStatus.failed("delivery_ledger", now, warning))
        );
    }

    private static int bucketIndex(Instant timestamp, Instant start, long bucketMillis, int buckets) {
        long offset = Math.max(0L, Duration.between(start, timestamp).toMillis());
        int index = (int) (offset / bucketMillis);
        return Math.min(buckets - 1, Math.max(0, index));
    }

    private record Prepared(
            DeliveryObservabilityQuery query,
            Instant now,
            DeliveryLedgerSnapshot ledger,
            String warning
    ) {
    }

    private DeliveryObservabilityOverview reuseOrUnavailable(
            DeliveryObservabilityQuery query,
            Instant now,
            String warning
    ) {
        CachedOverview cached = lastSuccess.get();
        if (cached != null) {
            Duration age = Duration.between(cached.generatedAt(), now);
            if (!age.isNegative() && age.compareTo(settings.staleAfter()) <= 0) {
                return markStale(cached.overview(), now, warning);
            }
        }
        return DeliveryObservabilityOverview.unavailable(
                query,
                now,
                List.of(DataQualityStatus.failed("delivery_ledger", now, warning))
        );
    }

    private DeliveryObservabilityOverview markStale(
            DeliveryObservabilityOverview overview,
            Instant now,
            String warning
    ) {
        List<DataQualityStatus> quality = new ArrayList<>();
        quality.add(new DataQualityStatus(
                "delivery_ledger",
                false,
                now.toEpochMilli(),
                overview.generatedAtEpochMillis(),
                true,
                warning
        ));
        overview.dataQuality().stream()
                .filter(item -> !"delivery_ledger".equals(item.source()))
                .forEach(quality::add);
        return new DeliveryObservabilityOverview(
                overview.projectId(), overview.window(), overview.windowStartEpochMillis(),
                overview.windowEndEpochMillis(), overview.generatedAtEpochMillis(),
                overview.acceptedCount(), overview.terminalCount(), overview.runningCount(),
                overview.successRate(), overview.qaPassRate(), overview.prCreationRate(),
                overview.humanInterventionRate(), overview.retryRate(), overview.unknownFailureRate(),
                overview.endToEndLatency(), overview.phaseLatency(), overview.capacities(),
                overview.queueBacklog(), overview.oldestQueueAgeSeconds(), overview.invalidObservations(),
                quality,
                overview.durationHistograms(), overview.schedulerLive(), overview.usageBuckets(),
                overview.stageTotals(), overview.failureCategories()
        );
    }

    private DeliveryObservabilityOverview build(
            DeliveryObservabilityQuery query,
            DeliveryLedgerSnapshot ledger,
            RequirementDeliveryMetrics.Snapshot scheduler,
            Instant now
    ) {
        Instant start = query.windowStart();
        Instant end = query.now();
        List<TaskObservation> scoped = ledger.tasks().stream()
                .filter(task -> query.allProjects() || query.projectId().equals(task.projectId()))
                .toList();
        long accepted = scoped.stream()
                .filter(task -> inWindow(task.acceptedAt(), start, end))
                .count();
        List<TaskObservation> terminal = scoped.stream()
                .filter(task -> task.terminal() && TERMINAL.contains(status(task)) && inWindow(task.terminalAt(), start, end))
                .toList();
        long running = scoped.stream()
                .filter(task -> !task.terminal() && task.acceptedAt() != null && task.acceptedAt().isBefore(end))
                .count();
        long success = terminal.stream().filter(task -> SUCCESS.contains(status(task))).count();
        long failure = terminal.stream().filter(task -> FAILURE.contains(status(task))).count();
        long judged = success + failure;
        long human = terminal.stream().filter(task -> "FAILED_NEEDS_HUMAN".equals(status(task))).count();
        long withPr = terminal.stream().filter(task -> task.pullRequestUrl() != null && !task.pullRequestUrl().isBlank()).count();
        long unknownFailures = terminal.stream()
                .filter(task -> FAILURE.contains(status(task)))
                .filter(task -> unknownCategory(task))
                .count();

        List<StageObservation> finishedStages = scoped.stream()
                .flatMap(task -> task.stages().stream())
                .filter(stage -> stage.finished() && inWindow(stage.finishedAt(), start, end))
                .filter(stage -> query.role().isBlank() || query.role().equals(stage.role()))
                .toList();
        long qaFinished = finishedStages.stream().filter(stage -> "QA_AGENT".equals(stage.role())).count();
        long qaPassed = finishedStages.stream()
                .filter(stage -> "QA_AGENT".equals(stage.role()) && "SUCCEEDED".equals(safe(stage.status())))
                .count();
        long retried = finishedStages.stream().filter(stage -> stage.attemptNo() > 1).count();

        int invalid = 0;
        List<Long> endToEnd = new ArrayList<>();
        Map<String, List<Long>> phaseSamples = new LinkedHashMap<>();
        PHASES.forEach(phase -> phaseSamples.put(phase, new ArrayList<>()));
        Map<String, List<Long>> roleSamples = new LinkedHashMap<>();
        Map<String, UsageAccumulator> usage = new LinkedHashMap<>();
        Set<String> seenAttempts = new java.util.HashSet<>();
        for (TaskObservation task : terminal) {
            if (task.acceptedAt() == null || task.terminalAt() == null) {
                invalid++;
                continue;
            }
            long e2e = Duration.between(task.acceptedAt(), task.terminalAt()).toMillis();
            if (e2e < 0L) {
                invalid++;
            } else {
                endToEnd.add(e2e);
            }
            DeliveryPhaseDurationCalculator.PhaseSplit split = phaseCalculator.split(task.statusEvents());
            invalid += split.invalidCount();
            split.phaseDurationsMillis().forEach((phase, millis) ->
                    phaseSamples.computeIfAbsent(phase, ignored -> new ArrayList<>()).add(millis));
        }
        for (StageObservation stage : finishedStages) {
            DeliveryPhaseDurationCalculator.DurationSample sample =
                    phaseCalculator.roleDuration(stage.startedAt(), stage.finishedAt());
            if (sample.invalid()) {
                invalid++;
            } else if (sample.valid()) {
                roleSamples.computeIfAbsent(safe(stage.role()), ignored -> new ArrayList<>()).add(sample.millis());
            }
            for (DeliveryLedgerSnapshot.AttemptUsage attempt : stage.attempts()) {
                if (attempt.attemptId() != null && !attempt.attemptId().isBlank()
                        && !seenAttempts.add(attempt.attemptId())) {
                    continue;
                }
                usage.computeIfAbsent(usageKey(attempt.runtime(), attempt.provider(), stage.role()),
                        ignored -> new UsageAccumulator(attempt.runtime(), attempt.provider(), stage.role()))
                        .add(attempt);
            }
        }

        List<CommandObservation> waiting = ledger.commands().stream()
                .filter(CommandObservation::waiting)
                .toList();
        double oldest = waiting.isEmpty()
                ? Double.NaN
                : waiting.stream()
                .map(CommandObservation::createdAt)
                .filter(Objects::nonNull)
                .mapToLong(created -> Math.max(0L, Duration.between(created, end).toMillis()))
                .max()
                .orElse(0L) / 1000.0D;

        Map<String, PercentileMetric> phaseLatency = new LinkedHashMap<>();
        for (String phase : PHASES) {
            phaseLatency.put(phase, PercentileMetric.fromMillis(
                    true, phaseSamples.getOrDefault(phase, List.of()), settings.minP99Samples()));
        }

        List<CapacityMetric> capacities = capacities(scheduler);
        List<DataQualityStatus> quality = new ArrayList<>();
        quality.add(DataQualityStatus.ok("delivery_ledger", ledger.generatedAt()));
        SchedulerLiveMetrics schedulerLive = SchedulerLiveMetrics.from(scheduler);
        if (scheduler == null) {
            quality.add(DataQualityStatus.failed("scheduler_live", now, "scheduler snapshot unavailable"));
        } else {
            quality.add(DataQualityStatus.ok("scheduler_live", now));
        }

        Map<String, DurationHistogram> histograms = new LinkedHashMap<>();
        histograms.put("end_to_end", DurationHistogram.fromMillis(endToEnd));
        for (String phase : PHASES) {
            histograms.put(phase, DurationHistogram.fromMillis(phaseSamples.getOrDefault(phase, List.of())));
        }
        roleSamples.forEach((role, samples) -> histograms.put("role:" + role, DurationHistogram.fromMillis(samples)));

        return new DeliveryObservabilityOverview(
                query.projectId(),
                query.window().token(),
                start.toEpochMilli(),
                end.toEpochMilli(),
                now.toEpochMilli(),
                accepted,
                terminal.size(),
                running,
                RatioMetric.of(true, success, judged),
                RatioMetric.of(true, qaPassed, qaFinished),
                RatioMetric.of(true, withPr, terminal.size()),
                RatioMetric.of(true, human, judged),
                RatioMetric.of(true, retried, finishedStages.size()),
                RatioMetric.of(true, unknownFailures, failure),
                PercentileMetric.fromMillis(true, endToEnd, settings.minP99Samples()),
                phaseLatency,
                capacities,
                waiting.size(),
                oldest,
                invalid,
                quality,
                histograms,
                schedulerLive,
                usage.values().stream().map(UsageAccumulator::toBucket).toList(),
                stageTotals(scoped),
                failureCategories(terminal)
        );
    }

    private static List<CapacityMetric> capacities(RequirementDeliveryMetrics.Snapshot scheduler) {
        if (scheduler == null) {
            return List.of();
        }
        List<CapacityMetric> result = new ArrayList<>();
        for (ScheduleResourceClass resource : ScheduleResourceClass.values()) {
            int inUse = scheduler.currentInFlightByResource().getOrDefault(resource, 0);
            int capacity = scheduler.currentCapacityByResource().getOrDefault(resource, 0);
            result.add(new CapacityMetric(resource.name(), inUse, capacity, capacity > 0 && inUse >= capacity));
        }
        return result;
    }

    private static List<RoleStatusCount> stageTotals(List<TaskObservation> tasks) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TaskObservation task : tasks) {
            for (StageObservation stage : task.stages()) {
                String key = safe(stage.role()) + "\n" + safe(stage.status());
                counts.merge(key, 1L, Long::sum);
            }
        }
        List<RoleStatusCount> result = new ArrayList<>();
        counts.forEach((key, count) -> {
            int split = key.indexOf('\n');
            result.add(new RoleStatusCount(key.substring(0, split), key.substring(split + 1), count));
        });
        return List.copyOf(result);
    }

    private static Map<String, Long> failureCategories(List<TaskObservation> terminal) {
        Map<String, Long> categories = new LinkedHashMap<>();
        for (TaskObservation task : terminal) {
            if (!FAILURE.contains(status(task))) {
                continue;
            }
            String category = foldFailure(task.errorCategory());
            if ("UNKNOWN".equals(category)) {
                category = foldFailure(task.stages().stream()
                        .map(StageObservation::errorCategory)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .orElse("UNKNOWN"));
            }
            categories.merge(category, 1L, Long::sum);
        }
        return Map.copyOf(categories);
    }

    private static String foldFailure(String raw) {
        String value = safe(raw).toUpperCase(Locale.ROOT);
        return switch (value) {
            case "TIMEOUT", "PROVIDER", "VALIDATION", "BUDGET", "LEASE", "CANCELLED",
                    "SCOPE_VIOLATION", "UNKNOWN" -> value;
            default -> "UNKNOWN";
        };
    }

    private static String usageKey(String runtime, String provider, String role) {
        return foldRuntime(runtime) + "\n" + foldProvider(provider) + "\n" + safe(role);
    }

    private static String foldRuntime(String runtime) {
        String value = safe(runtime).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "pi", "claude-compat" -> value;
            default -> "unknown";
        };
    }

    private static String foldProvider(String provider) {
        String value = safe(provider).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "anthropic", "deepseek", "openai", "longcat" -> value;
            default -> "OTHER";
        };
    }

    private static final class UsageAccumulator {
        private final String runtime;
        private final String provider;
        private final String role;
        private long input;
        private long output;
        private long cache;
        private double cost;
        private boolean costAvailable;
        private final List<Long> ttft = new ArrayList<>();
        private final List<Long> firstResponse = new ArrayList<>();

        private UsageAccumulator(String runtime, String provider, String role) {
            this.runtime = foldRuntime(runtime);
            this.provider = foldProvider(provider);
            this.role = safe(role);
        }

        private void add(DeliveryLedgerSnapshot.AttemptUsage attempt) {
            if (attempt.usageAvailable()) {
                input += attempt.inputTokens();
                output += attempt.outputTokens();
                cache += attempt.cacheTokens();
            }
            if (!Double.isNaN(attempt.estimatedCostCny())) {
                cost += attempt.estimatedCostCny();
                costAvailable = true;
            }
            if (attempt.firstTokenMillis() >= 0L) {
                ttft.add(attempt.firstTokenMillis());
            }
            if (attempt.firstProviderResponseMillis() >= 0L) {
                firstResponse.add(attempt.firstProviderResponseMillis());
            }
        }

        private UsageBucket toBucket() {
            return new UsageBucket(
                    runtime, provider, role, input, output, cache,
                    costAvailable ? cost : Double.NaN,
                    DurationHistogram.fromMillis(ttft),
                    DurationHistogram.fromMillis(firstResponse)
            );
        }
    }

    private static boolean inWindow(Instant timestamp, Instant start, Instant end) {
        return timestamp != null && !timestamp.isBefore(start) && timestamp.isBefore(end);
    }

    private static String status(TaskObservation task) {
        return safe(task.status()).toUpperCase(Locale.ROOT);
    }

    private static boolean unknownCategory(TaskObservation task) {
        String category = safe(task.errorCategory()).toUpperCase(Locale.ROOT);
        return category.isBlank() || "UNKNOWN".equals(category);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record CachedOverview(DeliveryObservabilityOverview overview, Instant generatedAt) {
    }
}
