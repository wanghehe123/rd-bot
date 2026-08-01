package com.wish.rd.bootstrap.evaluation.impl;

import com.wish.rd.engine.evaluation.impl.InMemoryEvaluationRunStore;
import com.wish.rd.engine.evaluation.model.EvaluationMode;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingBenchmarkCampaignRecoveryTest {

    private static final String SNAPSHOT_ID = "recovery-snap";

    private InMemoryEvaluationRunStore runStore;
    private CodingBenchmarkCampaignServiceTest.KickRecordingDispatcher dispatcher;
    private CodingBenchmarkCampaignRecovery recovery;

    @BeforeEach
    void setUp() {
        runStore = new InMemoryEvaluationRunStore();
        dispatcher = new CodingBenchmarkCampaignServiceTest.KickRecordingDispatcher();
        recovery = new CodingBenchmarkCampaignRecovery(runStore, dispatcher);
    }

    @Test
    void recoverKicksUnpausedRunningTrialsAndPreparingRuns() {
        EvaluationRun running = seed("run-running", EvaluationRunStatus.RUNNING_TRIALS, false);
        EvaluationRun preparing = seed("run-preparing", EvaluationRunStatus.PREPARING, false);
        seed("run-paused", EvaluationRunStatus.RUNNING_TRIALS, true);
        seed("run-terminal", EvaluationRunStatus.SUCCEEDED, false);
        seed("run-legacy", EvaluationRunStatus.RUNNING_TRIALS, false, EvaluationMode.LEGACY_QUALITY);

        recovery.recover();

        assertEquals(2, dispatcher.kicks.size());
        assertTrue(dispatcher.kicks.stream().anyMatch(k -> k.campaignId().equals(running.runId())));
        assertTrue(dispatcher.kicks.stream().anyMatch(k -> k.campaignId().equals(preparing.runId())));
        assertTrue(dispatcher.kicks.stream().allMatch(k -> SNAPSHOT_ID.equals(k.snapshotId())));
    }

    private EvaluationRun seed(String runId, EvaluationRunStatus status, boolean paused) {
        return seed(runId, status, paused, EvaluationMode.CODING_BENCHMARK);
    }

    private EvaluationRun seed(
            String runId,
            EvaluationRunStatus status,
            boolean paused,
            EvaluationMode mode
    ) {
        long now = System.currentTimeMillis();
        EvaluationRunConfig config = new EvaluationRunConfig(
                "campaign", SNAPSHOT_ID + ".json", null, SNAPSHOT_ID, 0, "", "", 3600,
                null, 0, false, "", "", mode, SNAPSHOT_ID);
        EvaluationRun run = EvaluationRun.created(runId, config, 1, "", now)
                .withStatus(status, status.name().toLowerCase(), "", "", now);
        if (paused) {
            run = run.withDispatchPaused(true, now);
        }
        runStore.create(run);
        return run;
    }
}
