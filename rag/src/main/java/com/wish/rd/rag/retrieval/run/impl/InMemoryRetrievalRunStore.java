package com.wish.rd.rag.retrieval.run.impl;

import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.retrieval.run.RetrievalRunTransitionPolicy;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunArtifact;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunEvent;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** In-memory implementation for tests and local starts without PostgreSQL. */
public final class InMemoryRetrievalRunStore implements RetrievalRunStore {

    private final LinkedHashMap<String, RetrievalRun> runs = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<RetrievalRunEvent>> eventsByRunId = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<RetrievalRunArtifact>> artifactsByRunId = new LinkedHashMap<>();

    @Override
    public synchronized RetrievalRun create(RetrievalRun run) {
        if (run == null || run.runId().isBlank()) {
            throw new IllegalArgumentException("retrieval run id must not be blank");
        }
        if (runs.containsKey(run.runId())) {
            throw new IllegalStateException("retrieval run already exists: " + run.runId());
        }
        if (!run.idempotencyKey().isBlank()) {
            boolean duplicateKey = runs.values().stream()
                    .anyMatch(existing -> run.idempotencyKey().equals(existing.idempotencyKey()));
            if (duplicateKey) {
                throw new IllegalStateException("retrieval run idempotency key already exists: " + run.idempotencyKey());
            }
        }
        runs.put(run.runId(), run);
        eventsByRunId.put(run.runId(), new ArrayList<>());
        artifactsByRunId.put(run.runId(), new ArrayList<>());
        appendArtifact(new RetrievalRunArtifact(
                run.runId() + ":query", run.runId(), "QUERY", "", run.queryPreview(), run.queryHash(), true,
                run.createdAtEpochMillis()
        ));
        return run;
    }

    @Override
    public synchronized Optional<RetrievalRun> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public synchronized List<RetrievalRun> listByTask(String taskId) {
        String safeTaskId = taskId == null ? "" : taskId.strip();
        return runs.values().stream()
                .filter(run -> safeTaskId.equals(run.taskId()))
                .sorted(Comparator.comparingInt(RetrievalRun::attemptNo)
                        .thenComparingLong(RetrievalRun::createdAtEpochMillis)
                        .thenComparing(RetrievalRun::runId))
                .toList();
    }

    @Override
    public synchronized List<RetrievalRunEvent> listEvents(String runId) {
        return List.copyOf(eventsByRunId.getOrDefault(runId, List.of()));
    }

    @Override
    public synchronized List<RetrievalRunArtifact> listArtifacts(String runId) {
        return List.copyOf(artifactsByRunId.getOrDefault(runId, List.of()));
    }

    @Override
    public synchronized void appendArtifact(RetrievalRunArtifact artifact) {
        if (artifact == null || artifact.runId().isBlank()) {
            throw new IllegalArgumentException("retrieval artifact run id must not be blank");
        }
        require(artifact.runId());
        artifactsByRunId.computeIfAbsent(artifact.runId(), ignored -> new ArrayList<>()).add(artifact);
    }

    @Override
    public synchronized RetrievalRun updateEvidenceCounts(
            String runId,
            long expectedVersion,
            int candidateCount,
            int selectedEvidenceCount,
            long nowEpochMillis
    ) {
        RetrievalRun current = require(runId);
        if (current.version() != expectedVersion) {
            throw new IllegalStateException("stale retrieval run version: " + runId);
        }
        RetrievalRun updated = current.withEvidenceCounts(candidateCount, selectedEvidenceCount, nowEpochMillis);
        runs.put(runId, updated);
        return updated;
    }

    @Override
    public synchronized RetrievalRun transition(
            String runId,
            long expectedVersion,
            RetrievalRunStatus nextStatus,
            int nextIteration,
            EvidenceQualityDecision qualityDecision,
            String stopReason,
            String errorCategory,
            String errorMessage,
            String trigger,
            long nowEpochMillis
    ) {
        RetrievalRun current = require(runId);
        if (current.version() != expectedVersion) {
            throw new IllegalStateException("stale retrieval run version: " + runId);
        }
        RetrievalRunTransitionPolicy.ensureTransition(current.status(), nextStatus);
        RetrievalRun updated = current.withStatus(
                nextStatus, nextIteration, qualityDecision, stopReason, errorCategory, errorMessage, nowEpochMillis
        );
        runs.put(runId, updated);
        eventsByRunId.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(new RetrievalRunEvent(
                runId + ":" + updated.version(), runId, current.status(), nextStatus, trigger,
                firstNonBlank(stopReason, errorMessage), errorCategory, nowEpochMillis
        ));
        if (nextStatus == RetrievalRunStatus.EVALUATING) {
            appendArtifact(new RetrievalRunArtifact(
                    runId + ":quality:" + updated.version(), runId, "QUALITY_REPORT", "",
                    qualityDecision == null ? "" : qualityDecision.name(), "", true, nowEpochMillis
            ));
        }
        if (nextStatus == RetrievalRunStatus.SUCCEEDED || nextStatus == RetrievalRunStatus.SUCCEEDED_DEGRADED) {
            appendArtifact(new RetrievalRunArtifact(
                    runId + ":context:" + updated.version(), runId, "CONTEXT_PACKAGE", "", stopReason, "", true,
                    nowEpochMillis
            ));
        }
        return updated;
    }

    @Override
    public synchronized RetrievalRun retry(String terminalRunId, String nextRunId, long nowEpochMillis) {
        RetrievalRun parent = require(terminalRunId);
        if (!parent.status().isTerminal()) {
            throw new IllegalStateException("retrieval run is not terminal: " + terminalRunId);
        }
        Optional<RetrievalRun> existingChild = runs.values().stream()
                .filter(run -> terminalRunId.equals(run.parentRunId()))
                .min(Comparator.comparingInt(RetrievalRun::attemptNo)
                        .thenComparingLong(RetrievalRun::createdAtEpochMillis)
                        .thenComparing(RetrievalRun::runId));
        if (existingChild.isPresent()) {
            return existingChild.get();
        }
        if (nextRunId == null || nextRunId.isBlank()) {
            throw new IllegalArgumentException("next retrieval run id must not be blank");
        }
        return create(new RetrievalRun(
                nextRunId, parent.taskId(), parent.consumerType(), parent.role(), parent.stageRunId(),
                parent.attemptNo() + 1, parent.runId(), parent.idempotencyKey() + ":retry:" + (parent.attemptNo() + 1),
                RetrievalRunStatus.CREATED, parent.knowledgeBaseIds(), parent.queryHash(), parent.queryPreview(),
                0, parent.maxIterations(), parent.contextBudgetChars(), 0, 0, null, "", "", "", "", 0L,
                0L, nowEpochMillis, nowEpochMillis
        ));
    }

    private RetrievalRun require(String runId) {
        RetrievalRun run = runs.get(runId);
        if (run == null) {
            throw new NoSuchElementException("retrieval run not found: " + runId);
        }
        return run;
    }

    private static String firstNonBlank(String left, String right) {
        return left != null && !left.isBlank() ? left : right == null ? "" : right;
    }
}
