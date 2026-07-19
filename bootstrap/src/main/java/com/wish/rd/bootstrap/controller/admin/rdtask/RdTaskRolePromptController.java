package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务级多角色实际 Prompt 与角色上下文证据的只读审计接口。
 *
 * <p>这里仅返回阶段已归档的 {@code PROMPT_SNAPSHOT.contentPreview}，不会把任务级基线 Prompt
 * 伪装成任一角色的真实请求，也不会读取原始材料正文。
 */
@RestController
public final class RdTaskRolePromptController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PROMPT_SNAPSHOT = "PROMPT_SNAPSHOT";

    private final RagStreamTaskRegistry registry;
    private final AgentStageRunStore stageRunStore;
    private final AgentStageArtifactStore artifactStore;
    private final RoleContextPackageStore contextPackageStore;

    @Autowired
    public RdTaskRolePromptController(
            RagStreamTaskRegistry registry,
            ObjectProvider<AgentStageRunStore> stageRunStoreProvider,
            ObjectProvider<AgentStageArtifactStore> artifactStoreProvider,
            ObjectProvider<RoleContextPackageStore> contextPackageStoreProvider
    ) {
        this(
                registry,
                stageRunStoreProvider.getIfAvailable(InMemoryAgentStageRunStore::new),
                artifactStoreProvider.getIfAvailable(InMemoryAgentStageArtifactStore::new),
                contextPackageStoreProvider.getIfAvailable(InMemoryRoleContextPackageStore::new)
        );
    }

    public RdTaskRolePromptController(
            RagStreamTaskRegistry registry,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            RoleContextPackageStore contextPackageStore
    ) {
        this.registry = registry;
        this.stageRunStore = stageRunStore == null ? new InMemoryAgentStageRunStore() : stageRunStore;
        this.artifactStore = artifactStore == null ? new InMemoryAgentStageArtifactStore() : artifactStore;
        this.contextPackageStore = contextPackageStore == null
                ? new InMemoryRoleContextPackageStore()
                : contextPackageStore;
    }

    @GetMapping("/admin/rd-tasks/{taskId}/role-prompts")
    public RolePromptResponse list(@PathVariable("taskId") String taskId) {
        RdTask task = requireTask(taskId);
        List<AgentRole> stageOrder = stageOrder(task);
        Map<AgentRole, Integer> roleOrder = roleOrder(stageOrder);
        Map<String, AgentStageArtifact> artifactsById = artifactStore.listByTask(task.taskId()).stream()
                .collect(Collectors.toMap(
                        AgentStageArtifact::artifactId,
                        Function.identity(),
                        RdTaskRolePromptController::newestArtifact,
                        LinkedHashMap::new
                ));

        List<RolePromptStageView> stagePrompts = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> stageOrder.contains(stage.role()))
                .sorted(Comparator
                        .comparingInt((AgentStageRun stage) -> roleOrder.getOrDefault(stage.role(), 99))
                        .thenComparingInt(AgentStageRun::attemptNo)
                        .thenComparingLong(AgentStageRun::createTimeEpochMillis)
                        .thenComparing(AgentStageRun::stageRunId))
                .map(stage -> toStageView(stage, artifactsById))
                .toList();
        return new RolePromptResponse(task.taskId(), task.taskType(), stagePrompts);
    }

    private RolePromptStageView toStageView(
            AgentStageRun stage,
            Map<String, AgentStageArtifact> artifactsById
    ) {
        return new RolePromptStageView(
                stage.stageRunId(),
                stage.taskId(),
                stage.role().name(),
                stage.status().name(),
                stage.attemptNo(),
                stage.providerName(),
                toPromptView(stage, artifactsById.get(stage.promptArtifactId())),
                toContextView(stage)
        );
    }

    private PromptView toPromptView(AgentStageRun stage, AgentStageArtifact artifact) {
        if (stage.promptArtifactId().isBlank()) {
            return PromptView.unavailable("尚未生成实际角色 Prompt");
        }
        if (artifact == null
                || !PROMPT_SNAPSHOT.equals(artifact.artifactType())
                || !stage.stageRunId().equals(artifact.stageRunId())
                || stage.role() != artifact.role()) {
            return PromptView.unavailable("实际角色 Prompt 产物不可用");
        }
        String preview = artifact.contentPreview();
        if (preview.isBlank()) {
            return PromptView.unavailable("实际角色 Prompt 预览为空");
        }
        long contentLength = contentLength(artifact.metadataJson(), preview.length());
        return new PromptView(
                true,
                "",
                artifact.artifactId(),
                artifact.summary(),
                preview,
                artifact.contentHash(),
                contentLength,
                preview.length(),
                contentLength > preview.length(),
                artifact.createdAtEpochMillis()
        );
    }

    private ContextView toContextView(AgentStageRun stage) {
        if (stage.contextPackageId().isBlank()) {
            return ContextView.unavailable("尚未构建角色上下文");
        }
        RoleContextPackage contextPackage = contextPackageStore.findById(stage.contextPackageId())
                .filter(candidate -> stage.taskId().equals(candidate.taskId()))
                .filter(candidate -> stage.role().name().equals(candidate.role()))
                .orElse(null);
        if (contextPackage == null) {
            return ContextView.unavailable("角色上下文包不可用");
        }
        List<ContextEvidenceView> evidence = contextPackage.evidence().stream()
                .map(RdTaskRolePromptController::toEvidenceView)
                .toList();
        return new ContextView(
                true,
                "",
                contextPackage.packageId(),
                contextPackage.packageVersion(),
                contextPackage.retrievalRunId(),
                contextPackage.maxChars(),
                contextPackage.usedChars(),
                contextPackage.acceptanceCriteria(),
                contextPackage.riskHints(),
                evidence,
                contextPackage.omittedEvidenceIds(),
                contextPackage.createdAtEpochMillis()
        );
    }

    private static ContextEvidenceView toEvidenceView(RoleContextEvidence evidence) {
        return new ContextEvidenceView(
                evidence.evidenceId(),
                evidence.sourceType(),
                evidence.sourceUri(),
                evidence.title(),
                evidence.contentHash(),
                evidence.summary(),
                evidence.collectedAtEpochMillis(),
                evidence.selectionReason(),
                evidence.relevanceScore(),
                evidence.requiredEvidenceType(),
                evidence.sharedRoot()
        );
    }

    private RdTask requireTask(String taskId) {
        try {
            return registry.getTask(taskId);
        } catch (NoSuchElementException | NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private static List<AgentRole> stageOrder(RdTask task) {
        return "BUG_FIX".equals(task.taskType())
                ? AgentRole.bugFixOrder()
                : AgentRole.requirementDeliveryOrder();
    }

    private static Map<AgentRole, Integer> roleOrder(List<AgentRole> stageOrder) {
        Map<AgentRole, Integer> order = new LinkedHashMap<>();
        for (int index = 0; index < stageOrder.size(); index++) {
            order.put(stageOrder.get(index), index);
        }
        return order;
    }

    private static AgentStageArtifact newestArtifact(AgentStageArtifact left, AgentStageArtifact right) {
        if (left.createdAtEpochMillis() != right.createdAtEpochMillis()) {
            return left.createdAtEpochMillis() > right.createdAtEpochMillis() ? left : right;
        }
        return left.artifactId().compareTo(right.artifactId()) >= 0 ? left : right;
    }

    private static long contentLength(String metadataJson, int fallback) {
        try {
            JsonNode metadata = OBJECT_MAPPER.readTree(metadataJson);
            JsonNode length = metadata == null ? null : metadata.path("contentLength");
            if (length != null && length.canConvertToLong() && length.longValue() > 0L) {
                return length.longValue();
            }
        } catch (Exception ignored) {
            // Historical artifact metadata is optional and must not block audit access.
        }
        return Math.max(0, fallback);
    }

    public record RolePromptResponse(
            String taskId,
            String taskType,
            List<RolePromptStageView> stagePrompts
    ) {
    }

    public record RolePromptStageView(
            String stageRunId,
            String taskId,
            String role,
            String status,
            int attemptNo,
            String providerName,
            PromptView prompt,
            ContextView context
    ) {
    }

    public record PromptView(
            boolean available,
            String unavailableReason,
            String artifactId,
            String summary,
            String contentPreview,
            String contentHash,
            long contentLength,
            int previewLength,
            boolean truncated,
            long createdAtEpochMillis
    ) {
        private static PromptView unavailable(String reason) {
            return new PromptView(false, reason, "", "", "", "", 0L, 0, false, 0L);
        }
    }

    public record ContextView(
            boolean available,
            String unavailableReason,
            String packageId,
            int packageVersion,
            String retrievalRunId,
            int maxChars,
            int usedChars,
            List<String> acceptanceCriteria,
            List<String> riskHints,
            List<ContextEvidenceView> evidence,
            List<String> omittedEvidenceIds,
            long createdAtEpochMillis
    ) {
        private static ContextView unavailable(String reason) {
            return new ContextView(false, reason, "", 0, "", 0, 0, List.of(), List.of(), List.of(), List.of(), 0L);
        }
    }

    public record ContextEvidenceView(
            String evidenceId,
            String sourceType,
            String sourceUri,
            String title,
            String contentHash,
            String summary,
            long collectedAtEpochMillis,
            String selectionReason,
            double relevanceScore,
            String requiredEvidenceType,
            boolean sharedRoot
    ) {
    }
}
