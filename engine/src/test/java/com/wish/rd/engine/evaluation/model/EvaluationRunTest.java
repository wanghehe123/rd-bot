package com.wish.rd.engine.evaluation.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationRunTest {

    @Test
    void shouldStartUnpausedAndToggleDispatchPausedWithoutTouchingStatus() {
        EvaluationRun created = EvaluationRun.created("501", config(), 1, "", 1_000L);
        assertFalse(created.dispatchPaused());

        EvaluationRun paused = created.withDispatchPaused(true, 2_000L);

        assertTrue(paused.dispatchPaused());
        assertEquals(created.status(), paused.status());
        assertEquals(created.progressPercent(), paused.progressPercent());
        assertEquals(created.version() + 1L, paused.version());
        assertEquals(2_000L, paused.updatedAtEpochMillis());
        assertFalse(paused.withDispatchPaused(false, 3_000L).dispatchPaused());
    }

    @Test
    void shouldPreserveDispatchPausedAcrossStatusTransitions() {
        EvaluationRun paused = EvaluationRun.created("502", config(), 1, "", 1_000L)
                .withDispatchPaused(true, 2_000L);

        EvaluationRun queued = paused.withStatus(EvaluationRunStatus.QUEUED, "queued", "", "", 3_000L);

        assertTrue(queued.dispatchPaused());
    }

    @Test
    void shouldStampStartedAtWhenCodingBenchmarkEntersRunningTrials() {
        EvaluationRun queued = EvaluationRun.created("503", config(), 1, "", 1_000L)
                .withStatus(EvaluationRunStatus.QUEUED, "queued", "", "", 2_000L);
        assertEquals(0L, queued.startedAtEpochMillis());

        EvaluationRun running = queued
                .withStatus(EvaluationRunStatus.PREPARING, "preparing", "", "", 3_000L)
                .withStatus(EvaluationRunStatus.RUNNING_TRIALS, "running", "", "", 4_000L);

        assertEquals(4_000L, running.startedAtEpochMillis());
        // 已经起算的 startedAt 不得被后续阶段覆盖，否则耗时统计会被重置。
        assertEquals(4_000L, running
                .withStatus(EvaluationRunStatus.SCORING, "scoring", "", "", 5_000L)
                .startedAtEpochMillis());
    }

    private static EvaluationRunConfig config() {
        return new EvaluationRunConfig(
                "coding-benchmark-probe", "coding-benchmark.jsonl", EvaluationSource.FIXTURE, "local", 0,
                "", "", 30, EvaluationJudgeProvider.NONE, 0, false, "", "",
                EvaluationMode.CODING_BENCHMARK, "snapshot-1");
    }
}
