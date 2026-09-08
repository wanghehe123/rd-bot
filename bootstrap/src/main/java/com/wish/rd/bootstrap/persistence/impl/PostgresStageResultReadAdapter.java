package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageFinalizationRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageFinalizationMapper;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateStore;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.query.CodingMeaNotFoundException;
import com.wish.rd.engine.requirement.query.StageResultReadPort;
import com.wish.rd.engine.requirement.query.StageResultSnapshot;
import com.wish.rd.engine.retry.TaskRetryAttemptBindingStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * PostgreSQL read adapter for one stage result.
 *
 * <p>Must not be {@code final}: {@code @Transactional} uses CGLIB subclassing.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresStageResultReadAdapter implements StageResultReadPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RagStreamTaskRegistry registry;
    private final AgentStageRunStore stageRunStore;
    private final AuditedTaskStateStore auditedTaskStateStore;
    private final RequirementStageCommandStore commandStore;
    private final TaskRetryAttemptBindingStore retryBindingStore;
    private final RequirementStageFinalizationMapper finalizationMapper;
    private final AgentStageArtifactStore artifactStore;

    /**
     * Creates the read-only adapter.
     */
    public PostgresStageResultReadAdapter(
            RagStreamTaskRegistry registry,
            AgentStageRunStore stageRunStore,
            AuditedTaskStateStore auditedTaskStateStore,
            RequirementStageCommandStore commandStore,
            ObjectProvider<TaskRetryAttemptBindingStore> retryBindingStore,
            RequirementStageFinalizationMapper finalizationMapper,
            ObjectProvider<AgentStageArtifactStore> artifactStore
    ) {
        this.registry = registry;
        this.stageRunStore = stageRunStore;
        this.auditedTaskStateStore = auditedTaskStateStore;
        this.commandStore = commandStore;
        this.retryBindingStore = retryBindingStore.getIfAvailable();
        this.finalizationMapper = finalizationMapper;
        this.artifactStore = artifactStore.getIfAvailable();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StageResultSnapshot read(String taskId, String stageRunId) {
        RdRequirementTask task = requireRequirementTask(taskId);
        AgentStageRun stage = stageRunStore.listByTask(task.taskId()).stream()
                .filter(candidate -> task.taskId().equals(candidate.taskId()))
                .filter(candidate -> candidate.stageRunId().equals(stageRunId == null ? "" : stageRunId.strip()))
                .findFirst()
                .orElseThrow(() -> new CodingMeaNotFoundException("stage run not found"));
        String commandId = bindCommandId(task.taskId(), stage.stageRunId());
        String finalizedJson = "";
        String finalizationId = "";
        if (!commandId.isBlank()) {
            RequirementStageFinalizationRow row = finalizationMapper.findLatestFinalized(
                    PostgresPersistenceSupport.parseId(task.taskId()),
                    PostgresPersistenceSupport.parseId(commandId));
            if (row != null && task.taskId().equals(PostgresPersistenceSupport.idString(row.taskId))) {
                finalizedJson = row.resultJson == null ? "" : row.resultJson;
                finalizationId = commandId + ":" + row.attemptNo;
            }
        }
        AgentStageArtifact artifact = loadResultArtifact(task.taskId(), stage);
        String preview = artifact == null ? "" : safe(artifact.contentPreview());
        boolean truncated = artifact != null && previewTruncated(artifact, preview);
        return new StageResultSnapshot(
                stage,
                commandId,
                finalizationId,
                finalizedJson,
                artifact == null ? stage.resultArtifactId() : artifact.artifactId(),
                preview,
                truncated
        );
    }

    private String bindCommandId(String taskId, String stageRunId) {
        List<AuditRun> matches = auditedTaskStateStore.listAuditRuns(taskId).stream()
                .filter(run -> taskId.equals(run.taskId()))
                .filter(run -> stageRunId.equals(run.subjectStageRunId()))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst().commandId();
        }
        if (matches.size() > 1) {
            return "";
        }
        if (retryBindingStore == null) {
            return "";
        }
        Optional<String> bindingId = retryBindingStore.findByStageRunId(stageRunId).map(binding -> binding.bindingId());
        if (bindingId.isEmpty()) {
            return "";
        }
        List<RequirementStageCommand> commands = commandStore.listByTargetRetryBinding(taskId, bindingId.get());
        if (commands.size() != 1) {
            return "";
        }
        return commands.getFirst().commandId();
    }

    private AgentStageArtifact loadResultArtifact(String taskId, AgentStageRun stage) {
        if (artifactStore == null) {
            return null;
        }
        List<AgentStageArtifact> typed = artifactStore.listByTaskStageAndType(
                taskId, stage.stageRunId(), "RESULT_JSON");
        if (!stage.resultArtifactId().isBlank()) {
            return typed.stream()
                    .filter(artifact -> stage.resultArtifactId().equals(artifact.artifactId()))
                    .findFirst()
                    .orElse(typed.isEmpty() ? null : typed.getFirst());
        }
        return typed.isEmpty() ? null : typed.getFirst();
    }

    private boolean previewTruncated(AgentStageArtifact artifact, String preview) {
        long stored = contentLength(artifact.metadataJson());
        return stored > preview.length();
    }

    private long contentLength(String metadataJson) {
        try {
            JsonNode node = MAPPER.readTree(metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson);
            JsonNode length = node.get("contentLength");
            return length == null || !length.canConvertToLong() ? 0L : length.longValue();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private RdRequirementTask requireRequirementTask(String taskId) {
        RdTask task;
        try {
            task = registry.getTask(taskId);
        } catch (NoSuchElementException missing) {
            throw new CodingMeaNotFoundException("task not found");
        }
        if (!(task instanceof RdRequirementTask requirementTask)) {
            throw new CodingMeaNotFoundException("stage result is requirement-only");
        }
        return requirementTask;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
