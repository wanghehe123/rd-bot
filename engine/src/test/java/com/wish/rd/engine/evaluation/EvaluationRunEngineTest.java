package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.impl.InMemoryEvaluationRunStore;
import com.wish.rd.engine.evaluation.model.EvaluationArtifact;
import com.wish.rd.engine.evaluation.model.EvaluationExecutionResult;
import com.wish.rd.engine.evaluation.model.EvaluationJudgeProvider;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import com.wish.rd.engine.evaluation.model.EvaluationSource;
import com.wish.rd.engine.evaluation.model.EvaluationRunEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationRunEngineTest {

    @Test
    void shouldExecuteRecordScoreReportAndPersistArtifacts() {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        CapturingScheduler scheduler = new CapturingScheduler();
        FakeExecutor executor = FakeExecutor.success();
        AtomicInteger ids = new AtomicInteger(100);
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, executor, scheduler, () -> String.valueOf(ids.incrementAndGet()), () -> 1_000L);

        EvaluationRun queued = engine.start(config("fixture-smoke", ""));

        assertEquals(EvaluationRunStatus.QUEUED, queued.status());
        scheduler.runNext();

        EvaluationRun completed = engine.find(queued.runId());
        assertEquals(EvaluationRunStatus.SUCCEEDED, completed.status());
        assertEquals(6, completed.sampleCount());
        assertEquals(5, completed.passedSampleCount());
        assertEquals(1, completed.failedSampleCount());
        assertTrue(completed.metricsJson().contains("evidence_hit@5"));
        assertEquals(List.of(
                        EvaluationRunStatus.CREATED,
                        EvaluationRunStatus.QUEUED,
                        EvaluationRunStatus.RECORDING,
                        EvaluationRunStatus.SCORING,
                        EvaluationRunStatus.REPORTING,
                        EvaluationRunStatus.SUCCEEDED),
                store.listEvents(queued.runId()).stream().map(event -> event.toStatus()).toList());
        assertEquals(2, store.listArtifacts(queued.runId()).size());
    }

    @Test
    void shouldRunDiffWhenBaselineIsConfigured() {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        CapturingScheduler scheduler = new CapturingScheduler();
        FakeExecutor executor = FakeExecutor.success();
        AtomicInteger ids = new AtomicInteger(200);
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, executor, scheduler, () -> String.valueOf(ids.incrementAndGet()), () -> 2_000L);

        EvaluationRun baseline = engine.start(config("baseline", ""));
        scheduler.runNext();
        EvaluationRun candidate = engine.start(config("candidate", baseline.runId()));
        scheduler.runNext();

        assertEquals(EvaluationRunStatus.SUCCEEDED, engine.find(candidate.runId()).status());
        assertTrue(store.listEvents(candidate.runId()).stream()
                .anyMatch(event -> event.toStatus() == EvaluationRunStatus.DIFFING));
        assertEquals(baseline.runId(), executor.lastBaselineRunId);
    }

    @Test
    void shouldCancelActiveRunAndKeepTerminalRunImmutable() {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        CapturingScheduler scheduler = new CapturingScheduler();
        FakeExecutor executor = FakeExecutor.cancelled();
        AtomicInteger ids = new AtomicInteger(300);
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, executor, scheduler, () -> String.valueOf(ids.incrementAndGet()), () -> 3_000L);

        EvaluationRun queued = engine.start(config("cancel-me", ""));
        EvaluationRun cancelled = engine.cancel(queued.runId());

        assertEquals(EvaluationRunStatus.CANCELLED, cancelled.status());
        assertTrue(executor.cancelledRunIds.contains(queued.runId()));
        assertThrows(IllegalStateException.class, () -> engine.cancel(queued.runId()));
    }

    @Test
    void shouldReturnCancelledSnapshotWhenWorkerWinsCancellationRace() {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        AtomicInteger ids = new AtomicInteger(350);
        EvaluationExecutionPort racingExecutor = new EvaluationExecutionPort() {
            @Override
            public EvaluationExecutionResult execute(EvaluationRun run, EvaluationProgressListener listener) {
                throw new CancellationException("cancelled");
            }

            @Override
            public void cancel(String runId) {
                store.transition(runId, EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.CANCELLED,
                        "worker observed cancellation", "", "", 3_500L);
            }
        };
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, racingExecutor, task -> { }, () -> String.valueOf(ids.incrementAndGet()), () -> 3_500L);
        EvaluationRun queued = engine.start(config("cancel-race", ""));

        EvaluationRun cancelled = engine.cancel(queued.runId());

        assertEquals(EvaluationRunStatus.CANCELLED, cancelled.status());
    }

    @Test
    void shouldIgnoreCancellationConflictWhenControllerAlreadyCompletedIt() {
        RacingCancellationStore store = new RacingCancellationStore();
        EvaluationExecutionPort cancelledExecutor = new EvaluationExecutionPort() {
            public EvaluationExecutionResult execute(EvaluationRun run, EvaluationProgressListener listener) {
                throw new CancellationException("cancelled by controller");
            }
            public void cancel(String runId) { }
        };
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, cancelledExecutor, Runnable::run, () -> "381", () -> 3_800L);

        EvaluationRun started = engine.start(config("worker-cancel-race", ""));

        assertEquals(EvaluationRunStatus.CANCELLED, engine.find(started.runId()).status());
    }

    @Test
    void shouldNotFailWorkerWhenCancellationCompletesDuringFailureHandling() {
        CancellationDuringFailureStore store = new CancellationDuringFailureStore();
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, FakeExecutor.success(), Runnable::run, () -> "391", () -> 3_900L);

        EvaluationRun started = assertDoesNotThrow(() -> engine.start(config("cancel-before-recording", "")));

        assertEquals(EvaluationRunStatus.CANCELLED, engine.find(started.runId()).status());
    }

    @Test
    void shouldRetryTerminalRunWithNewAttemptAndParentLink() {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        CapturingScheduler scheduler = new CapturingScheduler();
        FakeExecutor executor = FakeExecutor.failure();
        AtomicInteger ids = new AtomicInteger(400);
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, executor, scheduler, () -> String.valueOf(ids.incrementAndGet()), () -> 4_000L);

        EvaluationRun first = engine.start(config("retry-me", ""));
        scheduler.runNext();
        assertEquals(EvaluationRunStatus.FAILED, engine.find(first.runId()).status());

        EvaluationRun retry = engine.retry(first.runId());

        assertNotEquals(first.runId(), retry.runId());
        assertEquals(first.runId(), retry.parentRunId());
        assertEquals(2, retry.attemptNo());
        assertEquals(EvaluationRunStatus.QUEUED, retry.status());
    }

    @Test
    void shouldRejectUnsafeOrIncompleteConfiguration() {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, FakeExecutor.success(), Runnable::run, () -> "501", () -> 5_000L);

        EvaluationRunConfig invalid = new EvaluationRunConfig(
                "bad", "../secret.jsonl", EvaluationSource.RAG_HTTP, "local", 0,
                "https://example.com", "../rag.log", 10, EvaluationJudgeProvider.NONE, 0,
                false, "");

        assertThrows(IllegalArgumentException.class, () -> engine.start(invalid));
    }

    @Test
    void shouldValidateTaskRunSourceAndKeepTaskIdAcrossRetry() {
        InMemoryEvaluationRunStore store = new InMemoryEvaluationRunStore();
        CapturingScheduler scheduler = new CapturingScheduler();
        AtomicInteger ids = new AtomicInteger(600);
        EvaluationRunEngine engine = new EvaluationRunEngine(
                store, FakeExecutor.success(), scheduler, () -> String.valueOf(ids.incrementAndGet()), () -> 6_000L);
        EvaluationRunConfig taskRun = new EvaluationRunConfig(
                "task execution", "task-run.generated.jsonl", EvaluationSource.TASK_RUN, "local", 0,
                "", "", 30, EvaluationJudgeProvider.NONE, 0, false, "", "7480495920010891264");

        EvaluationRun first = engine.start(taskRun);
        scheduler.runNext();
        EvaluationRun retry = engine.retry(first.runId());

        assertEquals("7480495920010891264", retry.config().taskId());
        assertEquals(2, retry.attemptNo());
        assertThrows(IllegalArgumentException.class, () -> engine.start(new EvaluationRunConfig(
                "missing task", "task-run.generated.jsonl", EvaluationSource.TASK_RUN, "local", 0,
                "", "", 30, EvaluationJudgeProvider.NONE, 0, false, "", "")));
        assertThrows(IllegalArgumentException.class, () -> engine.start(new EvaluationRunConfig(
                "bad task", "task-run.generated.jsonl", EvaluationSource.TASK_RUN, "local", 0,
                "", "", 30, EvaluationJudgeProvider.NONE, 0, false, "", "../task")));
    }

    private static EvaluationRunConfig config(String name, String baselineRunId) {
        return new EvaluationRunConfig(
                name,
                "rd_eval_smoke.jsonl",
                EvaluationSource.FIXTURE,
                "local-web",
                0,
                "http://127.0.0.1:18080",
                "logs/rag-retrieval.jsonl",
                30,
                EvaluationJudgeProvider.NONE,
                0,
                false,
                baselineRunId
        );
    }

    private static final class CapturingScheduler implements EvaluationTaskSchedulerPort {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void submit(Runnable task) {
            tasks.add(task);
        }

        void runNext() {
            tasks.removeFirst().run();
        }
    }

    private static final class FakeExecutor implements EvaluationExecutionPort {
        private final Mode mode;
        private final List<String> cancelledRunIds = new ArrayList<>();
        private String lastBaselineRunId = "";

        private FakeExecutor(Mode mode) {
            this.mode = mode;
        }

        static FakeExecutor success() {
            return new FakeExecutor(Mode.SUCCESS);
        }

        static FakeExecutor failure() {
            return new FakeExecutor(Mode.FAILURE);
        }

        static FakeExecutor cancelled() {
            return new FakeExecutor(Mode.CANCELLED);
        }

        @Override
        public EvaluationExecutionResult execute(EvaluationRun run, EvaluationProgressListener listener) {
            if (mode == Mode.FAILURE) {
                throw new IllegalStateException("python exited with code 2");
            }
            if (mode == Mode.CANCELLED) {
                throw new CancellationException("cancelled");
            }
            listener.phase(EvaluationRunStatus.SCORING, "score records");
            listener.phase(EvaluationRunStatus.REPORTING, "render reports");
            if (!run.config().baselineRunId().isBlank()) {
                lastBaselineRunId = run.config().baselineRunId();
                listener.phase(EvaluationRunStatus.DIFFING, "compare baseline");
            }
            return new EvaluationExecutionResult(
                    6,
                    5,
                    1,
                    false,
                    "{\"evidence_hit@5\":0.96}",
                    List.of(
                            new EvaluationArtifact("score", "SCORES", "reports/run/_scores.json", "scores", "hash1", 120L, 10L),
                            new EvaluationArtifact("report", "REPORT", "reports/run/report.md", "report", "hash2", 240L, 10L)
                    )
            );
        }

        @Override
        public void cancel(String runId) {
            cancelledRunIds.add(runId);
        }

        private enum Mode { SUCCESS, FAILURE, CANCELLED }
    }

    private static final class RacingCancellationStore implements EvaluationRunStore {
        private final InMemoryEvaluationRunStore delegate = new InMemoryEvaluationRunStore();
        private boolean raced;

        public EvaluationRun create(EvaluationRun run) { return delegate.create(run); }
        public java.util.Optional<EvaluationRun> find(String runId) { return delegate.find(runId); }
        public List<EvaluationRun> list() { return delegate.list(); }
        public EvaluationRun transition(String runId, EvaluationRunStatus expected, EvaluationRunStatus target,
                                        String message, String category, String error, long now) {
            if (!raced && expected == EvaluationRunStatus.CANCEL_REQUESTED && target == EvaluationRunStatus.CANCELLED) {
                raced = true;
                delegate.transition(runId, expected, target, "controller completed cancellation", "", "", now);
            }
            return delegate.transition(runId, expected, target, message, category, error, now);
        }
        public EvaluationRun complete(String runId, EvaluationRunStatus expected, EvaluationExecutionResult result, long now) {
            return delegate.complete(runId, expected, result, now);
        }
        public void appendArtifacts(String runId, List<EvaluationArtifact> artifacts) { delegate.appendArtifacts(runId, artifacts); }
        public List<EvaluationRunEvent> listEvents(String runId) { return delegate.listEvents(runId); }
        public List<EvaluationArtifact> listArtifacts(String runId) { return delegate.listArtifacts(runId); }
    }

    private static final class CancellationDuringFailureStore implements EvaluationRunStore {
        private final InMemoryEvaluationRunStore delegate = new InMemoryEvaluationRunStore();
        private boolean cancelBeforeRecording = true;
        private boolean completeAfterFind;

        public EvaluationRun create(EvaluationRun run) { return delegate.create(run); }
        public java.util.Optional<EvaluationRun> find(String runId) {
            java.util.Optional<EvaluationRun> found = delegate.find(runId);
            if (completeAfterFind && found.map(EvaluationRun::status).orElse(null) == EvaluationRunStatus.CANCEL_REQUESTED) {
                completeAfterFind = false;
                EvaluationRun snapshot = found.orElseThrow();
                delegate.transition(runId, EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.CANCELLED,
                        "controller completed cancellation", "", "", snapshot.updatedAtEpochMillis());
            }
            return found;
        }
        public List<EvaluationRun> list() { return delegate.list(); }
        public EvaluationRun transition(String runId, EvaluationRunStatus expected, EvaluationRunStatus target,
                                        String message, String category, String error, long now) {
            if (cancelBeforeRecording && expected == EvaluationRunStatus.QUEUED
                    && target == EvaluationRunStatus.RECORDING) {
                cancelBeforeRecording = false;
                delegate.transition(runId, EvaluationRunStatus.QUEUED, EvaluationRunStatus.CANCEL_REQUESTED,
                        "controller requested cancellation", "", "", now);
                completeAfterFind = true;
            }
            return delegate.transition(runId, expected, target, message, category, error, now);
        }
        public EvaluationRun complete(String runId, EvaluationRunStatus expected, EvaluationExecutionResult result, long now) {
            return delegate.complete(runId, expected, result, now);
        }
        public void appendArtifacts(String runId, List<EvaluationArtifact> artifacts) { delegate.appendArtifacts(runId, artifacts); }
        public List<EvaluationRunEvent> listEvents(String runId) { return delegate.listEvents(runId); }
        public List<EvaluationArtifact> listArtifacts(String runId) { return delegate.listArtifacts(runId); }
    }
}
