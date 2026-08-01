package com.wish.rd.bootstrap.evaluation.impl;

import com.wish.rd.engine.evaluation.CodingBenchmarkCaseRuntime;
import com.wish.rd.engine.evaluation.CodingBenchmarkExecutionHooks;
import com.wish.rd.engine.evaluation.CodingBenchmarkExecutionPort;
import com.wish.rd.engine.evaluation.CodingBenchmarkRuntimeCatalogPort;
import com.wish.rd.engine.evaluation.CodingBenchmarkTrialStore;
import com.wish.rd.engine.evaluation.EvaluationRunStore;
import com.wish.rd.engine.evaluation.EvaluationTaskSchedulerPort;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionResult;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkVerdict;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Convergent dispatch loop for one coding benchmark campaign (§11).
 *
 * <p>At most one loop runs per {@code campaignId}. The loop claims queued trials, executes them
 * through {@link CodingBenchmarkExecutionPort}, and enforces active ≤ 6, per-case ≤ 1, and
 * oracle ≤ 3 via {@link CodingBenchmarkExecutionHooks#beforeOracle()}.
 */
@Component
public class CodingBenchmarkDispatcher {

    /** Cap concurrent claimed/running trials to limit workspace materialisation memory pressure. */
    static final int MAX_ACTIVE_TRIALS = 4;
    static final int MAX_ORACLE_TRIALS = 3;
    static final long LEASE_MILLIS = 90 * 60 * 1000L;
    static final long LOOP_WAIT_MILLIS = 25L;
    private static final String WORKER_ID = "coding-benchmark-dispatcher";

    private static final Logger log = LoggerFactory.getLogger(CodingBenchmarkDispatcher.class);

    private final EvaluationRunStore runStore;
    private final CodingBenchmarkTrialStore trialStore;
    private final CodingBenchmarkExecutionPort executionPort;
    private final CodingBenchmarkRuntimeCatalogPort runtimeCatalog;
    private final CodingBenchmarkSnapshotToRequestAdapter requestAdapter;
    private final EvaluationTaskSchedulerPort scheduler;
    private final CodingBenchmarkCampaignCompletion completion;
    private final ConcurrentHashMap<String, AtomicBoolean> loopRunning = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> oracleGates = new ConcurrentHashMap<>();

    @Autowired
    public CodingBenchmarkDispatcher(
            EvaluationRunStore runStore,
            CodingBenchmarkTrialStore trialStore,
            CodingBenchmarkExecutionPort executionPort,
            CodingBenchmarkRuntimeCatalogPort runtimeCatalog,
            CodingBenchmarkSnapshotToRequestAdapter requestAdapter,
            EvaluationTaskSchedulerPort scheduler,
            @Lazy CodingBenchmarkCampaignCompletion completion
    ) {
        this.runStore = Objects.requireNonNull(runStore, "runStore must not be null");
        this.trialStore = Objects.requireNonNull(trialStore, "trialStore must not be null");
        this.executionPort = Objects.requireNonNull(executionPort, "executionPort must not be null");
        this.runtimeCatalog = Objects.requireNonNull(runtimeCatalog, "runtimeCatalog must not be null");
        this.requestAdapter = Objects.requireNonNull(requestAdapter, "requestAdapter must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.completion = Objects.requireNonNull(completion, "completion must not be null");
    }

    /**
     * Starts the dispatch loop for one campaign when none is already running.
     *
     * @param campaignId parent evaluation run ID (also the trial campaign key)
     * @param snapshotId frozen snapshot used to resolve case runtimes
     */
    public void kick(String campaignId, String snapshotId) {
        Objects.requireNonNull(campaignId, "campaignId must not be null");
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        AtomicBoolean running = loopRunning.computeIfAbsent(campaignId, ignored -> new AtomicBoolean(false));
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler.submit(() -> {
            try {
                dispatchLoop(campaignId, snapshotId);
            } finally {
                running.set(false);
            }
        });
    }

    private void dispatchLoop(String campaignId, String snapshotId) {
        Map<String, CodingBenchmarkCaseRuntime> runtimesByCase = runtimeCatalog.runtimeForSnapshot(snapshotId).stream()
                .collect(Collectors.toMap(CodingBenchmarkCaseRuntime::caseId, Function.identity(), (left, right) -> left));

        while (true) {
            EvaluationRun run = runStore.find(campaignId).orElse(null);
            if (run == null || shouldStopDispatch(run.status())) {
                return;
            }
            if (run.dispatchPaused()) {
                return;
            }

            List<CodingBenchmarkTrial> trials = trialStore.listByCampaign(campaignId);
            long activeCount = countActive(trials);
            if (activeCount >= MAX_ACTIVE_TRIALS) {
                sleepQuietly(LOOP_WAIT_MILLIS);
                continue;
            }

            if (!hasQueued(trials)) {
                if (activeCount == 0) {
                    refreshRunProgress(campaignId, trials);
                    completion.onTrialsDrained(campaignId);
                    return;
                }
                sleepQuietly(LOOP_WAIT_MILLIS);
                continue;
            }

            long now = System.currentTimeMillis();
            Optional<CodingBenchmarkTrial> claimed = trialStore.claimNext(
                    campaignId, WORKER_ID, now, LEASE_MILLIS);
            if (claimed.isEmpty()) {
                // Either all remaining queued rows share an active case, or another worker won the race.
                sleepQuietly(LOOP_WAIT_MILLIS);
                continue;
            }

            CodingBenchmarkTrial trial = claimed.orElseThrow();
            scheduler.submit(() -> executeTrial(trial, snapshotId, runtimesByCase));
        }
    }

    private void executeTrial(
            CodingBenchmarkTrial claimed,
            String snapshotId,
            Map<String, CodingBenchmarkCaseRuntime> runtimesByCase
    ) {
        String trialId = claimed.trialId();
        CodingBenchmarkTrial current = trialStore.transition(
                trialId,
                CodingBenchmarkTrialStatus.PREPARING,
                claimed.version(),
                CodingBenchmarkTrialStatus.RUNNING_AGENTS,
                CodingBenchmarkVerdict.PENDING, "", "",
                System.currentTimeMillis()
        );

        CodingBenchmarkCaseRuntime runtime = runtimesByCase.get(current.caseId());
        if (runtime == null) {
            log.error("[CODING_BENCHMARK] no runtime descriptor for case {} in snapshot {}",
                    current.caseId(), snapshotId);
            terminalTransition(current, CodingBenchmarkVerdict.INFRA_ERROR, "missing runtime");
            return;
        }

        CodingBenchmarkSnapshotToRequestAdapter.TrialWorkspace workspace;
        try {
            workspace = requestAdapter.materialiseTrialWorkspace(current, runtime);
        } catch (IOException exception) {
            log.error("[CODING_BENCHMARK] workspace materialisation failed for trial {}: {}",
                    trialId, exception.getMessage());
            terminalTransition(current, CodingBenchmarkVerdict.INFRA_ERROR, exception.getMessage());
            return;
        }

        CodingBenchmarkExecutionRequest request = requestAdapter.adapt(current, workspace, runtime);
        CodingBenchmarkExecutionResult result = executionPort.execute(request, beforeOracleHook(trialId, claimed.campaignId()));

        CodingBenchmarkTrial afterAgents = trialStore.find(trialId).orElse(current);
        CodingBenchmarkVerdict verdict;
        String errorMessage;
        if (result.infrastructureFailure()) {
            verdict = CodingBenchmarkVerdict.INFRA_ERROR;
            errorMessage = result.errorMessage();
        } else if (result.oracleExitCode() == 0) {
            verdict = CodingBenchmarkVerdict.PASS;
            errorMessage = "";
        } else if (result.oracleExitCode() < 0) {
            String message = result.errorMessage() == null ? "" : result.errorMessage();
            if (message.contains("did not publish a candidate patch")) {
                verdict = CodingBenchmarkVerdict.NO_PATCH;
            } else {
                verdict = CodingBenchmarkVerdict.PROTOCOL_ERROR;
            }
            errorMessage = message;
        } else {
            verdict = CodingBenchmarkVerdict.TEST_FAIL;
            errorMessage = result.errorMessage();
        }
        terminalTransition(afterAgents, verdict, errorMessage);

        refreshRunProgress(claimed.campaignId(), trialStore.listByCampaign(claimed.campaignId()));
        log.info("[CODING_BENCHMARK] trial {} verdict={} agentExit={} oracleExit={}",
                trialId, verdict, result.agentExitCode(), result.oracleExitCode());
    }

    private CodingBenchmarkExecutionHooks beforeOracleHook(String trialId, String campaignId) {
        Object gate = oracleGates.computeIfAbsent(campaignId, ignored -> new Object());
        return () -> {
            synchronized (gate) {
                while (true) {
                    EvaluationRun run = runStore.find(campaignId).orElse(null);
                    if (run != null && shouldStopDispatch(run.status())) {
                        return;
                    }
                    if (countRunningOracle(campaignId) < MAX_ORACLE_TRIALS) {
                        CodingBenchmarkTrial current = trialStore.find(trialId).orElseThrow();
                        if (current.status() == CodingBenchmarkTrialStatus.RUNNING_AGENTS) {
                            trialStore.transition(
                                    trialId,
                                    CodingBenchmarkTrialStatus.RUNNING_AGENTS,
                                    current.version(),
                                    CodingBenchmarkTrialStatus.RUNNING_ORACLE,
                                    CodingBenchmarkVerdict.PENDING, "", "",
                                    System.currentTimeMillis()
                            );
                        }
                        return;
                    }
                    try {
                        gate.wait(LOOP_WAIT_MILLIS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        };
    }

    private void terminalTransition(CodingBenchmarkTrial current, CodingBenchmarkVerdict verdict, String errorMessage) {
        CodingBenchmarkTrialStatus terminal = verdict == CodingBenchmarkVerdict.PASS
                ? CodingBenchmarkTrialStatus.SUCCEEDED
                : CodingBenchmarkTrialStatus.FAILED;
        CodingBenchmarkTrial latest = trialStore.find(current.trialId()).orElse(current);
        CodingBenchmarkTrialStatus expected = latest.status();
        if (expected != CodingBenchmarkTrialStatus.RUNNING_ORACLE
                && expected != CodingBenchmarkTrialStatus.RUNNING_AGENTS) {
            expected = CodingBenchmarkTrialStatus.RUNNING_ORACLE;
        }
        trialStore.transition(
                latest.trialId(),
                expected,
                latest.version(),
                terminal,
                verdict,
                verdict == CodingBenchmarkVerdict.INFRA_ERROR ? "INFRA" : "",
                errorMessage,
                System.currentTimeMillis()
        );
        if (expected == CodingBenchmarkTrialStatus.RUNNING_ORACLE) {
            Object gate = oracleGates.get(latest.campaignId());
            if (gate != null) {
                synchronized (gate) {
                    gate.notifyAll();
                }
            }
        }
    }

    private long countRunningOracle(String campaignId) {
        return trialStore.listByCampaign(campaignId).stream()
                .filter(trial -> trial.status() == CodingBenchmarkTrialStatus.RUNNING_ORACLE)
                .count();
    }

    private void refreshRunProgress(String campaignId, List<CodingBenchmarkTrial> trials) {
        int passed = 0;
        int failed = 0;
        for (CodingBenchmarkTrial trial : trials) {
            if (trial.status() == CodingBenchmarkTrialStatus.SUCCEEDED) {
                passed++;
            } else if (trial.status().isTerminal()) {
                failed++;
            }
        }
        int sampleCount = passed + failed;
        if (sampleCount == 0) {
            return;
        }
        runStore.updateSampleProgress(campaignId, sampleCount, passed, failed, System.currentTimeMillis());
    }

    private static boolean shouldStopDispatch(EvaluationRunStatus status) {
        return status.isTerminal() || status == EvaluationRunStatus.CANCEL_REQUESTED;
    }

    private static long countActive(List<CodingBenchmarkTrial> trials) {
        return trials.stream().filter(trial -> trial.status().isActive()).count();
    }

    private static Optional<CodingBenchmarkTrial> firstQueuedTrial(List<CodingBenchmarkTrial> trials) {
        return trials.stream()
                .filter(trial -> trial.status() == CodingBenchmarkTrialStatus.QUEUED
                        || trial.status() == CodingBenchmarkTrialStatus.RETRY_PENDING)
                .findFirst();
    }

    private static boolean hasQueued(List<CodingBenchmarkTrial> trials) {
        return firstQueuedTrial(trials).isPresent();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
