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
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.AgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
import com.wish.rd.rag.project.agent.model.AgentStageStateProjection;
import com.wish.rd.rag.project.agent.model.AgentStateV2Codec;
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
import java.time.Instant;
import java.util.function.Function;
import java.util.function.LongSupplier;
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
    private static final String AGENT_STATE_SNAPSHOT = "AGENT_STATE_SNAPSHOT";
    private static final String AGENT_EFFECTIVE_CONTEXT = "AGENT_EFFECTIVE_CONTEXT";
    private static final long STATE_PROJECTION_STALE_AFTER_MILLIS = 30_000L;
    private static final int AUDIT_PREVIEW_MAX_CHARS = 32_768;

    private final RagStreamTaskRegistry registry;
    private final AgentStageRunStore stageRunStore;
    private final AgentStageArtifactStore artifactStore;
    private final RoleContextPackageStore contextPackageStore;
    private final AgentExecutionProfileSnapshotStore profileSnapshotStore;
    private final AgentStageStateProjectionStore stateProjectionStore;
    private final LongSupplier nowMillis;

    @Autowired
    public RdTaskRolePromptController(
            RagStreamTaskRegistry registry,
            ObjectProvider<AgentStageRunStore> stageRunStoreProvider,
            ObjectProvider<AgentStageArtifactStore> artifactStoreProvider,
            ObjectProvider<RoleContextPackageStore> contextPackageStoreProvider,
            ObjectProvider<AgentExecutionProfileSnapshotStore> profileSnapshotStoreProvider,
            ObjectProvider<AgentStageStateProjectionStore> stateProjectionStoreProvider
    ) {
        this(
                registry,
                stageRunStoreProvider.getIfAvailable(InMemoryAgentStageRunStore::new),
                artifactStoreProvider.getIfAvailable(InMemoryAgentStageArtifactStore::new),
                contextPackageStoreProvider.getIfAvailable(InMemoryRoleContextPackageStore::new),
                profileSnapshotStoreProvider.getIfAvailable(InMemoryAgentExecutionProfileSnapshotStore::new),
                stateProjectionStoreProvider.getIfAvailable(InMemoryAgentStageStateProjectionStore::new),
                System::currentTimeMillis
        );
    }

    public RdTaskRolePromptController(
            RagStreamTaskRegistry registry,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            RoleContextPackageStore contextPackageStore
    ) {
        this(
                registry,
                stageRunStore,
                artifactStore,
                contextPackageStore,
                new InMemoryAgentExecutionProfileSnapshotStore(),
                new InMemoryAgentStageStateProjectionStore(),
                System::currentTimeMillis
        );
    }

    RdTaskRolePromptController(
            RagStreamTaskRegistry registry,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            RoleContextPackageStore contextPackageStore,
            AgentExecutionProfileSnapshotStore profileSnapshotStore,
            AgentStageStateProjectionStore stateProjectionStore,
            LongSupplier nowMillis
    ) {
        this.registry = registry;
        this.stageRunStore = stageRunStore == null ? new InMemoryAgentStageRunStore() : stageRunStore;
        this.artifactStore = artifactStore == null ? new InMemoryAgentStageArtifactStore() : artifactStore;
        this.contextPackageStore = contextPackageStore == null
                ? new InMemoryRoleContextPackageStore()
                : contextPackageStore;
        this.profileSnapshotStore = profileSnapshotStore == null
                ? new InMemoryAgentExecutionProfileSnapshotStore()
                : profileSnapshotStore;
        this.stateProjectionStore = stateProjectionStore == null
                ? new InMemoryAgentStageStateProjectionStore()
                : stateProjectionStore;
        this.nowMillis = nowMillis == null ? System::currentTimeMillis : nowMillis;
    }

    @GetMapping("/admin/rd-tasks/{taskId}/role-prompts")
    public RolePromptResponse list(@PathVariable("taskId") String taskId) {
        RdTask task = requireTask(taskId);
        List<AgentRole> stageOrder = stageOrder(task);
        Map<AgentRole, Integer> roleOrder = roleOrder(stageOrder);
        List<AgentStageArtifact> artifacts = artifactStore.listByTask(task.taskId());
        Map<String, AgentStageArtifact> artifactsById = artifacts.stream()
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
                .map(stage -> toStageView(stage, artifactsById, artifacts))
                .toList();
        return new RolePromptResponse(task.taskId(), task.taskType(), stagePrompts);
    }

    private RolePromptStageView toStageView(
            AgentStageRun stage,
            Map<String, AgentStageArtifact> artifactsById,
            List<AgentStageArtifact> artifacts
    ) {
        AgentExecutionProfileSnapshot profile = matchingProfile(stage);
        String runtimeType = profile == null ? "" : profile.runtimeType().name();
        String capabilityFailure = stateCapabilityFailure(profile);
        AgentStageStateProjection projection = matchingProjection(stage);
        List<AgentStageArtifact> stageArtifacts = artifacts.stream()
                .filter(artifact -> matchesStage(stage, artifact))
                .toList();
        LatestStateView latestState = capabilityFailure.isBlank()
                ? toLatestStateView(stage, projection, stageArtifacts)
                : LatestStateView.unavailable(capabilityFailure);
        EffectiveContextView effectiveContext = capabilityFailure.isBlank()
                ? toEffectiveContextView(
                        stage,
                        toPromptView(stage, artifactsById.get(stage.promptArtifactId())),
                        projection,
                        stageArtifacts,
                        latestState
                )
                : EffectiveContextView.unavailable(capabilityFailure);
        return new RolePromptStageView(
                stage.stageRunId(),
                stage.taskId(),
                stage.role().name(),
                stage.status().name(),
                stage.attemptNo(),
                stage.providerName(),
                runtimeType,
                toPromptView(stage, artifactsById.get(stage.promptArtifactId())),
                toContextView(stage),
                latestState,
                effectiveContext
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

    private AgentExecutionProfileSnapshot matchingProfile(AgentStageRun stage) {
        return profileSnapshotStore.findByStageRunId(stage.stageRunId())
                .filter(AgentExecutionProfileSnapshot::hasValidIntegrityHash)
                .filter(snapshot -> stage.taskId().equals(snapshot.taskId()))
                .filter(snapshot -> stage.role().name().equals(snapshot.role()))
                .filter(snapshot -> stage.attemptNo() == snapshot.attemptNo())
                .orElse(null);
    }

    private String stateCapabilityFailure(AgentExecutionProfileSnapshot profile) {
        if (profile == null) {
            return "未冻结执行 Profile，PI 状态栏不可用";
        }
        if (!"PI".equals(profile.runtimeType().name())) {
            return "当前 Attempt 不是 PI Runtime，状态栏不适用";
        }
        try {
            if (!profile.hasCapability(AgentRuntimeCapability.PI_AGENT_STATE_V2)) {
                return "当前 Attempt 未启用 PI_AGENT_STATE_V2 capability";
            }
        } catch (RuntimeException invalidSnapshot) {
            return "冻结执行 Profile capability 无效";
        }
        return "";
    }

    private AgentStageStateProjection matchingProjection(AgentStageRun stage) {
        return stateProjectionStore.findByStageRunId(stage.stageRunId())
                .filter(projection -> stage.taskId().equals(projection.identity().taskId()))
                .filter(projection -> stage.stageRunId().equals(projection.identity().stageRunId()))
                .filter(projection -> stage.role().name().equals(projection.identity().role()))
                .filter(projection -> stage.attemptNo() == projection.identity().attemptNo())
                .orElse(null);
    }

    private LatestStateView toLatestStateView(
            AgentStageRun stage,
            AgentStageStateProjection projection,
            List<AgentStageArtifact> stageArtifacts
    ) {
        if (projection != null && projection.stateSequence() >= 0 && !projection.stateJson().isBlank()) {
            try {
                JsonNode state = AgentStateV2Codec.decodeAndVerify(projection.stateJson(), projection.stateHash());
                requireStateIdentity(stage, state, projection.stateSequence());
                return latestStateView(
                        stage, state, projection.stateHash(), "", "LIVE_PROJECTION",
                        projection.finalized(), projection.stateProjectedAtEpochMillis(), false
                );
            } catch (RuntimeException ignored) {
                return LatestStateView.unavailable("live state projection 未通过 identity/hash 校验");
            }
        }
        AgentStageArtifact artifact = newestStageArtifact(stageArtifacts, AGENT_STATE_SNAPSHOT);
        if (artifact == null || artifact.contentPreview().isBlank()) {
            return LatestStateView.unavailable("当前 Attempt 尚无最新状态快照");
        }
        try {
            JsonNode state = AgentStateV2Codec.decodeAndVerify(artifact.contentPreview(), artifact.contentHash());
            requireStateIdentity(stage, state, state.path("sequence").asLong(-1L));
            return latestStateView(
                    stage, state, artifact.contentHash(), artifact.artifactId(), "ARCHIVED_ARTIFACT",
                    stage.status().isTerminal(), artifact.createdAtEpochMillis(), agentStatePreviewTruncated(artifact)
            );
        } catch (RuntimeException ignored) {
            return LatestStateView.unavailable("归档状态产物未通过 identity/hash 校验");
        }
    }

    private LatestStateView latestStateView(
            AgentStageRun stage,
            JsonNode state,
            String stateHash,
            String artifactId,
            String source,
            boolean finalized,
            long projectedAt,
            boolean previewTruncated
    ) {
        Freshness freshness = freshness(finalized, projectedAt);
        return new LatestStateView(
                true, "", source, finalized, freshness.stale(), freshness.reason(), projectedAt,
                STATE_PROJECTION_STALE_AFTER_MILLIS, artifactId, stage.stageRunId(), stage.taskId(),
                stage.role().name(), stage.attemptNo(), state.path("protocol").asText(""),
                state.path("sequence").asLong(), state.path("generatedAtEpochMillis").asLong(),
                state.path("currentGoal").asText(""), state.path("phase").asText(""),
                epochText(state.path("taskStartedAtEpochMillis").asLong()),
                epochText(state.path("stageStartedAtEpochMillis").asLong()),
                state.path("resultStatus").asText(""), state.path("blocker").asText(""),
                stateBudget(state.path("budget")), stateTodos(state.path("todos")),
                recentErrors(state.path("recentErrors")), stateHash, previewTruncated
        );
    }

    private EffectiveContextView toEffectiveContextView(
            AgentStageRun stage,
            PromptView prompt,
            AgentStageStateProjection projection,
            List<AgentStageArtifact> stageArtifacts,
            LatestStateView latestState
    ) {
        if (!prompt.available()) {
            return EffectiveContextView.unavailable("实际角色 Prompt 不可用，无法组成有效上下文");
        }
        if (projection != null && projection.injectionSequence() > 0L) {
            try {
                validateLiveInjection(prompt, projection);
                return effectiveContextView(
                        stage, prompt, projection.injectionSequence(), projection.injectedStateSequence(),
                        projection.injectedStateHash(), projection.promptHash(), projection.injectedBlockHash(),
                        projection.injectedBlock(), projection.injectedAtEpochMillis(), "LIVE_PROJECTION",
                        projection.finalized(), "", latestState
                );
            } catch (RuntimeException ignored) {
                return EffectiveContextView.unavailable("live effective context 未通过 identity/hash 校验");
            }
        }
        AgentStageArtifact artifact = newestStageArtifact(stageArtifacts, AGENT_EFFECTIVE_CONTEXT);
        if (artifact == null || artifact.contentPreview().isBlank()) {
            return EffectiveContextView.unavailable(
                    latestState.available() ? "最新状态尚未发生真实上下文注入" : "当前 Attempt 尚无有效上下文注入记录"
            );
        }
        try {
            JsonNode value = OBJECT_MAPPER.readTree(artifact.contentPreview());
            validateArchivedInjection(stage, prompt, value);
            return effectiveContextView(
                    stage, prompt, value.path("injectionSequence").asLong(),
                    value.path("stateSequence").asLong(), value.path("stateHash").asText(),
                    value.path("promptHash").asText(), value.path("blockHash").asText(),
                    value.path("injectedBlock").asText(), parseEpoch(value.path("injectedAt").asText()),
                    "ARCHIVED_ARTIFACT", stage.status().isTerminal(), artifact.artifactId(), latestState
            );
        } catch (Exception ignored) {
            return EffectiveContextView.unavailable("归档 effective context 未通过 identity/hash 校验");
        }
    }

    private EffectiveContextView effectiveContextView(
            AgentStageRun stage,
            PromptView prompt,
            long injectionSequence,
            long stateSequence,
            String stateHash,
            String promptHash,
            String blockHash,
            String injectedBlock,
            long injectedAt,
            String source,
            boolean finalized,
            String artifactId,
            LatestStateView latestState
    ) {
        String content = prompt.contentPreview() + "\n\n" + injectedBlock;
        String preview = content.length() <= AUDIT_PREVIEW_MAX_CHARS
                ? content : content.substring(0, AUDIT_PREVIEW_MAX_CHARS);
        Freshness freshness = freshness(finalized, injectedAt);
        return new EffectiveContextView(
                true, "", source, finalized, freshness.stale(), freshness.reason(), injectedAt,
                STATE_PROJECTION_STALE_AFTER_MILLIS, artifactId, stage.stageRunId(), stage.taskId(),
                stage.role().name(), stage.attemptNo(), "rd-agent-effective-context/v1",
                List.of("PROMPT_SNAPSHOT", "AGENT_STATE_BLOCK"), injectionSequence,
                prompt.artifactId(), promptHash, "", stateSequence, stateHash, blockHash,
                preview, sha256(content), content.length(), preview.length(), preview.length() < content.length(),
                injectedAt, latestState.available() && latestState.sequence() > stateSequence
        );
    }

    private static void validateLiveInjection(PromptView prompt, AgentStageStateProjection projection) {
        if (!projection.promptHash().equals(prompt.contentHash())) {
            throw new IllegalArgumentException("prompt hash mismatch");
        }
        if (!sha256(projection.injectedBlock()).equals(projection.injectedBlockHash())) {
            throw new IllegalArgumentException("injected block hash mismatch");
        }
    }

    private static void validateArchivedInjection(AgentStageRun stage, PromptView prompt, JsonNode value) {
        if (value == null || !value.isObject()
                || !"rd-agent-effective-context/v1".equals(value.path("protocol").asText())
                || !stage.taskId().equals(value.path("taskId").asText())
                || !stage.stageRunId().equals(value.path("stageRunId").asText())
                || !stage.role().name().equals(value.path("role").asText())
                || stage.attemptNo() != value.path("attemptNo").asInt()
                || value.path("injectionSequence").asLong() < 1L
                || !prompt.contentHash().equals(value.path("promptHash").asText())
                || !sha256(value.path("injectedBlock").asText()).equals(value.path("blockHash").asText())) {
            throw new IllegalArgumentException("archived effective context invalid");
        }
    }

    private static void requireStateIdentity(AgentStageRun stage, JsonNode state, long sequence) {
        if (!stage.taskId().equals(state.path("taskId").asText())
                || !stage.stageRunId().equals(state.path("stageRunId").asText())
                || !stage.role().name().equals(state.path("role").asText())
                || stage.attemptNo() != state.path("attemptNo").asInt()
                || sequence != state.path("sequence").asLong(-1L)) {
            throw new IllegalArgumentException("state identity mismatch");
        }
    }

    private Freshness freshness(boolean finalized, long projectedAt) {
        if (finalized) return new Freshness(false, "");
        if (projectedAt <= 0L) return new Freshness(true, "缺少最后投影时间");
        boolean stale = nowMillis.getAsLong() - projectedAt > STATE_PROJECTION_STALE_AFTER_MILLIS;
        return new Freshness(stale, stale ? "active projection 超过 stale threshold 30000ms" : "");
    }

    private static AgentStageArtifact newestStageArtifact(List<AgentStageArtifact> artifacts, String type) {
        return artifacts.stream()
                .filter(artifact -> type.equals(artifact.artifactType()))
                .max(RdTaskRolePromptController::compareArtifact)
                .orElse(null);
    }

    private static boolean matchesStage(AgentStageRun stage, AgentStageArtifact artifact) {
        return stage.taskId().equals(artifact.taskId())
                && stage.stageRunId().equals(artifact.stageRunId())
                && stage.role() == artifact.role();
    }

    private static int compareArtifact(AgentStageArtifact left, AgentStageArtifact right) {
        int created = Long.compare(left.createdAtEpochMillis(), right.createdAtEpochMillis());
        return created != 0 ? created : left.artifactId().compareTo(right.artifactId());
    }

    private static boolean agentStatePreviewTruncated(AgentStageArtifact artifact) {
        return contentLength(artifact.metadataJson(), artifact.contentPreview().length())
                > artifact.contentPreview().length();
    }

    private static long parseEpoch(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String epochText(long epochMillis) {
        try {
            return epochMillis > 0L ? Instant.ofEpochMilli(epochMillis).toString() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String sha256(String value) {
        return "sha256:" + AgentExecutionProfileSnapshot.sha256(value == null ? "" : value);
    }

    private static StateBudgetView stateBudget(JsonNode budget) {
        return new StateBudgetView(
                budget.path("availability").asText(""), budget.path("model").asText(""),
                nullableLong(budget.get("maxContextTokens")), nullableLong(budget.get("reservedOutputTokens")),
                nullableLong(budget.get("estimatedInputTokens")), budget.path("estimatorVersion").asText("")
        );
    }

    private static List<StateTodoView> stateTodos(JsonNode todos) {
        if (!todos.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(todos.spliterator(), false)
                .map(todo -> new StateTodoView(
                        todo.path("todoId").asText(""), todo.path("owner").asText(""),
                        todo.path("kind").asText(""), todo.path("title").asText(""),
                        todo.path("status").asText(""), todo.path("required").asBoolean(false),
                        todo.path("acceptanceCriteriaId").asText(""),
                        todo.path("acceptanceContentHash").asText(""), textList(todo.path("evidenceArtifactIds"))
                ))
                .toList();
    }

    private static List<RecentErrorView> recentErrors(JsonNode errors) {
        if (!errors.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(errors.spliterator(), false)
                .limit(8)
                .map(error -> new RecentErrorView(
                        error.path("toolName").asText(""),
                        error.has("summary") ? error.path("summary").asText("") : error.path("error").asText(""),
                        error.path("fingerprint").asText(""),
                        error.has("timestamp") ? error.path("timestamp").asText("") : error.path("at").asText("")
                ))
                .toList();
    }

    private static List<String> textList(JsonNode node) {
        if (!node.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .toList();
    }

    private static Long nullableLong(JsonNode node) {
        return node != null && node.canConvertToLong() ? node.longValue() : null;
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
            String runtimeType,
            PromptView prompt,
            ContextView context,
            LatestStateView latestState,
            EffectiveContextView effectiveContext
    ) {
    }

    public record LatestStateView(
            boolean available,
            String unavailableReason,
            String source,
            boolean finalized,
            boolean stale,
            String staleReason,
            long lastProjectionAtEpochMillis,
            long staleAfterMillis,
            String artifactId,
            String stageRunId,
            String taskId,
            String role,
            int attemptNo,
            String protocol,
            long sequence,
            long generatedAtEpochMillis,
            String currentGoal,
            String phase,
            String taskStartedAt,
            String stageStartedAt,
            String resultStatus,
            String blocker,
            StateBudgetView budget,
            List<StateTodoView> todos,
            List<RecentErrorView> recentErrors,
            String contentHash,
            boolean previewTruncated
    ) {
        private static LatestStateView unavailable(String reason) {
            return new LatestStateView(
                    false, reason, "", false, false, "", 0L, STATE_PROJECTION_STALE_AFTER_MILLIS,
                    "", "", "", "", 0, "", 0L, 0L, "", "", "", "", "", "",
                    StateBudgetView.empty(), List.of(), List.of(), "", false
            );
        }
    }

    public record EffectiveContextView(
            boolean available,
            String unavailableReason,
            String source,
            boolean finalized,
            boolean stale,
            String staleReason,
            long lastProjectionAtEpochMillis,
            long staleAfterMillis,
            String artifactId,
            String stageRunId,
            String taskId,
            String role,
            int attemptNo,
            String protocol,
            List<String> compositionOrder,
            long injectionSequence,
            String promptArtifactId,
            String promptContentHash,
            String stateArtifactId,
            long stateSequence,
            String stateContentHash,
            String injectedBlockHash,
            String contentPreview,
            String contentHash,
            long contentLength,
            int previewLength,
            boolean truncated,
            long generatedAtEpochMillis,
            boolean latestStateNotInjected
    ) {
        private static EffectiveContextView unavailable(String reason) {
            return new EffectiveContextView(
                    false, reason, "", false, false, "", 0L, STATE_PROJECTION_STALE_AFTER_MILLIS,
                    "", "", "", "", 0, "", List.of(), 0L, "", "", "", 0L, "", "", "", "",
                    0L, 0, false, 0L, false
            );
        }
    }

    public record StateBudgetView(
            String availability,
            String model,
            Long maxContextTokens,
            Long reservedOutputTokens,
            Long estimatedInputTokens,
            String estimatorVersion
    ) {
        private static StateBudgetView empty() {
            return new StateBudgetView("", "", null, null, null, "");
        }
    }

    public record StateTodoView(
            String todoId,
            String owner,
            String kind,
            String title,
            String status,
            boolean required,
            String acceptanceCriteriaId,
            String acceptanceContentHash,
            List<String> evidenceArtifactIds
    ) {
    }

    public record RecentErrorView(String toolName, String summary, String fingerprint, String timestamp) {
    }

    private record Freshness(boolean stale, String reason) {
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
