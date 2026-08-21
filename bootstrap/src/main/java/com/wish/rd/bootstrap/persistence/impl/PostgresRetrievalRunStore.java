package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RdRagRetrievalEventRow;
import com.wish.rd.bootstrap.persistence.entity.RdRagRetrievalArtifactRow;
import com.wish.rd.bootstrap.persistence.entity.RdRagRetrievalRunRow;
import com.wish.rd.bootstrap.persistence.mapper.RdRagRetrievalArtifactMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdRagRetrievalEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdRagRetrievalRunMapper;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.retrieval.run.RetrievalRunTransitionPolicy;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunArtifact;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunEvent;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** PostgreSQL source of truth for retrieval state plus append-only transitions. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresRetrievalRunStore implements RetrievalRunStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final RdRagRetrievalRunMapper runMapper;
    private final RdRagRetrievalEventMapper eventMapper;
    private final RdRagRetrievalArtifactMapper artifactMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public PostgresRetrievalRunStore(
            RdRagRetrievalRunMapper runMapper,
            RdRagRetrievalEventMapper eventMapper,
            RdRagRetrievalArtifactMapper artifactMapper,
            SnowflakeIdGenerator idGenerator,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.artifactMapper = artifactMapper;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.copy();
    }

    @Override
    @Transactional
    public RetrievalRun create(RetrievalRun run) {
        runMapper.insertRun(toRow(run, null));
        artifactMapper.insertArtifact(artifactRow(
                run.runId(), "QUERY", "", run.queryPreview(), run.queryHash(), run.createdAtEpochMillis()
        ));
        return run;
    }

    @Override
    public Optional<RetrievalRun> find(String runId) {
        return Optional.ofNullable(runMapper.selectById(PostgresPersistenceSupport.parseId(runId))).map(this::toRun);
    }

    @Override
    public List<RetrievalRun> listByTask(String taskId) {
        return runMapper.selectList(new QueryWrapper<RdRagRetrievalRunRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId)))
                .stream()
                .sorted(Comparator.comparing((RdRagRetrievalRunRow row) -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toRun)
                .toList();
    }

    @Override
    public List<RetrievalRunEvent> listEvents(String runId) {
        return eventMapper.selectList(new QueryWrapper<RdRagRetrievalEventRow>()
                        .eq("run_id", PostgresPersistenceSupport.parseId(runId))
                        .orderByAsc("occurred_at", "id"))
                .stream().map(this::toEvent).toList();
    }

    @Override
    public List<RetrievalRunArtifact> listArtifacts(String runId) {
        return artifactMapper.selectList(new QueryWrapper<RdRagRetrievalArtifactRow>()
                        .eq("run_id", PostgresPersistenceSupport.parseId(runId))
                        .orderByAsc("created_at", "id"))
                .stream()
                .map(this::toArtifact)
                .toList();
    }

    @Override
    @Transactional
    public void appendArtifact(RetrievalRunArtifact artifact) {
        if (artifact == null || artifact.runId().isBlank()) {
            throw new IllegalArgumentException("retrieval artifact run id must not be blank");
        }
        require(artifact.runId());
        artifactMapper.insertArtifact(toArtifactRow(artifact));
    }

    @Override
    @Transactional
    public RetrievalRun transition(
            String runId, long expectedVersion, RetrievalRunStatus nextStatus, int nextIteration,
            EvidenceQualityDecision qualityDecision, String stopReason, String errorCategory, String errorMessage,
            String trigger, long nowEpochMillis
    ) {
        RetrievalRun current = require(runId);
        if (current.version() != expectedVersion) {
            throw new IllegalStateException("stale retrieval run version: " + runId);
        }
        RetrievalRunTransitionPolicy.ensureTransition(current.status(), nextStatus);
        RetrievalRun updated = current.withStatus(
                nextStatus, nextIteration, qualityDecision, stopReason, errorCategory, errorMessage, nowEpochMillis
        );
        if (runMapper.transition(toRow(updated, expectedVersion)) != 1) {
            throw new IllegalStateException("retrieval run compare-and-set failed: " + runId);
        }
        eventMapper.insertEvent(toEventRow(new RetrievalRunEvent(
                idGenerator.nextIdString(), runId, current.status(), nextStatus, trigger,
                firstNonBlank(stopReason, errorMessage), errorCategory, nowEpochMillis
        )));
        if (nextStatus == RetrievalRunStatus.EVALUATING) {
            artifactMapper.insertArtifact(artifactRow(
                    runId, "QUALITY_REPORT", "", qualityDecision == null ? "" : qualityDecision.name(), "", nowEpochMillis
            ));
        }
        if (nextStatus == RetrievalRunStatus.SUCCEEDED || nextStatus == RetrievalRunStatus.SUCCEEDED_DEGRADED) {
            artifactMapper.insertArtifact(artifactRow(
                    runId, "CONTEXT_PACKAGE", "", stopReason, "", nowEpochMillis
            ));
        }
        return updated;
    }

    @Override
    public RetrievalRun updateEvidenceCounts(
            String runId, long expectedVersion, int candidateCount, int selectedEvidenceCount, long nowEpochMillis
    ) {
        RetrievalRun current = require(runId);
        if (current.version() != expectedVersion) {
            throw new IllegalStateException("stale retrieval run version: " + runId);
        }
        RetrievalRun updated = current.withEvidenceCounts(candidateCount, selectedEvidenceCount, nowEpochMillis);
        if (runMapper.updateEvidenceCounts(toRow(updated, expectedVersion)) != 1) {
            throw new IllegalStateException("retrieval evidence compare-and-set failed: " + runId);
        }
        return updated;
    }

    @Override
    @Transactional
    public RetrievalRun retry(String terminalRunId, String nextRunId, long nowEpochMillis) {
        RetrievalRun parent = require(terminalRunId);
        if (!parent.status().isTerminal()) {
            throw new IllegalStateException("retrieval run is not terminal: " + terminalRunId);
        }
        Optional<RetrievalRun> existingChild = listByTask(parent.taskId()).stream()
                .filter(run -> terminalRunId.equals(run.parentRunId()))
                .min(Comparator.comparingInt(RetrievalRun::attemptNo)
                        .thenComparingLong(RetrievalRun::createdAtEpochMillis)
                        .thenComparing(RetrievalRun::runId));
        if (existingChild.isPresent()) {
            return existingChild.get();
        }
        RetrievalRun retry = new RetrievalRun(
                nextRunId, parent.taskId(), parent.consumerType(), parent.role(), parent.stageRunId(),
                parent.attemptNo() + 1, parent.runId(), parent.idempotencyKey() + ":retry:" + (parent.attemptNo() + 1),
                RetrievalRunStatus.CREATED, parent.knowledgeBaseIds(), parent.queryHash(), parent.queryPreview(),
                0, parent.maxIterations(), parent.contextBudgetChars(), 0, 0, null, "", "", "", "", 0L,
                0L, nowEpochMillis, nowEpochMillis
        );
        return create(retry);
    }

    private RetrievalRun require(String runId) {
        return find(runId).orElseThrow(() -> new NoSuchElementException("retrieval run not found: " + runId));
    }

    private RdRagRetrievalRunRow toRow(RetrievalRun run, Long expectedVersion) {
        RdRagRetrievalRunRow row = new RdRagRetrievalRunRow();
        row.id = PostgresPersistenceSupport.parseId(run.runId());
        row.taskId = PostgresPersistenceSupport.parseId(run.taskId());
        row.consumerType = run.consumerType().name();
        row.role = run.role();
        row.stageRunId = run.stageRunId().isBlank() ? null : PostgresPersistenceSupport.parseId(run.stageRunId());
        row.attemptNo = run.attemptNo();
        row.parentRunId = run.parentRunId().isBlank() ? null : PostgresPersistenceSupport.parseId(run.parentRunId());
        row.idempotencyKey = run.idempotencyKey();
        row.status = run.status().name();
        row.knowledgeBaseIdsJson = writeJson(run.knowledgeBaseIds());
        row.queryHash = run.queryHash();
        row.queryPreview = run.queryPreview();
        row.currentIteration = run.currentIteration();
        row.maxIterations = run.maxIterations();
        row.contextBudgetChars = run.contextBudgetChars();
        row.candidateCount = run.candidateCount();
        row.selectedEvidenceCount = run.selectedEvidenceCount();
        row.qualityDecision = run.qualityDecision() == null ? "" : run.qualityDecision().name();
        row.stopReason = run.stopReason();
        row.errorCategory = run.errorCategory();
        row.errorMessage = run.errorMessage();
        row.leaseOwner = run.leaseOwner();
        row.leaseUntil = PostgresPersistenceSupport.nullableDateTime(run.leaseUntilEpochMillis());
        row.version = run.version();
        row.createdAt = PostgresPersistenceSupport.toDateTime(run.createdAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(run.updatedAtEpochMillis());
        row.expectedVersion = expectedVersion;
        return row;
    }

    private RetrievalRun toRun(RdRagRetrievalRunRow row) {
        // Legacy rows predate consumer_type and can be NULL. BUG_FIX is the historically correct
        // label for them because the ticket pipeline was the only writer back then; the record
        // itself now rejects a null consumer so new writes cannot inherit this fallback.
        return new RetrievalRun(
                PostgresPersistenceSupport.idString(row.id), PostgresPersistenceSupport.idString(row.taskId),
                enumValue(RetrievalConsumerType.class, row.consumerType, RetrievalConsumerType.BUG_FIX), row.role,
                PostgresPersistenceSupport.idString(row.stageRunId), row.attemptNo == null ? 1 : row.attemptNo,
                PostgresPersistenceSupport.idString(row.parentRunId), row.idempotencyKey,
                enumValue(RetrievalRunStatus.class, row.status, RetrievalRunStatus.CREATED), readJson(row.knowledgeBaseIdsJson),
                row.queryHash, row.queryPreview, row.currentIteration == null ? 0 : row.currentIteration,
                row.maxIterations == null ? 3 : row.maxIterations,
                row.contextBudgetChars == null ? 18_000 : row.contextBudgetChars,
                row.candidateCount == null ? 0 : row.candidateCount,
                row.selectedEvidenceCount == null ? 0 : row.selectedEvidenceCount,
                enumValue(EvidenceQualityDecision.class, row.qualityDecision, null), row.stopReason, row.errorCategory,
                row.errorMessage, row.leaseOwner, PostgresPersistenceSupport.toEpochMillis(row.leaseUntil),
                row.version == null ? 0L : row.version, PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private RdRagRetrievalEventRow toEventRow(RetrievalRunEvent event) {
        RdRagRetrievalEventRow row = new RdRagRetrievalEventRow();
        row.id = PostgresPersistenceSupport.parseId(event.eventId());
        row.runId = PostgresPersistenceSupport.parseId(event.runId());
        row.fromStatus = event.fromStatus().name();
        row.toStatus = event.toStatus().name();
        row.trigger = event.trigger();
        row.message = event.message();
        row.errorCategory = event.errorCategory();
        row.occurredAt = PostgresPersistenceSupport.toDateTime(event.occurredAtEpochMillis());
        return row;
    }

    private RdRagRetrievalArtifactRow artifactRow(
            String runId, String artifactType, String artifactUri, String preview, String contentHash, long createdAtEpochMillis
    ) {
        RdRagRetrievalArtifactRow row = new RdRagRetrievalArtifactRow();
        row.id = PostgresPersistenceSupport.parseId(idGenerator.nextIdString());
        row.runId = PostgresPersistenceSupport.parseId(runId);
        row.artifactType = artifactType;
        row.artifactUri = artifactUri == null ? "" : artifactUri;
        row.contentPreview = boundedPreview(preview);
        row.contentHash = contentHash == null ? "" : contentHash;
        row.metadataJson = "{}";
        row.redacted = true;
        row.createdAt = PostgresPersistenceSupport.toDateTime(createdAtEpochMillis);
        return row;
    }

    private RdRagRetrievalArtifactRow toArtifactRow(RetrievalRunArtifact artifact) {
        RdRagRetrievalArtifactRow row = new RdRagRetrievalArtifactRow();
        row.id = PostgresPersistenceSupport.parseId(artifact.artifactId());
        row.runId = PostgresPersistenceSupport.parseId(artifact.runId());
        row.artifactType = artifact.artifactType();
        row.artifactUri = artifact.artifactUri();
        row.contentPreview = boundedPreview(artifact.contentPreview());
        row.contentHash = artifact.contentHash();
        row.metadataJson = "{}";
        row.redacted = artifact.redacted();
        row.createdAt = PostgresPersistenceSupport.toDateTime(artifact.createdAtEpochMillis());
        return row;
    }

    private RetrievalRunArtifact toArtifact(RdRagRetrievalArtifactRow row) {
        return new RetrievalRunArtifact(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.runId),
                row.artifactType, row.artifactUri, row.contentPreview, row.contentHash,
                Boolean.TRUE.equals(row.redacted), PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }

    private static String boundedPreview(String preview) {
        String safePreview = preview == null ? "" : preview;
        return safePreview.length() <= 2_000 ? safePreview : safePreview.substring(0, 2_000);
    }

    private RetrievalRunEvent toEvent(RdRagRetrievalEventRow row) {
        return new RetrievalRunEvent(
                PostgresPersistenceSupport.idString(row.id), PostgresPersistenceSupport.idString(row.runId),
                enumValue(RetrievalRunStatus.class, row.fromStatus, RetrievalRunStatus.CREATED),
                enumValue(RetrievalRunStatus.class, row.toStatus, RetrievalRunStatus.CREATED), row.trigger,
                row.message, row.errorCategory, PostgresPersistenceSupport.toEpochMillis(row.occurredAt)
        );
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize retrieval scope", exception);
        }
    }

    private List<String> readJson(String values) {
        try {
            return objectMapper.readValue(values == null || values.isBlank() ? "[]" : values, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse retrieval scope", exception);
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static String firstNonBlank(String left, String right) {
        return left != null && !left.isBlank() ? left : right == null ? "" : right;
    }
}
