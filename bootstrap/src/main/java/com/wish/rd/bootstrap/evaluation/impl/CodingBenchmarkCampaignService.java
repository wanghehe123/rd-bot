package com.wish.rd.bootstrap.evaluation.impl;

import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.bootstrap.evaluation.model.CodingBenchmarkTrialDetailView;
import com.wish.rd.engine.evaluation.CodingBenchmarkCaseSelector;
import com.wish.rd.engine.evaluation.CodingBenchmarkPlanFactory;
import com.wish.rd.engine.evaluation.CodingBenchmarkProbePlanFactory;
import com.wish.rd.engine.evaluation.CodingBenchmarkTrialStore;
import com.wish.rd.engine.evaluation.EvaluationRunStore;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkCase;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkPlan;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkSnapshot;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialEvent;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkVerdict;
import com.wish.rd.engine.evaluation.model.EvaluationExecutionResult;
import com.wish.rd.engine.evaluation.model.EvaluationMode;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Orchestrates coding benchmark campaigns from a frozen snapshot through trial dispatch and run lifecycle.
 *
 * <p>The service creates a parent {@link EvaluationRun} with {@code CODING_BENCHMARK} mode, advances it
 * through the coding-benchmark status graph, and kicks {@link CodingBenchmarkDispatcher} for convergent
 * trial execution. Pause, resume, and cancel are coding-benchmark-specific control-plane operations that
 * do not reuse the legacy Python evaluation cancel path.
 *
 * <p>Two modes are supported:
 *
 * <ul>
 *   <li>{@code PROBE}: 1-2 cases × 4 arms, no sentinels, selected by {@link CodingBenchmarkCaseSelector}</li>
 *   <li>{@code FORMAL}: cost-limited 5 cases × 4 arms (20 trials); full 88-trial matrix via
 *       {@link #createFullFormalCampaign}</li>
 * </ul>
 */
@Service
public class CodingBenchmarkCampaignService implements CodingBenchmarkCampaignCompletion {

    private static final Logger log = LoggerFactory.getLogger(CodingBenchmarkCampaignService.class);
    private static final String STUB_METRICS_JSON = "[{\"metric\":\"coding_benchmark_stub\",\"value\":1}]";
    private static final int ARTIFACT_PREVIEW_CHARS = 48_000;
    private static final int ARTIFACT_FULL_CHARS = 200_000;
    private static final Map<String, ArtifactSpec> ARTIFACT_SPECS = buildArtifactSpecs();

    private final FileSystemCodingBenchmarkCatalog catalog;
    private final CodingBenchmarkTrialStore trialStore;
    private final EvaluationRunStore runStore;
    private final CodingBenchmarkProbePlanFactory probeFactory;
    private final CodingBenchmarkPlanFactory planFactory;
    private final CodingBenchmarkCaseSelector caseSelector;
    private final EvaluationProperties properties;
    private final CodingBenchmarkDispatcher dispatcher;
    private final FakeCodingBenchmarkExecutionPort fakePort;
    private final SnowflakeIdGenerator idGenerator;

    public CodingBenchmarkCampaignService(
            FileSystemCodingBenchmarkCatalog catalog,
            CodingBenchmarkTrialStore trialStore,
            EvaluationRunStore runStore,
            EvaluationProperties properties,
            @Lazy CodingBenchmarkDispatcher dispatcher,
            @Autowired(required = false) FakeCodingBenchmarkExecutionPort fakePort,
            SnowflakeIdGenerator idGenerator
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.trialStore = Objects.requireNonNull(trialStore, "trialStore must not be null");
        this.runStore = Objects.requireNonNull(runStore, "runStore must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.fakePort = fakePort;
        this.probeFactory = new CodingBenchmarkProbePlanFactory(idGenerator::nextIdString);
        this.planFactory = new CodingBenchmarkPlanFactory(idGenerator::nextIdString);
        this.caseSelector = new CodingBenchmarkCaseSelector();
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
    }

    // ---------------------------------------------------------------------------
    // Snapshot discovery
    // ---------------------------------------------------------------------------

    /** @return all verified-ready snapshots from the catalog, newest-first by snapshotId. */
    public List<CodingBenchmarkSnapshot> listReadySnapshots() {
        return catalog.readySnapshots().stream()
                .sorted((a, b) -> b.snapshotId().compareTo(a.snapshotId()))
                .toList();
    }

    /** @return a verified-ready snapshot if it exists, otherwise empty. */
    public java.util.Optional<CodingBenchmarkSnapshot> findReadySnapshot(String snapshotId) {
        return catalog.readySnapshots().stream()
                .filter(s -> s.snapshotId().equals(snapshotId))
                .findFirst();
    }

    /**
     * Lists recent coding-benchmark campaigns newest-first.
     *
     * @param limit maximum campaigns to return (clamped to 1–100)
     * @return coding-benchmark evaluation runs only
     */
    public List<EvaluationRun> listCampaigns(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return runStore.list().stream()
                .filter(run -> run.config().mode() == EvaluationMode.CODING_BENCHMARK)
                .sorted((a, b) -> Long.compare(b.createdAtEpochMillis(), a.createdAtEpochMillis()))
                .limit(safeLimit)
                .toList();
    }

    /**
     * Returns the coding-benchmark campaign run or empty when missing / wrong mode.
     *
     * @param runId parent evaluation run id
     */
    public java.util.Optional<EvaluationRun> findCampaign(String runId) {
        return runStore.find(runId)
                .filter(run -> run.config().mode() == EvaluationMode.CODING_BENCHMARK);
    }

    /**
     * Lists all trials for a coding-benchmark campaign, ordered by case then arm.
     *
     * @param runId parent campaign / evaluation run id
     * @throws NoSuchElementException when the run is missing or not a coding benchmark
     */
    public List<CodingBenchmarkTrial> listTrials(String runId) {
        requireCodingBenchmarkRun(runId);
        return trialStore.listByCampaign(runId).stream()
                .sorted((a, b) -> {
                    int byCase = a.caseId().compareTo(b.caseId());
                    if (byCase != 0) {
                        return byCase;
                    }
                    int byArm = a.arm().compareTo(b.arm());
                    if (byArm != 0) {
                        return byArm;
                    }
                    return Integer.compare(a.replicateNo(), b.replicateNo());
                })
                .toList();
    }

    /**
     * Builds a console detail view for one trial: lifecycle events plus allowlisted workspace artifacts.
     */
    public CodingBenchmarkTrialDetailView getTrialDetail(String runId, String trialId) {
        CodingBenchmarkTrial trial = requireTrial(runId, trialId);
        List<CodingBenchmarkTrialEvent> events = trialStore.listEvents(trialId);
        Path trialRoot = trialWorkspaceRoot(runId, trialId);
        List<CodingBenchmarkTrialDetailView.Artifact> artifacts = new ArrayList<>();
        List<CodingBenchmarkTrialDetailView.Preview> previews = new ArrayList<>();
        for (ArtifactSpec spec : ARTIFACT_SPECS.values()) {
            Path file = resolveArtifactFile(trialRoot, spec.relativePath());
            boolean available = file != null && Files.isRegularFile(file);
            long size = 0L;
            if (available) {
                try {
                    size = Files.size(file);
                } catch (IOException ignored) {
                    available = false;
                }
            }
            artifacts.add(new CodingBenchmarkTrialDetailView.Artifact(spec.key(), spec.label(), size, available));
            if (available && spec.autoPreview()) {
                CodingBenchmarkTrialDetailView.ArtifactContent content = readArtifact(file, spec, ARTIFACT_PREVIEW_CHARS);
                previews.add(new CodingBenchmarkTrialDetailView.Preview(
                        spec.key(), spec.label(), content.content(), content.truncated()));
            }
        }
        return new CodingBenchmarkTrialDetailView(trial, events, artifacts, previews);
    }

    /**
     * Reads one allowlisted trial artifact as text (truncated for safety).
     */
    public CodingBenchmarkTrialDetailView.ArtifactContent getTrialArtifact(
            String runId,
            String trialId,
            String artifactKey
    ) {
        requireTrial(runId, trialId);
        ArtifactSpec spec = ARTIFACT_SPECS.get(artifactKey == null ? "" : artifactKey.trim());
        if (spec == null) {
            throw new IllegalArgumentException("unsupported artifact key: " + artifactKey);
        }
        Path file = resolveArtifactFile(trialWorkspaceRoot(runId, trialId), spec.relativePath());
        if (file == null || !Files.isRegularFile(file)) {
            throw new NoSuchElementException("artifact not found: " + artifactKey);
        }
        return readArtifact(file, spec, ARTIFACT_FULL_CHARS);
    }

    private CodingBenchmarkTrial requireTrial(String runId, String trialId) {
        requireCodingBenchmarkRun(runId);
        CodingBenchmarkTrial trial = trialStore.find(trialId)
                .orElseThrow(() -> new NoSuchElementException("trial not found: " + trialId));
        if (!runId.equals(trial.campaignId())) {
            throw new NoSuchElementException("trial not found in campaign: " + trialId);
        }
        return trial;
    }

    private Path trialWorkspaceRoot(String runId, String trialId) {
        return properties.resolvedCodingBenchmarkRoot()
                .resolve("output")
                .resolve(runId)
                .resolve(trialId)
                .toAbsolutePath()
                .normalize();
    }

    private static Path resolveArtifactFile(Path trialRoot, String relativePath) {
        Path resolved = trialRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(trialRoot)) {
            return null;
        }
        return resolved;
    }

    private static CodingBenchmarkTrialDetailView.ArtifactContent readArtifact(
            Path file,
            ArtifactSpec spec,
            int maxChars
    ) {
        try {
            long size = Files.size(file);
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            boolean truncated = raw.length() > maxChars;
            String content = truncated ? raw.substring(0, maxChars) + "\n…(truncated)…" : raw;
            return new CodingBenchmarkTrialDetailView.ArtifactContent(
                    spec.key(), spec.label(), content, truncated, size);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read artifact " + spec.key() + ": " + exception.getMessage(),
                    exception);
        }
    }

    private static Map<String, ArtifactSpec> buildArtifactSpecs() {
        Map<String, ArtifactSpec> specs = new LinkedHashMap<>();
        addSpec(specs, "problem.md", "题目说明", "input/problem.md", true);
        addSpec(specs, "arm-profile.json", "Arm 配置", "input/arm-profile.json", true);
        addSpec(specs, "result.json", "Agent 结果", "output/result.json", true);
        addSpec(specs, "candidate.patch", "候选补丁", "output/candidate.patch", true);
        addSpec(specs, "candidate-patch-source.json", "补丁来源", "output/candidate-patch-source.json", true);
        addSpec(specs, "runtime-meta.json", "运行元数据", "output/runtime-meta.json", true);
        addSpec(specs, "agent-events.jsonl", "Agent 事件流", "output/agent-events.jsonl", false);
        addSpec(specs, "test.log", "Agent 测试日志", "output/test.log", false);
        addSpec(specs, "INFRA_QUARANTINE", "基建隔离记录", "output/INFRA_QUARANTINE", true);
        addSpec(specs, "oracle-result.json", "Oracle 评分结果", "oracle-output/oracle-result.json", true);
        return Map.copyOf(specs);
    }

    private static void addSpec(
            Map<String, ArtifactSpec> specs,
            String key,
            String label,
            String relativePath,
            boolean autoPreview
    ) {
        specs.put(key, new ArtifactSpec(key, label, relativePath, autoPreview));
    }

    private record ArtifactSpec(String key, String label, String relativePath, boolean autoPreview) {}

    /**
     * Creates a PROBE campaign that selects one FRESH_PRIMARY and one PUBLIC_ANCHOR case
     * and schedules 8 trials (2 cases × 4 arms).
     *
     * @param snapshotId a frozen snapshot discoverable by the catalog
     * @param name human-readable campaign name; defaults to a generated one if blank
     * @return the created probe evaluation run in {@link EvaluationRunStatus#RUNNING_TRIALS}
     * @throws NoSuchElementException if the snapshot is not found or not ready
     */
    public EvaluationRun createProbeCampaign(String snapshotId, String name) {
        String resolvedName = (name == null || name.isBlank())
                ? "Coding Benchmark Probe · " + snapshotId
                : name;

        CodingBenchmarkSnapshot snapshot = catalog.readySnapshots().stream()
                .filter(s -> s.snapshotId().equals(snapshotId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "snapshot not found or not ready: " + snapshotId));

        List<CodingBenchmarkCase> allCases = loadCases(snapshotId);
        List<CodingBenchmarkCase> probeCases = caseSelector.selectProbeCases(allCases);
        if (probeCases.isEmpty()) {
            throw new IllegalArgumentException("snapshot has no valid cases for probe");
        }

        List<String> probeCaseIds = probeCases.stream()
                .map(CodingBenchmarkCase::caseId)
                .toList();
        String runId = idGenerator.nextIdString();
        List<CodingBenchmarkTrial> trials = probeFactory.create(runId, probeCaseIds);

        return createCampaign(snapshotId, runId, resolvedName, trials);
    }

    /**
     * Creates a cost-limited FORMAL campaign: 5 stratified cases × 4 arms = 20 trials (no sentinels).
     *
     * <p>Full 20-case/88-trial formal remains available via {@link #createFullFormalCampaign} when budget allows.</p>
     *
     * @param snapshotId a frozen snapshot discoverable by the catalog
     * @param name human-readable campaign name; defaults to a generated one if blank
     * @param sentinelCaseIds ignored in cost-limited mode (kept for API compatibility)
     * @return the created formal evaluation run in {@link EvaluationRunStatus#RUNNING_TRIALS}
     */
    public EvaluationRun createFormalCampaign(String snapshotId, String name, Set<String> sentinelCaseIds) {
        return createCostLimitedFormalCampaign(snapshotId, name);
    }

    /**
     * Creates a cost-limited formal campaign (5 cases × 4 arms, no sentinels).
     *
     * @param snapshotId frozen snapshot id
     * @param name campaign display name
     * @return running evaluation run with 20 trials
     */
    public EvaluationRun createCostLimitedFormalCampaign(String snapshotId, String name) {
        String resolvedName = (name == null || name.isBlank())
                ? "Coding Benchmark Formal · 5-case · " + snapshotId
                : name;

        CodingBenchmarkSnapshot snapshot = catalog.readySnapshots().stream()
                .filter(s -> s.snapshotId().equals(snapshotId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "snapshot not found or not ready: " + snapshotId));

        List<CodingBenchmarkCase> allCases = loadCases(snapshotId);
        List<CodingBenchmarkCase> selected = caseSelector.selectCostLimitedCases(
                allCases, CodingBenchmarkProbePlanFactory.COST_LIMITED_MAX_CASES);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("snapshot has no valid cases for cost-limited formal");
        }
        List<String> caseIds = selected.stream().map(CodingBenchmarkCase::caseId).toList();
        String runId = idGenerator.nextIdString();
        List<CodingBenchmarkTrial> trials = probeFactory.create(runId, caseIds);
        log.info("[CODING_BENCHMARK] cost-limited formal cases={} trials={}", caseIds, trials.size());
        return createCampaign(snapshotId, runId, resolvedName, trials);
    }

    /**
     * Creates the full 20-case FORMAL campaign with sentinel A/D reruns (88 trials).
     *
     * @param snapshotId a frozen snapshot discoverable by the catalog
     * @param name human-readable campaign name
     * @param sentinelCaseIds exactly 4 pre-registered sentinel case IDs
     * @return the created formal evaluation run
     */
    public EvaluationRun createFullFormalCampaign(String snapshotId, String name, Set<String> sentinelCaseIds) {
        String resolvedName = (name == null || name.isBlank())
                ? "Coding Benchmark Formal · " + snapshotId
                : name;

        CodingBenchmarkSnapshot snapshot = catalog.readySnapshots().stream()
                .filter(s -> s.snapshotId().equals(snapshotId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "snapshot not found or not ready: " + snapshotId));

        List<CodingBenchmarkCase> allCases = loadCases(snapshotId);
        CodingBenchmarkPlan plan = planFactory.create(snapshotId, allCases, sentinelCaseIds);

        String runId = idGenerator.nextIdString();
        List<CodingBenchmarkTrial> trials = plan.trials().stream()
                .map(t -> CodingBenchmarkTrial.queued(
                        t.trialId(), runId, t.caseId(), t.arm(), t.replicateNo(), System.currentTimeMillis()))
                .toList();

        return createCampaign(snapshotId, runId, resolvedName, trials);
    }

    /**
     * Pauses trial dispatch for an active coding-benchmark run without changing its status.
     *
     * @param runId parent evaluation run identifier
     * @return the updated run snapshot with {@code dispatchPaused=true}
     */
    public EvaluationRun pause(String runId) {
        long now = System.currentTimeMillis();
        EvaluationRun current = requireCodingBenchmarkRun(runId);
        if (current.dispatchPaused()) {
            return current;
        }
        return runStore.setDispatchPaused(runId, true, current.version(), now);
    }

    /**
     * Clears the dispatch-pause flag and re-kicks the dispatcher when trials are still running.
     *
     * @param runId parent evaluation run identifier
     * @return the updated run snapshot with {@code dispatchPaused=false}
     */
    public EvaluationRun resume(String runId) {
        long now = System.currentTimeMillis();
        EvaluationRun current = requireCodingBenchmarkRun(runId);
        EvaluationRun updated = current.dispatchPaused()
                ? runStore.setDispatchPaused(runId, false, current.version(), now)
                : current;
        if (updated.status() == EvaluationRunStatus.RUNNING_TRIALS && !updated.dispatchPaused()) {
            dispatcher.kick(runId, snapshotIdFrom(updated));
        }
        return updated;
    }

    /**
     * Cancels an active coding-benchmark run and every non-terminal trial.
     *
     * @param runId parent evaluation run identifier
     * @return the cancelled run snapshot
     */
    public EvaluationRun cancel(String runId) {
        long now = System.currentTimeMillis();
        EvaluationRun current = requireCodingBenchmarkRun(runId);
        if (current.status().isTerminal()) {
            return current;
        }

        EvaluationRun cancelRequested = current.status() == EvaluationRunStatus.CANCEL_REQUESTED
                ? current
                : runStore.transition(runId, current.status(), EvaluationRunStatus.CANCEL_REQUESTED,
                "cancel requested", "", "", now);

        signalFakeCancel(runId);
        cancelNonTerminalTrials(runId, now);

        long finishNow = System.currentTimeMillis();
        EvaluationRun afterRequest = runStore.find(runId).orElse(cancelRequested);
        if (afterRequest.status() == EvaluationRunStatus.CANCELLED) {
            return afterRequest;
        }
        return runStore.transition(runId, EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.CANCELLED,
                "campaign cancelled", "", "", finishNow);
    }

    /**
     * Advances a drained campaign through scoring and reporting to a successful terminal state.
     *
     * @param runId parent evaluation run identifier
     */
    @Override
    public void onTrialsDrained(String runId) {
        EvaluationRun current = runStore.find(runId).orElse(null);
        if (current == null || current.status() != EvaluationRunStatus.RUNNING_TRIALS) {
            return;
        }

        long now = System.currentTimeMillis();
        runStore.transition(runId, EvaluationRunStatus.RUNNING_TRIALS, EvaluationRunStatus.SCORING,
                "scoring trials", "", "", now);
        long reportingNow = System.currentTimeMillis();
        EvaluationRun reporting = runStore.transition(runId, EvaluationRunStatus.SCORING, EvaluationRunStatus.REPORTING,
                "reporting", "", "", reportingNow);

        int sampleCount = Math.max(reporting.sampleCount(), reporting.passedSampleCount() + reporting.failedSampleCount());
        int passed = reporting.passedSampleCount();
        int failed = reporting.failedSampleCount();
        boolean overallPassed = failed == 0 && sampleCount > 0;
        EvaluationExecutionResult result = new EvaluationExecutionResult(
                sampleCount, passed, failed, overallPassed, STUB_METRICS_JSON, List.of());
        long completeNow = System.currentTimeMillis();
        runStore.complete(runId, EvaluationRunStatus.REPORTING, result, completeNow);
    }

    private EvaluationRun createCampaign(String snapshotId, String runId, String name, List<CodingBenchmarkTrial> trials) {
        long now = System.currentTimeMillis();

        EvaluationRunConfig config = new EvaluationRunConfig(
                name,
                snapshotId + ".json",
                null,
                snapshotId,
                0,
                "",
                "",
                3600,
                null,
                0,
                false,
                "",
                "",
                EvaluationMode.CODING_BENCHMARK,
                snapshotId
        );

        EvaluationRun run = EvaluationRun.created(runId, config, 1, "", now);
        runStore.create(run);
        trialStore.createAll(runId, trials);

        log.info("[CODING_BENCHMARK] created campaign runId={} snapshotId={} trials={}",
                runId, snapshotId, trials.size());

        EvaluationRun advanced = driveToRunningTrials(run, now);
        dispatcher.kick(advanced.runId(), snapshotId);
        return advanced;
    }

    private EvaluationRun driveToRunningTrials(EvaluationRun run, long now) {
        EvaluationRun queued = runStore.transition(run.runId(), EvaluationRunStatus.CREATED,
                EvaluationRunStatus.QUEUED, "queued", "", "", now);
        EvaluationRun preparing = runStore.transition(queued.runId(), EvaluationRunStatus.QUEUED,
                EvaluationRunStatus.PREPARING, "preparing", "", "", now);
        return runStore.transition(preparing.runId(), EvaluationRunStatus.PREPARING,
                EvaluationRunStatus.RUNNING_TRIALS, "running trials", "", "", now);
    }

    private void cancelNonTerminalTrials(String runId, long now) {
        for (CodingBenchmarkTrial trial : trialStore.listByCampaign(runId)) {
            if (!trial.status().isTerminal()) {
                trialStore.transition(
                        trial.trialId(),
                        trial.status(),
                        trial.version(),
                        CodingBenchmarkTrialStatus.CANCELLED,
                        CodingBenchmarkVerdict.INFRA_ERROR,
                        "CANCELLED",
                        "campaign cancelled",
                        now
                );
            }
        }
    }

    private void signalFakeCancel(String runId) {
        if (fakePort != null) {
            fakePort.cancelCampaign(runId);
        }
    }

    private EvaluationRun requireCodingBenchmarkRun(String runId) {
        EvaluationRun current = runStore.find(runId)
                .orElseThrow(() -> new NoSuchElementException("evaluation run not found: " + runId));
        if (current.config().mode() != EvaluationMode.CODING_BENCHMARK) {
            throw new IllegalArgumentException("not a coding benchmark run: " + runId);
        }
        return current;
    }

    static String snapshotIdFrom(EvaluationRun run) {
        String fromConfig = run.config().snapshotId();
        if (!fromConfig.isBlank()) {
            return fromConfig;
        }
        return run.config().environmentId();
    }

    private List<CodingBenchmarkCase> loadCases(String snapshotId) {
        Path snapshotRoot = properties.resolvedCodingBenchmarkRoot().resolve(snapshotId);
        try {
            String json = Files.readString(snapshotRoot.resolve("dataset-manifest.json"));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var tree = mapper.readTree(json);
            var casesNode = tree.get("cases");
            if (casesNode == null || !casesNode.isArray()) {
                throw new IllegalStateException("dataset-manifest has no cases array");
            }
            return CodingBenchmarkCaseSelector.fromRawCases(
                    mapper.convertValue(casesNode,
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}));
        } catch (IOException e) {
            throw new IllegalStateException("failed to load dataset manifest: " + snapshotId, e);
        }
    }
}
