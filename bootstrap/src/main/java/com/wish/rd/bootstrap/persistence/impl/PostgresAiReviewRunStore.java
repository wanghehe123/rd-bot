package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.AiReviewArtifactRow;
import com.wish.rd.bootstrap.persistence.entity.AiReviewEventRow;
import com.wish.rd.bootstrap.persistence.entity.AiReviewRunRow;
import com.wish.rd.bootstrap.persistence.mapper.AiReviewArtifactMapper;
import com.wish.rd.bootstrap.persistence.mapper.AiReviewEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.AiReviewRunMapper;
import com.wish.rd.engine.requirement.review.AiReviewRunStore;
import com.wish.rd.engine.requirement.review.model.AiReviewArtifact;
import com.wish.rd.engine.requirement.review.model.AiReviewDecision;
import com.wish.rd.engine.requirement.review.model.AiReviewEvent;
import com.wish.rd.engine.requirement.review.model.AiReviewResult;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/** PostgreSQL AI review store with CAS snapshots and append-only event/artifact history. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresAiReviewRunStore implements AiReviewRunStore {

    private static final Map<AiReviewRunStatus, List<AiReviewRunStatus>> ALLOWED = allowedTransitions();

    private final AiReviewRunMapper runMapper;
    private final AiReviewEventMapper eventMapper;
    private final AiReviewArtifactMapper artifactMapper;
    private final SnowflakeIdGenerator idGenerator;

    public PostgresAiReviewRunStore(
            AiReviewRunMapper runMapper,
            AiReviewEventMapper eventMapper,
            AiReviewArtifactMapper artifactMapper,
            SnowflakeIdGenerator idGenerator
    ) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.artifactMapper = artifactMapper;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
    }

    @Override
    public AiReviewRun create(AiReviewRun run) {
        runMapper.insertRun(toRow(run, null, null));
        return run;
    }

    @Override
    public Optional<AiReviewRun> find(String runId) {
        return Optional.ofNullable(runMapper.selectById(PostgresPersistenceSupport.parseId(runId)))
                .map(this::toRun);
    }

    @Override
    public List<AiReviewRun> listByTask(String taskId) {
        return runMapper.selectList(new QueryWrapper<AiReviewRunRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId))
                        .orderByAsc("attempt_no", "created_at", "id"))
                .stream().map(this::toRun).toList();
    }

    @Override
    @Transactional
    public AiReviewRun transition(
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
        requireExpectedAndAllowed(current, expectedStatus, targetStatus);
        AiReviewRun updated = current.withStatus(targetStatus, safe(errorCategory), safe(errorMessage), nowEpochMillis);
        compareAndSet(current, updated);
        appendEvent(current, updated, trigger, message, errorCategory, nowEpochMillis);
        return updated;
    }

    @Override
    @Transactional
    public AiReviewRun save(AiReviewRun run) {
        if (run == null) {
            throw new IllegalArgumentException("AI review run must not be null");
        }
        AiReviewRun current = require(run.runId());
        if (current.status().isTerminal()) {
            throw new IllegalStateException("terminal AI review run is immutable: " + run.runId());
        }
        if (run.version() != current.version() + 1L || run.status() != current.status()) {
            throw new IllegalStateException("stale AI review run version: " + run.runId());
        }
        compareAndSet(current, run);
        return run;
    }

    @Override
    @Transactional
    public AiReviewRun complete(
            String runId,
            AiReviewRunStatus expectedStatus,
            AiReviewRunStatus terminalStatus,
            AiReviewResult result,
            String trigger,
            String message,
            long nowEpochMillis
    ) {
        AiReviewRun current = require(runId);
        requireExpectedAndAllowed(current, expectedStatus, terminalStatus);
        if (terminalStatus == null || !terminalStatus.isTerminal()) {
            throw new IllegalStateException("AI review completion target must be terminal");
        }
        AiReviewRun updated = current.withResult(result, terminalStatus, nowEpochMillis);
        compareAndSet(current, updated);
        appendEvent(current, updated, trigger, message, "", nowEpochMillis);
        return updated;
    }

    @Override
    @Transactional
    public AiReviewRun retry(String terminalRunId, String nextRunId, long nowEpochMillis) {
        AiReviewRun parent = require(terminalRunId);
        if (!parent.status().isTerminal()) {
            throw new IllegalStateException("only terminal AI review run can be retried: " + terminalRunId);
        }
        Optional<AiReviewRun> existing = listByTask(parent.taskId()).stream()
                .filter(run -> parent.runId().equals(run.parentRunId()))
                .min(Comparator.comparingInt(AiReviewRun::attemptNo));
        if (existing.isPresent()) {
            return existing.get();
        }
        return create(AiReviewRun.created(nextRunId, parent.taskId(), parent.attemptNo() + 1,
                parent.runId(), parent.modelName(), nowEpochMillis));
    }

    @Override
    public List<AiReviewEvent> listEvents(String runId) {
        require(runId);
        return eventMapper.selectList(new QueryWrapper<AiReviewEventRow>()
                        .eq("run_id", PostgresPersistenceSupport.parseId(runId))
                        .orderByAsc("occurred_at", "id"))
                .stream().map(this::toEvent).toList();
    }

    @Override
    public AiReviewArtifact appendArtifact(AiReviewArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("AI review artifact must not be null");
        }
        require(artifact.runId());
        artifactMapper.insertArtifact(toArtifactRow(artifact));
        return artifact;
    }

    @Override
    public List<AiReviewArtifact> listArtifacts(String runId) {
        require(runId);
        return artifactMapper.selectList(new QueryWrapper<AiReviewArtifactRow>()
                        .eq("run_id", PostgresPersistenceSupport.parseId(runId))
                        .orderByAsc("created_at", "id"))
                .stream().map(this::toArtifact).toList();
    }

    private AiReviewRun require(String runId) {
        return find(runId).orElseThrow(() -> new NoSuchElementException("AI review run not found: " + runId));
    }

    private void requireExpectedAndAllowed(
            AiReviewRun current, AiReviewRunStatus expectedStatus, AiReviewRunStatus targetStatus
    ) {
        if (current.status() != expectedStatus) {
            throw new IllegalStateException("stale AI review status: expected " + expectedStatus
                    + " but was " + current.status());
        }
        if (current.status().isTerminal()
                || !ALLOWED.getOrDefault(current.status(), List.of()).contains(targetStatus)) {
            throw new IllegalStateException("illegal AI review transition: " + current.status() + " -> " + targetStatus);
        }
    }

    private void compareAndSet(AiReviewRun current, AiReviewRun updated) {
        if (runMapper.compareAndSet(toRow(updated, current.status(), current.version())) != 1) {
            throw new IllegalStateException("AI review compare-and-set failed: " + current.runId());
        }
    }

    private void appendEvent(
            AiReviewRun previous,
            AiReviewRun next,
            String trigger,
            String message,
            String errorCategory,
            long occurredAt
    ) {
        AiReviewEventRow row = new AiReviewEventRow();
        row.id = PostgresPersistenceSupport.parseId(idGenerator.nextIdString());
        row.runId = PostgresPersistenceSupport.parseId(next.runId());
        row.fromStatus = previous.status().name();
        row.toStatus = next.status().name();
        row.trigger = safe(trigger).isBlank() ? "SYSTEM" : safe(trigger);
        row.message = safe(message);
        row.errorCategory = safe(errorCategory);
        row.occurredAt = PostgresPersistenceSupport.toDateTime(occurredAt);
        eventMapper.insertEvent(row);
    }

    private AiReviewRunRow toRow(AiReviewRun run, AiReviewRunStatus expectedStatus, Long expectedVersion) {
        AiReviewRunRow row = new AiReviewRunRow();
        row.id = PostgresPersistenceSupport.parseId(run.runId());
        row.taskId = PostgresPersistenceSupport.parseId(run.taskId());
        row.attemptNo = run.attemptNo();
        row.parentRunId = nullableId(run.parentRunId());
        row.status = run.status().name();
        row.modelName = run.modelName();
        row.packageHash = run.packageHash();
        row.decision = run.decision() == null ? "" : run.decision().name();
        row.score = run.score();
        row.retryFromRole = run.retryFromRole();
        row.summary = run.summary();
        row.errorCategory = run.errorCategory();
        row.errorMessage = run.errorMessage();
        row.version = run.version();
        row.createdAt = PostgresPersistenceSupport.toDateTime(run.createdAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(run.updatedAtEpochMillis());
        row.expectedStatus = expectedStatus == null ? null : expectedStatus.name();
        row.expectedVersion = expectedVersion;
        return row;
    }

    private AiReviewRun toRun(AiReviewRunRow row) {
        return new AiReviewRun(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                row.attemptNo == null ? 1 : row.attemptNo,
                PostgresPersistenceSupport.idString(row.parentRunId),
                enumValue(AiReviewRunStatus.class, row.status, AiReviewRunStatus.CREATED),
                safe(row.modelName), safe(row.packageHash),
                enumValue(AiReviewDecision.class, row.decision, null),
                row.score == null ? 0 : row.score,
                safe(row.retryFromRole), safe(row.summary), safe(row.errorCategory), safe(row.errorMessage),
                row.version == null ? 0L : row.version,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private AiReviewEvent toEvent(AiReviewEventRow row) {
        return new AiReviewEvent(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.runId),
                enumValue(AiReviewRunStatus.class, row.fromStatus, AiReviewRunStatus.CREATED),
                enumValue(AiReviewRunStatus.class, row.toStatus, AiReviewRunStatus.CREATED),
                safe(row.trigger), safe(row.message), safe(row.errorCategory),
                PostgresPersistenceSupport.toEpochMillis(row.occurredAt)
        );
    }

    private AiReviewArtifactRow toArtifactRow(AiReviewArtifact artifact) {
        AiReviewArtifactRow row = new AiReviewArtifactRow();
        row.id = PostgresPersistenceSupport.parseId(artifact.artifactId());
        row.runId = PostgresPersistenceSupport.parseId(artifact.runId());
        row.artifactType = artifact.artifactType();
        row.artifactUri = artifact.artifactUri();
        row.contentPreview = boundedPreview(artifact.contentPreview());
        row.contentHash = artifact.contentHash();
        row.metadataJson = artifact.metadataJson();
        row.redacted = artifact.redacted();
        row.createdAt = PostgresPersistenceSupport.toDateTime(artifact.createdAtEpochMillis());
        return row;
    }

    private AiReviewArtifact toArtifact(AiReviewArtifactRow row) {
        return new AiReviewArtifact(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.runId),
                safe(row.artifactType), safe(row.artifactUri), safe(row.contentPreview), safe(row.contentHash),
                safe(row.metadataJson).isBlank() ? "{}" : row.metadataJson,
                Boolean.TRUE.equals(row.redacted), PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }

    private static Map<AiReviewRunStatus, List<AiReviewRunStatus>> allowedTransitions() {
        Map<AiReviewRunStatus, List<AiReviewRunStatus>> map = new EnumMap<>(AiReviewRunStatus.class);
        map.put(AiReviewRunStatus.CREATED, List.of(AiReviewRunStatus.PACKAGING, AiReviewRunStatus.CANCELLED));
        map.put(AiReviewRunStatus.PACKAGING, List.of(AiReviewRunStatus.REVIEWING,
                AiReviewRunStatus.FAILED_RETRYABLE, AiReviewRunStatus.CANCELLED));
        map.put(AiReviewRunStatus.REVIEWING, List.of(AiReviewRunStatus.VALIDATING,
                AiReviewRunStatus.FAILED_RETRYABLE, AiReviewRunStatus.CANCELLED));
        map.put(AiReviewRunStatus.VALIDATING, List.of(AiReviewRunStatus.SUCCEEDED_OK,
                AiReviewRunStatus.SUCCEEDED_NOT_OK, AiReviewRunStatus.SUCCEEDED_NEEDS_HUMAN,
                AiReviewRunStatus.FAILED_RETRYABLE, AiReviewRunStatus.CANCELLED));
        return Map.copyOf(map);
    }

    private static Long nullableId(String value) {
        String normalized = safe(value);
        return normalized.isBlank() ? null : PostgresPersistenceSupport.parseId(normalized);
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static String boundedPreview(String value) {
        String normalized = safe(value);
        return normalized.length() <= 20_000 ? normalized : normalized.substring(0, 20_000);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
