package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageArtifactRow;
import com.wish.rd.bootstrap.persistence.entity.RdQaEvidenceObjectRow;
import com.wish.rd.bootstrap.persistence.mapper.RdAgentStageArtifactMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdQaEvidenceObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * PostgreSQL Agent 阶段产物存储适配器。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresAgentStageArtifactStore implements AgentStageArtifactStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> QA_EVIDENCE_TYPES = Set.of(
            "QA_COMMAND_LOG",
            "QA_SCREENSHOT",
            "QA_TRACE",
            "QA_CONSOLE_LOG",
            "QA_NETWORK_LOG",
            "QA_HTTP_TRANSCRIPT",
            "QA_VIDEO",
            "QA_EVIDENCE_MANIFEST"
    );

    private final RdAgentStageArtifactMapper mapper;
    private final RdQaEvidenceObjectMapper evidenceMapper;

    public PostgresAgentStageArtifactStore(RdAgentStageArtifactMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public PostgresAgentStageArtifactStore(
            RdAgentStageArtifactMapper mapper,
            RdQaEvidenceObjectMapper evidenceMapper
    ) {
        this.mapper = mapper;
        this.evidenceMapper = evidenceMapper;
    }

    @Override
    public AgentStageArtifact save(AgentStageArtifact artifact) {
        mapper.upsertStageArtifact(toRow(artifact));
        if (evidenceMapper != null && isPrivateQaEvidence(artifact)) {
            evidenceMapper.upsertEvidenceObject(toEvidenceRow(artifact));
        }
        return artifact;
    }

    @Override
    public AgentStageArtifact saveImmutable(AgentStageArtifact artifact) {
        RdAgentStageArtifactRow row = toRow(artifact);
        int inserted = mapper.insertStageArtifactIgnoringConflict(row);
        RdAgentStageArtifactRow existing = mapper.selectById(row.id);
        if (existing == null) {
            throw new IllegalStateException("immutable artifact missing after insert: " + artifact.artifactId());
        }
        AgentStageArtifact existingArtifact = toArtifact(existing);
        AgentStageArtifactStore.assertImmutableCompatible(existingArtifact, artifact);
        if (inserted > 0 && evidenceMapper != null && isPrivateQaEvidence(artifact)) {
            evidenceMapper.upsertEvidenceObject(toEvidenceRow(artifact));
        }
        return existingArtifact;
    }

    @Override
    public List<AgentStageArtifact> listByTask(String taskId) {
        return mapper.selectByTaskOmittingPrivateQaPreviews(PostgresPersistenceSupport.parseId(taskId))
                .stream()
                .sorted(Comparator
                        .comparing((RdAgentStageArtifactRow row) -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toArtifact)
                .toList();
    }

    @Override
    public List<AgentStageArtifact> listByTaskStageAndType(String taskId, String stageRunId, String artifactType) {
        String type = artifactType == null ? "" : artifactType.strip();
        if (type.isBlank() || stageRunId == null || stageRunId.isBlank()) {
            return List.of();
        }
        return mapper.selectList(new QueryWrapper<RdAgentStageArtifactRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId))
                        .eq("stage_run_id", PostgresPersistenceSupport.parseId(stageRunId))
                        .eq("artifact_type", type))
                .stream()
                .sorted(Comparator
                        .comparing((RdAgentStageArtifactRow row) -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toArtifact)
                .toList();
    }

    @Override
    public int deleteByTaskAndTypes(String taskId, Set<String> artifactTypes) {
        if (artifactTypes == null || artifactTypes.isEmpty()) {
            return 0;
        }
        long parsedTaskId = PostgresPersistenceSupport.parseId(taskId);
        if (evidenceMapper != null) {
            evidenceMapper.delete(new QueryWrapper<RdQaEvidenceObjectRow>().eq("task_id", parsedTaskId));
        }
        return mapper.delete(new QueryWrapper<RdAgentStageArtifactRow>()
                .eq("task_id", parsedTaskId)
                .in("artifact_type", artifactTypes));
    }

    private RdAgentStageArtifactRow toRow(AgentStageArtifact artifact) {
        RdAgentStageArtifactRow row = new RdAgentStageArtifactRow();
        row.id = PostgresPersistenceSupport.parseId(artifact.artifactId());
        row.stageRunId = PostgresPersistenceSupport.parseId(artifact.stageRunId());
        row.taskId = PostgresPersistenceSupport.parseId(artifact.taskId());
        row.role = artifact.role().name();
        row.artifactType = artifact.artifactType();
        row.artifactUri = artifact.artifactUri();
        row.summary = artifact.summary();
        row.contentPreview = artifact.contentPreview();
        row.contentHash = artifact.contentHash();
        row.metadataJson = artifact.metadataJson();
        row.createdAt = PostgresPersistenceSupport.toDateTime(artifact.createdAtEpochMillis());
        return row;
    }

    private AgentStageArtifact toArtifact(RdAgentStageArtifactRow row) {
        return new AgentStageArtifact(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.stageRunId),
                PostgresPersistenceSupport.idString(row.taskId),
                AgentRole.valueOf(row.role),
                row.artifactType,
                row.artifactUri,
                row.summary,
                row.contentPreview,
                row.contentHash,
                row.metadataJson,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }

    private boolean isPrivateQaEvidence(AgentStageArtifact artifact) {
        return artifact != null
                && QA_EVIDENCE_TYPES.contains(artifact.artifactType())
                && artifact.artifactUri().startsWith("s3://");
    }

    private RdQaEvidenceObjectRow toEvidenceRow(AgentStageArtifact artifact) {
        JsonNode metadata;
        try {
            metadata = OBJECT_MAPPER.readTree(artifact.metadataJson());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid QA evidence metadata JSON", exception);
        }
        String sha256 = metadata.path("sha256").asText("").strip();
        if (sha256.startsWith("sha256:")) {
            sha256 = sha256.substring("sha256:".length());
        }
        long sizeBytes = parsePositiveLong(metadata.path("bytes").asText(""));
        if (!sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("QA evidence sha256 metadata is invalid");
        }
        RdQaEvidenceObjectRow row = new RdQaEvidenceObjectRow();
        row.taskId = PostgresPersistenceSupport.parseId(artifact.taskId());
        row.stageRunId = PostgresPersistenceSupport.parseId(artifact.stageRunId());
        row.artifactId = PostgresPersistenceSupport.parseId(artifact.artifactId());
        row.artifactType = artifact.artifactType();
        row.artifactName = firstNonBlank(metadata.path("artifactName").asText(""), artifact.summary());
        row.objectUri = artifact.artifactUri();
        row.contentType = firstNonBlank(
                metadata.path("contentType").asText(""), "application/octet-stream");
        row.sizeBytes = sizeBytes;
        row.sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
        row.createdAt = PostgresPersistenceSupport.toDateTime(artifact.createdAtEpochMillis());
        row.expiresAt = null;
        return row;
    }

    private static long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0L) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Normalized into a stable validation error below.
        }
        throw new IllegalStateException("QA evidence bytes metadata is invalid");
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.strip();
    }
}
