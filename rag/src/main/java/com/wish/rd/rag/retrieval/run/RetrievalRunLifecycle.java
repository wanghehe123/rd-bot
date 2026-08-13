package com.wish.rd.rag.retrieval.run;

import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunArtifact;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Small use-case layer that keeps retrieval state transitions and audit records consistent. */
public final class RetrievalRunLifecycle {

    private static final int DEFAULT_MAX_ITERATIONS = 3;
    private static final int DEFAULT_CONTEXT_BUDGET_CHARS = 18_000;

    private final RetrievalRunStore store;
    private final Supplier<String> idSupplier;
    private final LongSupplier clock;

    public RetrievalRunLifecycle(RetrievalRunStore store, Supplier<String> idSupplier, LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public RetrievalRun start(
            String taskId,
            RetrievalConsumerType consumerType,
            String role,
            String stageRunId,
            String query,
            List<String> knowledgeBaseIds
    ) {
        Optional<RetrievalRun> latest = findLatest(taskId, consumerType, role, stageRunId);
        if (latest.isPresent()) {
            RetrievalRun existing = latest.get();
            if (!existing.status().isTerminal()) {
                return ensureRetrieving(existing);
            }
            RetrievalRun child = store.retry(existing.runId(), idSupplier.get(), clock.getAsLong());
            return advanceCreatedToRetrieving(child);
        }
        long now = clock.getAsLong();
        String runId = idSupplier.get();
        RetrievalRun created = store.create(new RetrievalRun(
                runId, taskId, consumerType, role, stageRunId, 1, "",
                idempotencyKey(taskId, consumerType, role, stageRunId, 1), RetrievalRunStatus.CREATED,
                knowledgeBaseIds, sha256(query), preview(query), 0, configuredPositiveInt(
                "RD_RAG_DEEP_MAX_ITERATIONS", DEFAULT_MAX_ITERATIONS),
                configuredPositiveInt("RD_RAG_DEEP_CONTEXT_BUDGET_CHARS", DEFAULT_CONTEXT_BUDGET_CHARS),
                0, 0, null, "", "", "", "", 0L, 0L, now, now
        ));
        return advanceCreatedToRetrieving(created);
    }

    public RetrievalRun complete(
            String runId,
            int candidateCount,
            int selectedEvidenceCount,
            boolean degraded,
            String stopReason
    ) {
        if (selectedEvidenceCount <= 0) {
            return waitForInput(runId, firstNonBlank(stopReason, "无可用证据"));
        }
        RetrievalRun current = require(runId);
        long now = clock.getAsLong();
        RetrievalRun evaluating = store.transition(
                current.runId(), current.version(), RetrievalRunStatus.EVALUATING, current.currentIteration(),
                degraded ? EvidenceQualityDecision.DEGRADED_ACCEPTABLE : EvidenceQualityDecision.SUFFICIENT,
                "", "", "", "SYSTEM", now
        );
        RetrievalRun withCounts = store.updateEvidenceCounts(
                evaluating.runId(), evaluating.version(), candidateCount, selectedEvidenceCount, now
        );
        RetrievalRun packaging = store.transition(
                withCounts.runId(), withCounts.version(), RetrievalRunStatus.PACKAGING, withCounts.currentIteration(),
                withCounts.qualityDecision(), stopReason, "", "", "SYSTEM", now
        );
        return store.transition(
                packaging.runId(), packaging.version(),
                degraded ? RetrievalRunStatus.SUCCEEDED_DEGRADED : RetrievalRunStatus.SUCCEEDED,
                packaging.currentIteration(), packaging.qualityDecision(), stopReason, "", "", "SYSTEM", now
        );
    }

    public RetrievalRun waitForInput(String runId, String reason) {
        RetrievalRun current = require(runId);
        return waitForInput(runId, current.candidateCount(), current.selectedEvidenceCount(), reason);
    }

    /** Records observed evidence counts before entering the immutable WAITING_INPUT terminal state. */
    public RetrievalRun waitForInput(
            String runId,
            int candidateCount,
            int selectedEvidenceCount,
            String reason
    ) {
        RetrievalRun current = require(runId);
        if (current.status().isTerminal()) {
            return current;
        }
        long now = clock.getAsLong();
        current = store.updateEvidenceCounts(
                current.runId(), current.version(), candidateCount, selectedEvidenceCount, now
        );
        if (current.status() == RetrievalRunStatus.RETRIEVING) {
            current = store.transition(
                    current.runId(), current.version(), RetrievalRunStatus.EVALUATING, current.currentIteration(),
                    EvidenceQualityDecision.NEED_INPUT, "", "", "", "SYSTEM", now
            );
        }
        return store.transition(
                current.runId(), current.version(), RetrievalRunStatus.WAITING_INPUT, current.currentIteration(),
                EvidenceQualityDecision.NEED_INPUT, reason, "", "", "SYSTEM", now
        );
    }

    /**
     * 只读查找任务上最近一次检索 run，供旁路探测把 artifact 挂到已有 attempt。
     * 不得为此再 {@link #start}：start 会把非终态 run 当成同一 attempt 复用。
     *
     * @param taskId 任务 ID
     * @return 最近一次 run，没有则空
     */
    public Optional<RetrievalRun> findLatestForTask(String taskId) {
        String safeTaskId = taskId == null ? "" : taskId.strip();
        if (safeTaskId.isBlank()) {
            return Optional.empty();
        }
        return store.listByTask(safeTaskId).stream()
                .max(Comparator.comparingInt(RetrievalRun::attemptNo)
                        .thenComparingLong(RetrievalRun::createdAtEpochMillis)
                        .thenComparing(RetrievalRun::runId));
    }

    /**
     * Records a bounded redacted preview so operators can inspect retrieval behavior without
     * exposing raw prompts or complete source documents.
     *
     * @param runId retrieval attempt identifier
     * @param artifactType process/evidence classification
     * @param artifactUri safe logical location such as a retrieval channel
     * @param contentPreview redacted, bounded human-readable preview
     * @param contentHash content hash when available
     */
    public RetrievalRunArtifact appendArtifact(
            String runId,
            String artifactType,
            String artifactUri,
            String contentPreview,
            String contentHash
    ) {
        RetrievalRunArtifact artifact = new RetrievalRunArtifact(
                idSupplier.get(), runId, artifactType, artifactUri, contentPreview, contentHash, true,
                clock.getAsLong()
        );
        store.appendArtifact(artifact);
        return artifact;
    }

    public RetrievalRun failRetryable(String runId, String errorCategory, String errorMessage) {
        return fail(runId, RetrievalRunStatus.FAILED_RETRYABLE, errorCategory, errorMessage);
    }

    public RetrievalRun failNeedsHuman(String runId, String errorCategory, String errorMessage) {
        return fail(runId, RetrievalRunStatus.FAILED_NEEDS_HUMAN, errorCategory, errorMessage);
    }

    private RetrievalRun fail(
            String runId,
            RetrievalRunStatus terminal,
            String errorCategory,
            String errorMessage
    ) {
        RetrievalRun current = require(runId);
        if (current.status().isTerminal()) {
            return current;
        }
        return store.transition(
                current.runId(), current.version(), terminal, current.currentIteration(),
                current.qualityDecision(), firstNonBlank(errorMessage, terminal.name()),
                safe(errorCategory), safe(errorMessage), "SYSTEM", clock.getAsLong()
        );
    }

    private RetrievalRun ensureRetrieving(RetrievalRun run) {
        if (run.status() == RetrievalRunStatus.RETRIEVING
                || run.status() == RetrievalRunStatus.EVALUATING
                || run.status() == RetrievalRunStatus.PACKAGING) {
            return run;
        }
        return advanceCreatedToRetrieving(run);
    }

    private RetrievalRun advanceCreatedToRetrieving(RetrievalRun created) {
        long now = clock.getAsLong();
        RetrievalRun current = created;
        if (current.status() == RetrievalRunStatus.RECOVERING) {
            current = store.transition(
                    current.runId(), current.version(), RetrievalRunStatus.PLANNING, current.currentIteration(),
                    null, "", "", "", "SYSTEM", now
            );
        }
        if (current.status() == RetrievalRunStatus.CREATED) {
            current = store.transition(
                    current.runId(), current.version(), RetrievalRunStatus.PLANNING, 0, null,
                    "", "", "", "SYSTEM", now
            );
        }
        if (current.status() == RetrievalRunStatus.PLANNING) {
            current = store.transition(
                    current.runId(), current.version(), RetrievalRunStatus.RETRIEVING, 0, null,
                    "", "", "", "SYSTEM", now
            );
        }
        return current;
    }

    private Optional<RetrievalRun> findLatest(
            String taskId,
            RetrievalConsumerType consumerType,
            String role,
            String stageRunId
    ) {
        String safeRole = safe(role).toUpperCase();
        String safeStageRunId = safe(stageRunId);
        RetrievalConsumerType safeConsumer = consumerType == null ? RetrievalConsumerType.BUG_FIX : consumerType;
        return store.listByTask(taskId).stream()
                .filter(run -> run.consumerType() == safeConsumer)
                .filter(run -> safeRole.equals(run.role()))
                .filter(run -> safeStageRunId.equals(run.stageRunId()))
                .max(Comparator.comparingInt(RetrievalRun::attemptNo)
                        .thenComparingLong(RetrievalRun::createdAtEpochMillis)
                        .thenComparing(RetrievalRun::runId));
    }

    private RetrievalRun require(String runId) {
        return store.find(runId).orElseThrow(() -> new IllegalArgumentException("retrieval run not found: " + runId));
    }

    private static String idempotencyKey(
            String taskId,
            RetrievalConsumerType consumerType,
            String role,
            String stageRunId,
            int attemptNo
    ) {
        return safe(taskId) + ":" + (consumerType == null ? "BUG_FIX" : consumerType.name()) + ":"
                + safe(role) + ":" + safe(stageRunId) + ":" + attemptNo;
    }

    private static String preview(String query) {
        String value = safe(query);
        return value.length() <= 240 ? value : value.substring(0, 240);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(safe(value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String firstNonBlank(String left, String right) {
        return left != null && !left.isBlank() ? left : right == null ? "" : right;
    }

    private static int configuredPositiveInt(String environmentVariable, int fallback) {
        try {
            int configured = Integer.parseInt(System.getenv(environmentVariable));
            return configured > 0 ? configured : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
