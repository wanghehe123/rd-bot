package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.EvaluationArtifactRow;
import com.wish.rd.bootstrap.persistence.entity.EvaluationEventRow;
import com.wish.rd.bootstrap.persistence.entity.EvaluationRunOverviewRow;
import com.wish.rd.bootstrap.persistence.entity.EvaluationRunRow;
import com.wish.rd.bootstrap.persistence.mapper.EvaluationArtifactMapper;
import com.wish.rd.bootstrap.persistence.mapper.EvaluationEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.EvaluationRunMapper;
import com.wish.rd.engine.evaluation.EvaluationRunStore;
import com.wish.rd.engine.evaluation.EvaluationTransitionPolicy;
import com.wish.rd.engine.evaluation.model.EvaluationArtifact;
import com.wish.rd.engine.evaluation.model.EvaluationExecutionResult;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunEvent;
import com.wish.rd.engine.evaluation.model.EvaluationRunOverview;
import com.wish.rd.engine.evaluation.model.EvaluationRunPage;
import com.wish.rd.engine.evaluation.model.EvaluationRunQuery;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL EvaluationRun Store with CAS snapshots and transactional append-only events. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresEvaluationRunStore implements EvaluationRunStore {
    private final EvaluationRunMapper runMapper;
    private final EvaluationEventMapper eventMapper;
    private final EvaluationArtifactMapper artifactMapper;
    private final ObjectMapper objectMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final EvaluationTransitionPolicy transitionPolicy = new EvaluationTransitionPolicy();

    public PostgresEvaluationRunStore(
            EvaluationRunMapper runMapper,
            EvaluationEventMapper eventMapper,
            EvaluationArtifactMapper artifactMapper,
            ObjectMapper objectMapper,
            SnowflakeIdGenerator idGenerator
    ) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.artifactMapper = artifactMapper;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
    }

    @Override
    @Transactional
    public EvaluationRun create(EvaluationRun run) {
        runMapper.insertRun(toRow(run, null, null));
        appendEvent(run.runId(), null, EvaluationRunStatus.CREATED, "created", "", "", run.createdAtEpochMillis());
        return run;
    }

    @Override
    public Optional<EvaluationRun> find(String runId) {
        return Optional.ofNullable(runMapper.selectById(PostgresPersistenceSupport.parseId(runId))).map(this::toRun);
    }

    @Override
    public List<EvaluationRun> list() {
        return runMapper.selectList(new QueryWrapper<EvaluationRunRow>()
                        .orderByDesc("created_at", "id"))
                .stream().map(this::toRun).toList();
    }

    @Override
    public EvaluationRunPage query(EvaluationRunQuery query) {
        EvaluationRunQuery safeQuery = Objects.requireNonNull(query, "query must not be null");
        List<EvaluationRunRow> rows = runMapper.selectHistoryPage(safeQuery, safeQuery.pageSize(), safeQuery.offset());
        long total = runMapper.countHistoryPage(safeQuery);
        EvaluationRunOverviewRow overview = runMapper.selectHistoryOverview(safeQuery.nonSmokeDatasetIds());
        return EvaluationRunPage.of(
                (rows == null ? List.<EvaluationRunRow>of() : rows).stream().map(this::toRun).toList(),
                total,
                safeQuery,
                overview == null
                        ? new EvaluationRunOverview(0, 0, 0, 0, 0)
                        : new EvaluationRunOverview(
                                longValue(overview.total),
                                longValue(overview.active),
                                longValue(overview.gatePassed),
                                longValue(overview.incomplete),
                                longValue(overview.failed)
                        )
        );
    }

    @Override
    @Transactional
    public EvaluationRun transition(
            String runId,
            EvaluationRunStatus expected,
            EvaluationRunStatus target,
            String message,
            String errorCategory,
            String errorMessage,
            long now
    ) {
        EvaluationRun current = require(runId);
        requireExpected(current, expected);
        transitionPolicy.requireTransition(current.config().mode(), expected, target);
        EvaluationRun updated = current.withStatus(target, message, errorCategory, errorMessage, now);
        compareAndSet(current, updated);
        appendEvent(runId, expected, target, message, errorCategory, errorMessage, now);
        return updated;
    }

    @Override
    @Transactional
    public EvaluationRun complete(
            String runId,
            EvaluationRunStatus expected,
            EvaluationExecutionResult result,
            long now
    ) {
        EvaluationRun current = require(runId);
        requireExpected(current, expected);
        transitionPolicy.requireTransition(current.config().mode(), expected, EvaluationRunStatus.SUCCEEDED);
        EvaluationRun completed = current.withResult(result, now);
        compareAndSet(current, completed);
        appendEvent(runId, expected, EvaluationRunStatus.SUCCEEDED,
                "evaluation completed", "", "", now);
        return completed;
    }

    @Override
    @Transactional
    public void appendArtifacts(String runId, List<EvaluationArtifact> artifacts) {
        require(runId);
        for (EvaluationArtifact artifact : artifacts == null ? List.<EvaluationArtifact>of() : artifacts) {
            EvaluationArtifactRow row = new EvaluationArtifactRow();
            row.id = PostgresPersistenceSupport.parseId(idGenerator.nextIdString());
            row.runId = PostgresPersistenceSupport.parseId(runId);
            row.artifactType = safe(artifact.artifactType());
            row.artifactUri = safe(artifact.artifactUri());
            row.contentPreview = bounded(artifact.contentPreview(), 20_000);
            row.contentHash = safe(artifact.contentHash());
            row.sizeBytes = Math.max(0L, artifact.sizeBytes());
            row.createdAt = PostgresPersistenceSupport.toDateTime(artifact.createdAtEpochMillis());
            artifactMapper.insertArtifact(row);
        }
    }

    @Override
    public List<EvaluationRunEvent> listEvents(String runId) {
        require(runId);
        return eventMapper.selectList(new QueryWrapper<EvaluationEventRow>()
                        .eq("run_id", PostgresPersistenceSupport.parseId(runId))
                        .orderByAsc("occurred_at", "id"))
                .stream().map(this::toEvent).toList();
    }

    @Override
    public List<EvaluationArtifact> listArtifacts(String runId) {
        require(runId);
        return artifactMapper.selectList(new QueryWrapper<EvaluationArtifactRow>()
                        .eq("run_id", PostgresPersistenceSupport.parseId(runId))
                        .orderByAsc("created_at", "id"))
                .stream().map(this::toArtifact).toList();
    }

    private EvaluationRun require(String runId) {
        return find(runId).orElseThrow(() -> new NoSuchElementException("evaluation run not found: " + runId));
    }

    private void requireExpected(EvaluationRun current, EvaluationRunStatus expected) {
        if (current.status() != expected) {
            throw new IllegalStateException("evaluation status conflict: expected " + expected + " but was " + current.status());
        }
        if (current.status().isTerminal()) {
            throw new IllegalStateException("terminal evaluation run is immutable: " + current.runId());
        }
    }

    private void compareAndSet(EvaluationRun current, EvaluationRun updated) {
        if (runMapper.compareAndSet(toRow(updated, current.status(), current.version())) != 1) {
            throw new IllegalStateException("evaluation compare-and-set failed: " + current.runId());
        }
    }

    private void appendEvent(
            String runId,
            EvaluationRunStatus from,
            EvaluationRunStatus to,
            String message,
            String errorCategory,
            String errorMessage,
            long occurredAt
    ) {
        EvaluationEventRow row = new EvaluationEventRow();
        row.id = PostgresPersistenceSupport.parseId(idGenerator.nextIdString());
        row.runId = PostgresPersistenceSupport.parseId(runId);
        row.fromStatus = from == null ? "" : from.name();
        row.toStatus = to.name();
        row.message = safe(message);
        row.errorCategory = safe(errorCategory);
        row.errorMessage = bounded(errorMessage, 2_000);
        row.occurredAt = PostgresPersistenceSupport.toDateTime(occurredAt);
        eventMapper.insertEvent(row);
    }

    private EvaluationRunRow toRow(EvaluationRun run, EvaluationRunStatus expectedStatus, Long expectedVersion) {
        EvaluationRunRow row = new EvaluationRunRow();
        row.id = PostgresPersistenceSupport.parseId(run.runId());
        row.name = run.name();
        row.attemptNo = run.attemptNo();
        row.parentRunId = nullableId(run.parentRunId());
        row.status = run.status().name();
        row.phaseMessage = run.phaseMessage();
        row.progressPercent = run.progressPercent();
        row.configJson = writeJson(run.config());
        row.sampleCount = run.sampleCount();
        row.passedSampleCount = run.passedSampleCount();
        row.failedSampleCount = run.failedSampleCount();
        row.overallPassed = run.overallPassed();
        row.metricsJson = validJson(run.metricsJson(), "[]");
        row.errorCategory = run.errorCategory();
        row.errorMessage = run.errorMessage();
        row.version = run.version();
        row.createdAt = PostgresPersistenceSupport.toDateTime(run.createdAtEpochMillis());
        row.startedAt = PostgresPersistenceSupport.nullableDateTime(run.startedAtEpochMillis());
        row.finishedAt = PostgresPersistenceSupport.nullableDateTime(run.finishedAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(run.updatedAtEpochMillis());
        row.expectedStatus = expectedStatus == null ? null : expectedStatus.name();
        row.expectedVersion = expectedVersion;
        return row;
    }

    private EvaluationRun toRun(EvaluationRunRow row) {
        return new EvaluationRun(
                PostgresPersistenceSupport.idString(row.id), safe(row.name), value(row.attemptNo, 1),
                PostgresPersistenceSupport.idString(row.parentRunId),
                enumValue(row.status, EvaluationRunStatus.CREATED), safe(row.phaseMessage),
                value(row.progressPercent, 0), readConfig(row.configJson),
                value(row.sampleCount, 0), value(row.passedSampleCount, 0), value(row.failedSampleCount, 0),
                Boolean.TRUE.equals(row.overallPassed), validJson(row.metricsJson, "[]"),
                safe(row.errorCategory), safe(row.errorMessage), row.version == null ? 0L : row.version,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.startedAt),
                PostgresPersistenceSupport.toEpochMillis(row.finishedAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private EvaluationRunEvent toEvent(EvaluationEventRow row) {
        return new EvaluationRunEvent(
                PostgresPersistenceSupport.idString(row.id), PostgresPersistenceSupport.idString(row.runId),
                row.fromStatus == null || row.fromStatus.isBlank() ? null : enumValue(row.fromStatus, null),
                enumValue(row.toStatus, EvaluationRunStatus.CREATED), safe(row.message),
                safe(row.errorCategory), safe(row.errorMessage), PostgresPersistenceSupport.toEpochMillis(row.occurredAt)
        );
    }

    private EvaluationArtifact toArtifact(EvaluationArtifactRow row) {
        return new EvaluationArtifact(
                PostgresPersistenceSupport.idString(row.id), safe(row.artifactType), safe(row.artifactUri),
                safe(row.contentPreview), safe(row.contentHash), row.sizeBytes == null ? 0L : row.sizeBytes,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }

    private EvaluationRunConfig readConfig(String json) {
        try {
            return objectMapper.readValue(validJson(json, "{}"), EvaluationRunConfig.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot deserialize evaluation configuration", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evaluation configuration", exception);
        }
    }

    private String validJson(String value, String fallback) {
        String candidate = safe(value);
        if (candidate.isBlank()) {
            return fallback;
        }
        try {
            objectMapper.readTree(candidate);
            return candidate;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted evaluation JSON", exception);
        }
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long longValue(Long value) {
        return value == null ? 0L : value;
    }

    private static EvaluationRunStatus enumValue(String value, EvaluationRunStatus fallback) {
        try {
            return value == null || value.isBlank() ? fallback : EvaluationRunStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static Long nullableId(String value) {
        String normalized = safe(value);
        return normalized.isBlank() ? null : PostgresPersistenceSupport.parseId(normalized);
    }

    private static String bounded(String value, int maxChars) {
        String normalized = safe(value);
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
