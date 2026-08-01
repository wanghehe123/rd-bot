package com.wish.rd.bootstrap.evaluation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.engine.evaluation.CodingBenchmarkCaseRuntime;
import com.wish.rd.engine.evaluation.CodingBenchmarkExecutionPort;
import com.wish.rd.engine.evaluation.CodingBenchmarkRuntimeCatalogPort;
import com.wish.rd.engine.evaluation.CodingBenchmarkTrialStore;
import com.wish.rd.engine.evaluation.EvaluationTaskSchedulerPort;
import com.wish.rd.engine.evaluation.impl.InMemoryEvaluationRunStore;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionResult;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkRuntimeAttestation;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkVerdict;
import com.wish.rd.engine.evaluation.model.EvaluationMode;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CodingBenchmarkDispatcherTest {

    private static final String CAMPAIGN_ID = "9001";
    private static final String SNAPSHOT_ID = "snap-test";

    @TempDir
    Path tempDir;

    private InMemoryEvaluationRunStore runStore;
    private RecordingTrialStore trialStore;
    private FakeCodingBenchmarkExecutionPort executionPort;
    private ExecutorService workerPool;
    private CodingBenchmarkDispatcher dispatcher;
    private Path prepRoot;
    private CodingBenchmarkCaseRuntime caseRuntime;

    @BeforeEach
    void setUp() throws IOException {
        runStore = new InMemoryEvaluationRunStore();
        trialStore = new RecordingTrialStore();
        executionPort = new FakeCodingBenchmarkExecutionPort(5L);
        workerPool = Executors.newCachedThreadPool();
        prepRoot = Files.createDirectories(tempDir.resolve("prep"));
        Path repo = Files.createDirectories(prepRoot.resolve("repo"));
        Path cache = Files.createDirectories(prepRoot.resolve("cache"));
        Path verifierCache = Files.createDirectories(prepRoot.resolve("verifier-cache"));
        caseRuntime = CodingBenchmarkCaseRuntime.of(
                prepRoot,
                "case-01",
                "registry.example/agent@sha256:" + "a".repeat(64),
                "registry.example/oracle@sha256:" + "b".repeat(64),
                repo,
                cache,
                repo,
                verifierCache,
                null,
                null,
                "tests/runtime",
                List.of("/opt/rd/agent"),
                List.of("/opt/rd/oracle"),
                60_000L,
                8_000L
        );
        dispatcher = newDispatcher(runId -> {
        });
        seedRun(false);
    }

    @AfterEach
    void tearDown() {
        workerPool.shutdownNow();
    }

    @Test
    void shouldNotClaimWhenDispatchPaused() throws Exception {
        runStore = new InMemoryEvaluationRunStore();
        trialStore = new RecordingTrialStore();
        dispatcher = newDispatcher(runId -> {
        });
        seedRun(true);
        trialStore.createAll(CAMPAIGN_ID, List.of(queued("trial-1", "case-01", CodingBenchmarkArm.A)));

        dispatcher.kick(CAMPAIGN_ID, SNAPSHOT_ID);
        Thread.sleep(150L);

        assertEquals(0, trialStore.claimCallCount());
    }

    @Test
    void shouldNotClaimFifthTrialWhileFourAreActive() throws Exception {
        List<CodingBenchmarkTrial> trials = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            trials.add(active("trial-active-" + index, "case-active-" + index, CodingBenchmarkTrialStatus.RUNNING_AGENTS));
        }
        trials.add(queued("trial-queued", "case-queued", CodingBenchmarkArm.A));
        trialStore.createAll(CAMPAIGN_ID, trials);

        dispatcher.kick(CAMPAIGN_ID, SNAPSHOT_ID);
        Thread.sleep(150L);

        assertEquals(0, trialStore.claimCallCount());
    }

    @Test
    void shouldNotClaimSecondTrialForSameCaseWhileFirstIsActive() throws Exception {
        trialStore.createAll(CAMPAIGN_ID, List.of(
                active("trial-active", "case-01", CodingBenchmarkTrialStatus.RUNNING_AGENTS),
                queued("trial-queued-same-case", "case-01", CodingBenchmarkArm.B),
                queued("trial-queued-other-case", "case-02", CodingBenchmarkArm.A)
        ));

        dispatcher.kick(CAMPAIGN_ID, SNAPSHOT_ID);
        Thread.sleep(150L);

        assertEquals(CodingBenchmarkTrialStatus.QUEUED,
                trialStore.find("trial-queued-same-case").orElseThrow().status());
        assertTrue(trialStore.find("trial-queued-other-case").orElseThrow().status() != CodingBenchmarkTrialStatus.QUEUED,
                "dispatcher may claim a different case while case-01 stays active");
    }

    @Test
    void shouldBlockFourthOracleUntilOneFinishes() throws Exception {
        trialStore.createAll(CAMPAIGN_ID, List.of(
                oracleActive("oracle-1", "case-o1"),
                oracleActive("oracle-2", "case-o2"),
                oracleActive("oracle-3", "case-o3"),
                queued("trial-4", "case-04", CodingBenchmarkArm.A)
        ));

        CountDownLatch enteredOracleGate = new CountDownLatch(1);
        CountDownLatch oracleSlotAcquired = new CountDownLatch(1);
        AtomicReference<String> blockedTrialId = new AtomicReference<>();
        CodingBenchmarkExecutionPort blockingPort = (request, hooks) -> {
            blockedTrialId.set(request.trial().trialId());
            enteredOracleGate.countDown();
            hooks.beforeOracle();
            oracleSlotAcquired.countDown();
            return passResult(request);
        };
        dispatcher = newDispatcher(runId -> {
        }, blockingPort, snapshotId -> List.of(
                runtimeForCase("case-o1"),
                runtimeForCase("case-o2"),
                runtimeForCase("case-o3"),
                runtimeForCase("case-04")
        ));

        dispatcher.kick(CAMPAIGN_ID, SNAPSHOT_ID);
        assertTrue(enteredOracleGate.await(3, TimeUnit.SECONDS), "trial should reach beforeOracle");

        assertEquals(CodingBenchmarkTrialStatus.RUNNING_AGENTS,
                trialStore.find(blockedTrialId.get()).orElseThrow().status());

        trialStore.transition(
                "oracle-1",
                CodingBenchmarkTrialStatus.RUNNING_ORACLE,
                trialStore.find("oracle-1").orElseThrow().version(),
                CodingBenchmarkTrialStatus.SUCCEEDED,
                CodingBenchmarkVerdict.PASS, "", "",
                System.currentTimeMillis()
        );

        assertTrue(oracleSlotAcquired.await(3, TimeUnit.SECONDS),
                "fourth trial should acquire an oracle slot after one oracle finishes");
        long oracleRunning = trialStore.listByCampaign(CAMPAIGN_ID).stream()
                .filter(trial -> trial.status() == CodingBenchmarkTrialStatus.RUNNING_ORACLE)
                .count();
        assertTrue(oracleRunning <= CodingBenchmarkDispatcher.MAX_ORACLE_TRIALS);
    }

    @Test
    void shouldUseNonZeroVersionForTerminalTransition() throws Exception {
        trialStore.createAll(CAMPAIGN_ID, List.of(queued("trial-1", "case-01", CodingBenchmarkArm.A)));

        newSynchronousDispatcher(runId -> {
        }).kick(CAMPAIGN_ID, SNAPSHOT_ID);

        assertEquals(CodingBenchmarkTrialStatus.SUCCEEDED,
                trialStore.find("trial-1").orElseThrow().status());
        assertFalse(trialStore.terminalExpectedVersions().isEmpty());
        for (long version : trialStore.terminalExpectedVersions()) {
            assertTrue(version > 0L, "terminal CAS must use the loaded version, not 0");
        }
    }

    @Test
    void shouldKeepConcurrentOracleCountAtMostThree() throws Exception {
        executionPort = new FakeCodingBenchmarkExecutionPort(30L);
        List<CodingBenchmarkTrial> trials = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            trials.add(queued("trial-" + index, "case-" + index, CodingBenchmarkArm.A));
        }
        trialStore.createAll(CAMPAIGN_ID, trials);
        CodingBenchmarkRuntimeCatalogPort multiCaseCatalog = snapshotId -> {
            List<CodingBenchmarkCaseRuntime> runtimes = new ArrayList<>();
            for (int index = 0; index < 7; index++) {
                runtimes.add(runtimeForCase("case-" + index));
            }
            return runtimes;
        };
        dispatcher = newDispatcher(runId -> {
        }, executionPort, multiCaseCatalog);

        AtomicInteger maxOracle = new AtomicInteger();
        Thread observer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                int running = (int) trialStore.listByCampaign(CAMPAIGN_ID).stream()
                        .filter(trial -> trial.status() == CodingBenchmarkTrialStatus.RUNNING_ORACLE)
                        .count();
                maxOracle.updateAndGet(current -> Math.max(current, running));
                if (trialStore.listByCampaign(CAMPAIGN_ID).stream().allMatch(trial -> trial.status().isTerminal())) {
                    return;
                }
                sleepQuietly(10L);
            }
        });
        observer.start();

        dispatcher.kick(CAMPAIGN_ID, SNAPSHOT_ID);
        observer.join(15_000L);

        assertTrue(maxOracle.get() <= CodingBenchmarkDispatcher.MAX_ORACLE_TRIALS,
                "observed oracle concurrency: " + maxOracle.get());
        assertEquals(7, trialStore.listByCampaign(CAMPAIGN_ID).stream().filter(t -> t.status().isTerminal()).count());
    }

    private CodingBenchmarkDispatcher newDispatcher(CodingBenchmarkCampaignCompletion completion) {
        return newDispatcher(completion, executionPort);
    }

    private CodingBenchmarkDispatcher newDispatcher(
            CodingBenchmarkCampaignCompletion completion,
            CodingBenchmarkExecutionPort port
    ) {
        return newDispatcher(completion, port, snapshotId -> List.of(
                runtimeForCase("case-01"),
                runtimeForCase("case-02")
        ));
    }

    private CodingBenchmarkDispatcher newDispatcher(
            CodingBenchmarkCampaignCompletion completion,
            CodingBenchmarkExecutionPort port,
            CodingBenchmarkRuntimeCatalogPort runtimeCatalog
    ) {
        StubRequestAdapter adapter = new StubRequestAdapter(tempDir.resolve("workspaces"));
        EvaluationTaskSchedulerPort scheduler = workerPool::execute;
        return new CodingBenchmarkDispatcher(
                runStore, trialStore, port, runtimeCatalog, adapter, scheduler, completion);
    }

    private CodingBenchmarkDispatcher newSynchronousDispatcher(
            CodingBenchmarkCampaignCompletion completion,
            CodingBenchmarkExecutionPort port,
            CodingBenchmarkRuntimeCatalogPort runtimeCatalog
    ) {
        StubRequestAdapter adapter = new StubRequestAdapter(tempDir.resolve("workspaces"));
        return new CodingBenchmarkDispatcher(
                runStore, trialStore, port, runtimeCatalog, adapter, Runnable::run, completion);
    }

    private CodingBenchmarkDispatcher newSynchronousDispatcher(CodingBenchmarkCampaignCompletion completion) {
        return newSynchronousDispatcher(completion, executionPort, snapshotId -> List.of(runtimeForCase("case-01")));
    }

    private CodingBenchmarkCaseRuntime runtimeForCase(String caseId) {
        try {
            Path caseRoot = Files.createDirectories(prepRoot.resolve(caseId));
            Path repo = Files.createDirectories(caseRoot.resolve("repo"));
            Path cache = Files.createDirectories(caseRoot.resolve("cache"));
            Path verifierCache = Files.createDirectories(caseRoot.resolve("verifier-cache"));
            return CodingBenchmarkCaseRuntime.of(
                    prepRoot,
                    caseId,
                    "registry.example/agent@sha256:" + "a".repeat(64),
                    "registry.example/oracle@sha256:" + "b".repeat(64),
                    repo,
                    cache,
                    repo,
                    verifierCache,
                    null,
                    null,
                    "tests/runtime",
                    List.of("/opt/rd/agent"),
                    List.of("/opt/rd/oracle"),
                    60_000L,
                    8_000L
            );
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void seedRun(boolean paused) {
        long now = System.currentTimeMillis();
        EvaluationRunConfig config = new EvaluationRunConfig(
                "probe", "snap.json", null, SNAPSHOT_ID, 0, "", "", 3600,
                null, 0, false, "", "", EvaluationMode.CODING_BENCHMARK, SNAPSHOT_ID);
        EvaluationRun run = EvaluationRun.created(CAMPAIGN_ID, config, 1, "", now)
                .withStatus(EvaluationRunStatus.RUNNING_TRIALS, "running trials", "", "", now);
        if (paused) {
            run = run.withDispatchPaused(true, now);
        }
        runStore.create(run);
    }

    private static CodingBenchmarkTrial queued(String trialId, String caseId, CodingBenchmarkArm arm) {
        return CodingBenchmarkTrial.queued(trialId, CAMPAIGN_ID, caseId, arm, 0, 1_000L);
    }

    private static CodingBenchmarkTrial active(String trialId, String caseId, CodingBenchmarkTrialStatus status) {
        return new CodingBenchmarkTrial(
                trialId, CAMPAIGN_ID, caseId, CodingBenchmarkArm.A, 0,
                status, CodingBenchmarkVerdict.PENDING, 1, 2L,
                "worker", System.currentTimeMillis() + 60_000L, "", "", 1_000L, 1_000L);
    }

    private static CodingBenchmarkTrial oracleActive(String trialId, String caseId) {
        return active(trialId, caseId, CodingBenchmarkTrialStatus.RUNNING_ORACLE);
    }

    private static CodingBenchmarkExecutionResult passResult(CodingBenchmarkExecutionRequest request) {
        return new CodingBenchmarkExecutionResult(
                0, 0, false, "",
                new CodingBenchmarkRuntimeAttestation(
                        request.trial().trialId(), "", "", "fake", "fake", false,
                        Instant.now().toEpochMilli()));
    }

    private void awaitStatusOnStore(String trialId, CodingBenchmarkTrialStatus status, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L;
        while (System.currentTimeMillis() < deadline) {
            CodingBenchmarkTrialStatus current = trialStore.find(trialId)
                    .map(CodingBenchmarkTrial::status)
                    .orElse(null);
            if (current == status) {
                return;
            }
            Thread.sleep(25L);
        }
        fail("trial " + trialId + " did not reach " + status);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Minimal request adapter that avoids Docker workspace preparation in unit tests. */
    static final class StubRequestAdapter extends CodingBenchmarkSnapshotToRequestAdapter {
        private final Path workspaceRoot;

        StubRequestAdapter(Path workspaceRoot) {
            super(workspaceRoot, new EvaluationProperties(), () -> "relay-token", new ObjectMapper());
            this.workspaceRoot = workspaceRoot;
        }

        @Override
        public TrialWorkspace materialiseTrialWorkspace(
                CodingBenchmarkTrial trial,
                CodingBenchmarkCaseRuntime runtime
        ) throws IOException {
            Path root = workspaceRoot.resolve(trial.trialId());
            Path repo = Files.createDirectories(root.resolve("repo"));
            Path verifier = Files.createDirectories(root.resolve("verifier"));
            Path cache = Files.createDirectories(root.resolve("cache"));
            Path verifierCache = Files.createDirectories(root.resolve("verifier-cache"));
            Path output = Files.createDirectories(root.resolve("output"));
            return new TrialWorkspace(repo, verifier, cache, verifierCache, output, root);
        }

        @Override
        public CodingBenchmarkExecutionRequest adapt(
                CodingBenchmarkTrial trial,
                TrialWorkspace workspace,
                CodingBenchmarkCaseRuntime runtime
        ) {
            try {
                Path bundle = workspace.trialRoot().resolve("protected-tests.tar.gz");
                if (Files.notExists(bundle)) {
                    Files.createFile(bundle);
                }
                Path verifierRepo = Files.createDirectories(workspace.trialRoot().resolve("verifier-repo"));
                Path verifierCache = Files.createDirectories(workspace.trialRoot().resolve("verifier-cache"));
                Path agentOutput = Files.createDirectories(workspace.outputDir().resolve("agent"));
                Path oracleOutput = Files.createDirectories(workspace.outputDir().resolve("oracle"));
                return new CodingBenchmarkExecutionRequest(
                        trial,
                        runtime.agentImage(),
                        runtime.oracleImage(),
                        workspace.repoDir(),
                        workspace.cacheDir(),
                        agentOutput,
                        verifierRepo,
                        verifierCache,
                        agentOutput.resolve("candidate.patch"),
                        bundle,
                        null,
                        runtime.protectedTestTarget(),
                        oracleOutput,
                        runtime.agentCommand(),
                        runtime.oracleCommand(),
                        "relay-token",
                        runtime.agentTimeoutMillis(),
                        runtime.oracleTimeoutMillis()
                );
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    /** In-memory trial store with claim/transition recording for dispatcher tests. */
    static final class RecordingTrialStore implements CodingBenchmarkTrialStore {
        private final Map<String, CodingBenchmarkTrial> trials = new ConcurrentHashMap<>();
        private final AtomicInteger claimCalls = new AtomicInteger();
        private final List<Long> terminalExpectedVersions = new ArrayList<>();

        int claimCallCount() {
            return claimCalls.get();
        }

        List<Long> terminalExpectedVersions() {
            return List.copyOf(terminalExpectedVersions);
        }

        @Override
        public void createAll(String campaignId, List<CodingBenchmarkTrial> values) {
            for (CodingBenchmarkTrial trial : values) {
                trials.put(trial.trialId(), trial);
            }
        }

        @Override
        public Optional<CodingBenchmarkTrial> find(String trialId) {
            return Optional.ofNullable(trials.get(trialId));
        }

        @Override
        public List<CodingBenchmarkTrial> listByCampaign(String campaignId) {
            return trials.values().stream()
                    .filter(trial -> trial.campaignId().equals(campaignId))
                    .sorted(Comparator.comparing(CodingBenchmarkTrial::createdAtEpochMillis)
                            .thenComparing(CodingBenchmarkTrial::trialId))
                    .toList();
        }

        @Override
        public Optional<CodingBenchmarkTrial> claimNext(
                String campaignId,
                String leaseOwner,
                long now,
                long leaseMillis
        ) {
            claimCalls.incrementAndGet();
            Set<String> activeCases = listByCampaign(campaignId).stream()
                    .filter(trial -> trial.status().isActive())
                    .map(CodingBenchmarkTrial::caseId)
                    .collect(java.util.stream.Collectors.toSet());
            for (CodingBenchmarkTrial trial : listByCampaign(campaignId)) {
                if (trial.status() == CodingBenchmarkTrialStatus.QUEUED && !activeCases.contains(trial.caseId())) {
                    CodingBenchmarkTrial claimed = new CodingBenchmarkTrial(
                            trial.trialId(), trial.campaignId(), trial.caseId(), trial.arm(), trial.replicateNo(),
                            CodingBenchmarkTrialStatus.PREPARING, trial.verdict(), trial.attemptNo(), trial.version() + 1L,
                            leaseOwner, now + leaseMillis, "", "", trial.createdAtEpochMillis(), now);
                    trials.put(trial.trialId(), claimed);
                    return Optional.of(claimed);
                }
            }
            return Optional.empty();
        }

        @Override
        public CodingBenchmarkTrial transition(
                String trialId,
                CodingBenchmarkTrialStatus expected,
                long expectedVersion,
                CodingBenchmarkTrialStatus target,
                CodingBenchmarkVerdict verdict,
                String errorCategory,
                String errorMessage,
                long now
        ) {
            CodingBenchmarkTrial current = trials.get(trialId);
            if (current == null) {
                throw new IllegalStateException("trial not found: " + trialId);
            }
            if (current.status() != expected || current.version() != expectedVersion) {
                throw new IllegalStateException("trial CAS conflict for " + trialId);
            }
            if (target.isTerminal()) {
                terminalExpectedVersions.add(expectedVersion);
            }
            CodingBenchmarkTrial updated = new CodingBenchmarkTrial(
                    current.trialId(), current.campaignId(), current.caseId(), current.arm(), current.replicateNo(),
                    target, verdict, current.attemptNo(), expectedVersion + 1L,
                    target.isTerminal() ? "" : current.leaseOwner(),
                    target.isTerminal() ? 0L : current.leaseExpiresAtEpochMillis(),
                    errorCategory, errorMessage, current.createdAtEpochMillis(), now);
            trials.put(trialId, updated);
            return updated;
        }

        @Override
        public List<com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialEvent> listEvents(String trialId) {
            return List.of();
        }
    }
}
