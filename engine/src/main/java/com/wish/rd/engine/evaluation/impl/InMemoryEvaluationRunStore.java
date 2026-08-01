package com.wish.rd.engine.evaluation.impl;

import com.wish.rd.engine.evaluation.EvaluationRunStore;
import com.wish.rd.engine.evaluation.EvaluationTransitionPolicy;
import com.wish.rd.engine.evaluation.model.EvaluationArtifact;
import com.wish.rd.engine.evaluation.model.EvaluationExecutionResult;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunEvent;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Non-production Store used by unit tests and explicit memory-mode startup. */
public final class InMemoryEvaluationRunStore implements EvaluationRunStore {
    private final ConcurrentHashMap<String, EvaluationRun> runs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<EvaluationRunEvent>> events = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<EvaluationArtifact>> artifacts = new ConcurrentHashMap<>();
    private final EvaluationTransitionPolicy policy = new EvaluationTransitionPolicy();
    private final AtomicLong eventIds = new AtomicLong();

    @Override
    public EvaluationRun create(EvaluationRun run) {
        if (runs.putIfAbsent(run.runId(), run) != null) {
            throw new IllegalStateException("evaluation run already exists: " + run.runId());
        }
        events.computeIfAbsent(run.runId(), ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(new EvaluationRunEvent(String.valueOf(eventIds.incrementAndGet()), run.runId(), null,
                        EvaluationRunStatus.CREATED, "created", "", "", run.createdAtEpochMillis()));
        return run;
    }

    @Override
    public Optional<EvaluationRun> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public List<EvaluationRun> list() {
        return runs.values().stream()
                .sorted(Comparator.comparingLong(EvaluationRun::createdAtEpochMillis).reversed()
                        .thenComparing(EvaluationRun::runId, Comparator.reverseOrder()))
                .toList();
    }

    @Override
    public EvaluationRun transition(String runId, EvaluationRunStatus expected, EvaluationRunStatus target,
                                    String message, String errorCategory, String errorMessage, long now) {
        EvaluationRun[] updated = new EvaluationRun[1];
        runs.compute(runId, (ignored, current) -> {
            if (current == null) {
                throw new NoSuchElementException("evaluation run not found: " + runId);
            }
            if (current.status() != expected) {
                throw new IllegalStateException("evaluation status conflict: expected " + expected + " but was " + current.status());
            }
            policy.requireTransition(current.config().mode(), expected, target);
            updated[0] = current.withStatus(target, message, errorCategory, errorMessage, now);
            return updated[0];
        });
        events.computeIfAbsent(runId, ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(new EvaluationRunEvent(String.valueOf(eventIds.incrementAndGet()), runId, expected, target,
                        safe(message), safe(errorCategory), safe(errorMessage), now));
        return updated[0];
    }

    @Override
    public EvaluationRun complete(String runId, EvaluationRunStatus expected, EvaluationExecutionResult result, long now) {
        EvaluationRun[] updated = new EvaluationRun[1];
        runs.compute(runId, (ignored, current) -> {
            if (current == null) {
                throw new NoSuchElementException("evaluation run not found: " + runId);
            }
            if (current.status() != expected) {
                throw new IllegalStateException("evaluation status conflict: expected " + expected + " but was " + current.status());
            }
            policy.requireTransition(current.config().mode(), expected, EvaluationRunStatus.SUCCEEDED);
            updated[0] = current.withResult(result, now);
            return updated[0];
        });
        events.computeIfAbsent(runId, ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(new EvaluationRunEvent(String.valueOf(eventIds.incrementAndGet()), runId, expected,
                        EvaluationRunStatus.SUCCEEDED, "evaluation completed", "", "", now));
        return updated[0];
    }

    @Override
    public EvaluationRun updateSampleProgress(
            String runId,
            int sampleCount,
            int passedSampleCount,
            int failedSampleCount,
            long now
    ) {
        EvaluationRun[] updated = new EvaluationRun[1];
        runs.compute(runId, (ignored, current) -> {
            if (current == null) {
                throw new NoSuchElementException("evaluation run not found: " + runId);
            }
            updated[0] = current.withTrialSampleProgress(sampleCount, passedSampleCount, failedSampleCount, now);
            return updated[0];
        });
        return updated[0];
    }

    @Override
    public EvaluationRun setDispatchPaused(String runId, boolean paused, long expectedVersion, long now) {
        EvaluationRun[] updated = new EvaluationRun[1];
        runs.compute(runId, (ignored, current) -> {
            if (current == null) {
                throw new NoSuchElementException("evaluation run not found: " + runId);
            }
            if (current.version() != expectedVersion) {
                throw new IllegalStateException("evaluation version conflict: " + runId);
            }
            if (current.status().isTerminal()) {
                throw new IllegalStateException("terminal evaluation run is immutable: " + runId);
            }
            updated[0] = current.withDispatchPaused(paused, now);
            return updated[0];
        });
        return updated[0];
    }

    @Override
    public void appendArtifacts(String runId, List<EvaluationArtifact> values) {
        if (!runs.containsKey(runId)) {
            throw new NoSuchElementException("evaluation run not found: " + runId);
        }
        artifacts.computeIfAbsent(runId, ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .addAll(values == null ? List.of() : values);
    }

    @Override
    public List<EvaluationRunEvent> listEvents(String runId) {
        return List.copyOf(events.getOrDefault(runId, List.of()));
    }

    @Override
    public List<EvaluationArtifact> listArtifacts(String runId) {
        return List.copyOf(artifacts.getOrDefault(runId, List.of()));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
