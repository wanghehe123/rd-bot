package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.HostVerificationArtifactRow;
import com.wish.rd.bootstrap.persistence.entity.HostVerificationRunRow;
import com.wish.rd.bootstrap.persistence.entity.HostVerificationStepRow;
import com.wish.rd.bootstrap.persistence.mapper.HostVerificationMapper;
import com.wish.rd.engine.requirement.verify.HostVerificationStore;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStep;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepName;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * PostgreSQL host verification store with CAS status transitions.
 *
 * <p>Enabled when {@code rd.knowledge.store=postgres}. Blank {@code parentRunId}
 * is stored as {@code 0} and read back as blank.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresHostVerificationStore implements HostVerificationStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final HostVerificationMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * Creates the PostgreSQL adapter.
     *
     * @param mapper       run/step/artifact mapper
     * @param objectMapper JSON codec for step command lists
     */
    public PostgresHostVerificationStore(HostVerificationMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public HostVerificationRun create(HostVerificationRun run) {
        if (run == null) {
            throw new IllegalArgumentException("host verification run must not be null");
        }
        if (find(run.runId()).isPresent()) {
            throw new IllegalStateException("host verification run already exists: " + run.runId());
        }
        boolean duplicateAttempt = listByTask(run.taskId()).stream()
                .anyMatch(existing -> existing.attemptNo() == run.attemptNo());
        if (duplicateAttempt) {
            throw new IllegalStateException(
                    "host verification attempt already exists: " + run.taskId() + "#" + run.attemptNo());
        }
        mapper.insertRun(toRow(run));
        return run;
    }

    @Override
    public Optional<HostVerificationRun> find(String runId) {
        String normalized = safe(runId);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findById(PostgresPersistenceSupport.parseId(normalized)))
                .map(this::toRun);
    }

    @Override
    public List<HostVerificationRun> listByTask(String taskId) {
        String normalized = safe(taskId);
        if (normalized.isBlank()) {
            return List.of();
        }
        return mapper.listByTaskId(PostgresPersistenceSupport.parseId(normalized)).stream()
                .map(this::toRun)
                .toList();
    }

    @Override
    @Transactional
    public HostVerificationRun transition(
            String runId,
            HostVerificationStatus expected,
            HostVerificationStatus target,
            String failureCategory,
            String errorMessage,
            long nowEpochMillis
    ) {
        HostVerificationRun current = require(runId);
        if (current.status() != expected) {
            throw new IllegalStateException("stale host verification status: expected " + expected
                    + " but was " + current.status());
        }
        if (current.status().isTerminal()) {
            throw new IllegalStateException("terminal host verification run is immutable: " + current.runId());
        }
        if (!current.status().canTransitionTo(target)) {
            throw new IllegalStateException(
                    "illegal host verification transition: " + current.status() + " -> " + target);
        }
        HostVerificationRun updated = current.withStatus(target, failureCategory, errorMessage, nowEpochMillis);
        if (mapper.compareAndSet(toRow(updated), expected.name()) != 1) {
            throw new IllegalStateException("stale host verification write: " + current.runId());
        }
        return updated;
    }

    @Override
    public void saveStep(HostVerificationStep step) {
        if (step == null) {
            throw new IllegalArgumentException("host verification step must not be null");
        }
        require(step.runId());
        mapper.upsertStep(toStepRow(step));
    }

    @Override
    public List<HostVerificationStep> listSteps(String runId) {
        require(runId);
        return mapper.listSteps(PostgresPersistenceSupport.parseId(runId)).stream()
                .map(this::toStep)
                .toList();
    }

    @Override
    public HostVerificationArtifact appendArtifact(HostVerificationArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("host verification artifact must not be null");
        }
        require(artifact.runId());
        boolean duplicate = listArtifacts(artifact.runId()).stream().anyMatch(existing ->
                existing.artifactId().equals(artifact.artifactId())
                        || existing.relativePath().equals(artifact.relativePath()));
        if (duplicate) {
            throw new IllegalStateException(
                    "host verification artifact already exists: " + artifact.runId() + "/" + artifact.relativePath());
        }
        mapper.insertArtifact(toArtifactRow(artifact));
        return artifact;
    }

    @Override
    public List<HostVerificationArtifact> listArtifacts(String runId) {
        require(runId);
        return mapper.listArtifacts(PostgresPersistenceSupport.parseId(runId)).stream()
                .map(this::toArtifact)
                .toList();
    }

    private HostVerificationRun require(String runId) {
        return find(runId).orElseThrow(() -> new NoSuchElementException("host verification run not found: " + runId));
    }

    private HostVerificationRunRow toRow(HostVerificationRun run) {
        HostVerificationRunRow row = new HostVerificationRunRow();
        row.id = PostgresPersistenceSupport.parseId(run.runId());
        row.taskId = PostgresPersistenceSupport.parseId(run.taskId());
        row.codingStageRunId = PostgresPersistenceSupport.parseId(run.codingStageRunId());
        row.parentRunId = sentinelId(run.parentRunId());
        row.attemptNo = run.attemptNo();
        row.status = run.status().name();
        row.docsOnly = run.docsOnly();
        row.failureCategory = run.failureCategory();
        row.errorMessage = run.errorMessage();
        row.remediationCount = run.remediationCount();
        row.createdAt = PostgresPersistenceSupport.toDateTime(run.createdAtEpochMillis());
        row.startedAt = PostgresPersistenceSupport.nullableDateTime(run.startedAtEpochMillis());
        row.finishedAt = PostgresPersistenceSupport.nullableDateTime(run.finishedAtEpochMillis());
        return row;
    }

    private HostVerificationRun toRun(HostVerificationRunRow row) {
        return new HostVerificationRun(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                PostgresPersistenceSupport.idString(row.codingStageRunId),
                fromSentinelId(row.parentRunId),
                row.attemptNo == null ? 1 : row.attemptNo,
                enumValue(HostVerificationStatus.class, row.status, HostVerificationStatus.CREATED),
                Boolean.TRUE.equals(row.docsOnly),
                safe(row.failureCategory),
                safe(row.errorMessage),
                row.remediationCount == null ? 0 : row.remediationCount,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.startedAt),
                PostgresPersistenceSupport.toEpochMillis(row.finishedAt)
        );
    }

    private HostVerificationStepRow toStepRow(HostVerificationStep step) {
        HostVerificationStepRow row = new HostVerificationStepRow();
        row.runId = PostgresPersistenceSupport.parseId(step.runId());
        row.step = step.step().name();
        row.status = step.status().name();
        row.commandsJson = writeCommands(step.commands());
        row.exitCode = step.exitCode();
        row.durationMillis = step.durationMillis();
        row.logArtifactId = sentinelId(step.logArtifactId());
        row.errorMessage = step.errorMessage();
        return row;
    }

    private HostVerificationStep toStep(HostVerificationStepRow row) {
        return new HostVerificationStep(
                PostgresPersistenceSupport.idString(row.runId),
                enumValue(HostVerificationStepName.class, row.step, HostVerificationStepName.BUILD),
                enumValue(HostVerificationStepStatus.class, row.status, HostVerificationStepStatus.PENDING),
                readCommands(row.commandsJson),
                row.exitCode,
                row.durationMillis == null ? 0L : row.durationMillis,
                fromSentinelId(row.logArtifactId),
                safe(row.errorMessage)
        );
    }

    private HostVerificationArtifactRow toArtifactRow(HostVerificationArtifact artifact) {
        HostVerificationArtifactRow row = new HostVerificationArtifactRow();
        row.id = PostgresPersistenceSupport.parseId(artifact.artifactId());
        row.taskId = PostgresPersistenceSupport.parseId(artifact.taskId());
        row.runId = PostgresPersistenceSupport.parseId(artifact.runId());
        row.artifactType = artifact.artifactType();
        row.relativePath = artifact.relativePath();
        row.objectUri = artifact.objectUri();
        row.contentType = artifact.contentType();
        row.sizeBytes = artifact.sizeBytes();
        row.sha256 = artifact.sha256();
        row.createdAt = PostgresPersistenceSupport.toDateTime(artifact.createdAtEpochMillis());
        return row;
    }

    private HostVerificationArtifact toArtifact(HostVerificationArtifactRow row) {
        return new HostVerificationArtifact(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                PostgresPersistenceSupport.idString(row.runId),
                safe(row.artifactType),
                safe(row.relativePath),
                safe(row.objectUri),
                safe(row.contentType),
                row.sizeBytes == null ? 0L : row.sizeBytes,
                safe(row.sha256),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }

    private String writeCommands(List<String> commands) {
        try {
            return objectMapper.writeValueAsString(commands == null ? List.of() : commands);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize host verification commands", exception);
        }
    }

    private List<String> readCommands(String json) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "[]" : json, STRING_LIST);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to deserialize host verification commands", exception);
        }
    }

    private static Long sentinelId(String value) {
        String normalized = safe(value);
        return normalized.isBlank() ? 0L : PostgresPersistenceSupport.parseId(normalized);
    }

    private static String fromSentinelId(Long id) {
        if (id == null || id == 0L) {
            return "";
        }
        return PostgresPersistenceSupport.idString(id);
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
