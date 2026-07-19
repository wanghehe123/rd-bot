package com.wish.rd.engine.requirement.review.impl;

import com.wish.rd.engine.requirement.review.AiReviewRunStore;
import com.wish.rd.engine.requirement.review.model.AiReviewArtifact;
import com.wish.rd.engine.requirement.review.model.AiReviewEvent;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.engine.requirement.review.model.AiReviewResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only in-memory AI review store with CAS-like transition checks. */
public final class InMemoryAiReviewRunStore implements AiReviewRunStore {

    private static final Map<AiReviewRunStatus, List<AiReviewRunStatus>> ALLOWED = Map.of(
            AiReviewRunStatus.CREATED, List.of(AiReviewRunStatus.PACKAGING, AiReviewRunStatus.CANCELLED),
            AiReviewRunStatus.PACKAGING, List.of(AiReviewRunStatus.REVIEWING,
                    AiReviewRunStatus.FAILED_RETRYABLE, AiReviewRunStatus.CANCELLED),
            AiReviewRunStatus.REVIEWING, List.of(AiReviewRunStatus.VALIDATING,
                    AiReviewRunStatus.FAILED_RETRYABLE, AiReviewRunStatus.CANCELLED),
            AiReviewRunStatus.VALIDATING, List.of(AiReviewRunStatus.SUCCEEDED_OK,
                    AiReviewRunStatus.SUCCEEDED_NOT_OK, AiReviewRunStatus.SUCCEEDED_NEEDS_HUMAN,
                    AiReviewRunStatus.FAILED_RETRYABLE, AiReviewRunStatus.CANCELLED)
    );

    private final Map<String, AiReviewRun> runs = new LinkedHashMap<>();
    private final Map<String, List<AiReviewEvent>> events = new LinkedHashMap<>();
    private final Map<String, List<AiReviewArtifact>> artifacts = new LinkedHashMap<>();
    private final AtomicLong eventIds = new AtomicLong();

    @Override
    public synchronized AiReviewRun create(AiReviewRun run) {
        if (runs.containsKey(run.runId())) {
            throw new IllegalStateException("AI review run already exists: " + run.runId());
        }
        boolean duplicateAttempt = runs.values().stream().anyMatch(existing ->
                existing.taskId().equals(run.taskId()) && existing.attemptNo() == run.attemptNo());
        if (duplicateAttempt) {
            throw new IllegalStateException("AI review attempt already exists: " + run.taskId() + "#" + run.attemptNo());
        }
        runs.put(run.runId(), run);
        events.put(run.runId(), new ArrayList<>());
        artifacts.put(run.runId(), new ArrayList<>());
        return run;
    }

    @Override
    public synchronized Optional<AiReviewRun> find(String runId) {
        return Optional.ofNullable(runs.get(normalize(runId)));
    }

    @Override
    public synchronized List<AiReviewRun> listByTask(String taskId) {
        String normalized = normalize(taskId);
        return runs.values().stream()
                .filter(run -> run.taskId().equals(normalized))
                .sorted(Comparator.comparingInt(AiReviewRun::attemptNo))
                .toList();
    }

    @Override
    public synchronized AiReviewRun transition(
            String runId,
            AiReviewRunStatus expectedStatus,
            AiReviewRunStatus targetStatus,
            String trigger,
            String message,
            String errorCategory,
            String errorMessage,
            long nowEpochMillis
    ) {
        AiReviewRun current = require(runId);
        if (current.status() != expectedStatus) {
            throw new IllegalStateException("stale AI review status: expected " + expectedStatus
                    + " but was " + current.status());
        }
        if (current.status().isTerminal()) {
            throw new IllegalStateException("terminal AI review run is immutable: " + current.runId());
        }
        if (!ALLOWED.getOrDefault(current.status(), List.of()).contains(targetStatus)) {
            throw new IllegalStateException("illegal AI review transition: " + current.status() + " -> " + targetStatus);
        }
        AiReviewRun updated = current.withStatus(targetStatus, errorCategory, errorMessage, nowEpochMillis);
        runs.put(updated.runId(), updated);
        events.get(updated.runId()).add(new AiReviewEvent(
                Long.toString(eventIds.incrementAndGet()), updated.runId(), current.status(), targetStatus,
                trigger, message, errorCategory, nowEpochMillis));
        return updated;
    }

    @Override
    public synchronized AiReviewRun save(AiReviewRun run) {
        AiReviewRun current = require(run.runId());
        if (current.status().isTerminal()) {
            throw new IllegalStateException("terminal AI review run is immutable: " + run.runId());
        }
        if (run.version() <= current.version()) {
            throw new IllegalStateException("stale AI review run version: " + run.runId());
        }
        runs.put(run.runId(), run);
        return run;
    }

    @Override
    public synchronized AiReviewRun complete(
            String runId,
            AiReviewRunStatus expectedStatus,
            AiReviewRunStatus terminalStatus,
            AiReviewResult result,
            String trigger,
            String message,
            long nowEpochMillis
    ) {
        AiReviewRun current = require(runId);
        if (current.status() != expectedStatus) {
            throw new IllegalStateException("stale AI review status: expected " + expectedStatus
                    + " but was " + current.status());
        }
        if (current.status().isTerminal() || !terminalStatus.isTerminal()
                || !ALLOWED.getOrDefault(current.status(), List.of()).contains(terminalStatus)) {
            throw new IllegalStateException("illegal AI review completion: " + current.status() + " -> " + terminalStatus);
        }
        AiReviewRun updated = current.withResult(result, terminalStatus, nowEpochMillis);
        runs.put(updated.runId(), updated);
        events.get(updated.runId()).add(new AiReviewEvent(
                Long.toString(eventIds.incrementAndGet()), updated.runId(), current.status(), terminalStatus,
                trigger, message, "", nowEpochMillis));
        return updated;
    }

    @Override
    public synchronized AiReviewRun retry(String terminalRunId, String nextRunId, long nowEpochMillis) {
        AiReviewRun parent = require(terminalRunId);
        if (!parent.status().isTerminal()) {
            throw new IllegalStateException("only terminal AI review run can be retried: " + terminalRunId);
        }
        return create(AiReviewRun.created(nextRunId, parent.taskId(), parent.attemptNo() + 1,
                parent.runId(), parent.modelName(), nowEpochMillis));
    }

    @Override
    public synchronized List<AiReviewEvent> listEvents(String runId) {
        require(runId);
        return List.copyOf(events.get(normalize(runId)));
    }

    @Override
    public synchronized AiReviewArtifact appendArtifact(AiReviewArtifact artifact) {
        require(artifact.runId());
        artifacts.get(artifact.runId()).add(artifact);
        return artifact;
    }

    @Override
    public synchronized List<AiReviewArtifact> listArtifacts(String runId) {
        require(runId);
        return List.copyOf(artifacts.get(normalize(runId)));
    }

    private AiReviewRun require(String runId) {
        AiReviewRun run = runs.get(normalize(runId));
        if (run == null) {
            throw new java.util.NoSuchElementException("AI review run not found: " + runId);
        }
        return run;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
