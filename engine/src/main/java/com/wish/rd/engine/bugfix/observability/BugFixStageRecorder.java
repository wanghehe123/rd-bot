package com.wish.rd.engine.bugfix.observability;

import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;

/**
 * 记录 Bug 修复各阶段的 attempt、生命周期与输入输出产物。
 *
 * <p>供 {@code RdBotFixEngine} 在真实执行边界调用；持久化和状态合法性由阶段 Store 负责。
 */
public final class BugFixStageRecorder {

    private static final Comparator<AgentStageRun> RECENCY = Comparator
            .comparingInt(AgentStageRun::attemptNo)
            .thenComparingLong(AgentStageRun::createTimeEpochMillis)
            .thenComparing(AgentStageRun::stageRunId);

    private final AgentStageRunStore runStore;
    private final AgentStageArtifactStore artifactStore;
    private final RoleContextPackageStore contextPackageStore;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 创建 Bug 阶段记录器。
     *
     * @param runStore      阶段运行存储
     * @param artifactStore 阶段产物存储
     * @param idGenerator   ID 生成器
     */
    public BugFixStageRecorder(
            AgentStageRunStore runStore,
            AgentStageArtifactStore artifactStore,
            SnowflakeIdGenerator idGenerator
    ) {
        this(runStore, artifactStore, new InMemoryRoleContextPackageStore(), idGenerator);
    }

    public BugFixStageRecorder(
            AgentStageRunStore runStore,
            AgentStageArtifactStore artifactStore,
            RoleContextPackageStore contextPackageStore,
            SnowflakeIdGenerator idGenerator
    ) {
        this.runStore = java.util.Objects.requireNonNull(runStore, "runStore must not be null");
        this.artifactStore = java.util.Objects.requireNonNull(artifactStore, "artifactStore must not be null");
        this.contextPackageStore = java.util.Objects.requireNonNull(
                contextPackageStore, "contextPackageStore must not be null");
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
    }

    /**
     * 返回仅供单测和零配置本地运行使用的内存记录器。
     *
     * @return 内存记录器
     */
    public static BugFixStageRecorder inMemory() {
        return new BugFixStageRecorder(
                new com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore(),
                new com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore(),
                SnowflakeIdGenerator.defaultGenerator()
        );
    }

    /**
     * 创建新 attempt 并推进到 RUNNING，同时保存输入产物。
     *
     * @param taskId 任务 ID
     * @param role   Bug 阶段角色
     * @param input  阶段输入摘要
     * @return RUNNING 阶段
     */
    public AgentStageRun start(String taskId, AgentRole role, String input) {
        requireBugFixRole(role);
        String safeTaskId = requireText(taskId, "taskId");
        AgentStageRun latest = latest(safeTaskId, role);
        int attemptNo = latest == null ? 1 : latest.attemptNo() + 1;
        if (latest != null && !isEnded(latest.status())) {
            throw new IllegalStateException("bug-fix stage is already active: " + role + " " + latest.status());
        }
        long now = System.currentTimeMillis();
        AgentStageRun stage = runStore.save(AgentStageRun.pending(
                idGenerator.nextIdString(),
                safeTaskId,
                role,
                attemptNo,
                safeTaskId + ":" + role.name() + ":" + attemptNo,
                now
        ));
        AgentStageArtifact inputArtifact = saveArtifact(stage, "INPUT", "stage input", input, now);
        RoleContextPackage contextPackage = saveContextPackage(stage, inputArtifact, input, now);
        stage = runStore.save(stage.withContextPackageId(contextPackage.packageId(), now));
        AgentStageArtifact promptArtifact = saveArtifact(stage, "PROMPT", "stage prompt", input, now);
        stage = runStore.save(stage.withPromptArtifactId(promptArtifact.artifactId(), now));
        stage = runStore.transition(stage.stageRunId(), AgentStageStatus.CONTEXT_READY, "", "", now);
        stage = runStore.transition(stage.stageRunId(), AgentStageStatus.DISPATCHING, "", "", now);
        return runStore.transition(stage.stageRunId(), AgentStageStatus.RUNNING, "", "", now);
    }

    private RoleContextPackage saveContextPackage(
            AgentStageRun stage,
            AgentStageArtifact inputArtifact,
            String input,
            long now
    ) {
        String safeInput = input == null ? "" : input;
        RoleContextEvidence evidence = new RoleContextEvidence(
                inputArtifact.artifactId(),
                "BUG_STAGE_INPUT",
                inputArtifact.artifactUri(),
                stage.role().name() + " input",
                inputArtifact.contentHash(),
                preview(safeInput),
                now
        );
        return contextPackageStore.save(new RoleContextPackage(
                idGenerator.nextIdString(),
                stage.taskId(),
                stage.role().name(),
                stage.attemptNo(),
                java.util.List.of(evidence),
                java.util.List.of(),
                java.util.List.of(),
                12_000,
                Math.min(safeInput.length(), 12_000),
                java.util.List.of(),
                now
        ));
    }

    /**
     * 保存阶段结果并推进到 SUCCEEDED。
     *
     * @param stage                RUNNING 阶段
     * @param result               阶段结果摘要
     * @param providerName         provider 名称
     * @param providerAttemptsJson provider 尝试记录
     * @return SUCCEEDED 阶段
     */
    public AgentStageRun succeed(
            AgentStageRun stage,
            String result,
            String providerName,
            String providerAttemptsJson
    ) {
        AgentStageRun current = requireRunning(stage);
        long now = System.currentTimeMillis();
        current = runStore.save(current.withProviderMetadata(providerName, providerAttemptsJson, now));
        current = runStore.transition(current.stageRunId(), AgentStageStatus.RESULT_COLLECTING, "", "", now);
        AgentStageArtifact resultArtifact = saveArtifact(current, "RESULT", "stage result", result, now);
        current = runStore.save(current.withResultArtifactId(resultArtifact.artifactId(), now));
        current = runStore.transition(current.stageRunId(), AgentStageStatus.VERIFYING, "", "", now);
        return runStore.transition(current.stageRunId(), AgentStageStatus.SUCCEEDED, "", "", now);
    }

    /**
     * 将 RUNNING 阶段记录为可重试失败。
     *
     * @param stage         RUNNING 阶段
     * @param errorCategory 错误分类
     * @param errorMessage  错误说明
     * @return FAILED_RETRYABLE 阶段
     */
    public AgentStageRun failRetryable(AgentStageRun stage, String errorCategory, String errorMessage) {
        AgentStageRun current = requireRunning(stage);
        return runStore.transition(
                current.stageRunId(),
                AgentStageStatus.FAILED_RETRYABLE,
                errorCategory,
                errorMessage,
                System.currentTimeMillis()
        );
    }

    /**
     * 将 RUNNING 阶段记录为需要人工处理的失败。
     *
     * @param stage         RUNNING 阶段
     * @param errorCategory 错误分类
     * @param errorMessage  错误说明
     * @return FAILED_NEEDS_HUMAN 阶段
     */
    public AgentStageRun failNeedsHuman(AgentStageRun stage, String errorCategory, String errorMessage) {
        AgentStageRun current = requireRunning(stage);
        return runStore.transition(
                current.stageRunId(),
                AgentStageStatus.FAILED_NEEDS_HUMAN,
                errorCategory,
                errorMessage,
                System.currentTimeMillis()
        );
    }

    private AgentStageArtifact saveArtifact(
            AgentStageRun stage,
            String type,
            String summary,
            String content,
            long now
    ) {
        String safeContent = content == null ? "" : content;
        AgentStageArtifact artifact = new AgentStageArtifact(
                idGenerator.nextIdString(),
                stage.stageRunId(),
                stage.taskId(),
                stage.role(),
                type,
                "rd-artifact://" + stage.stageRunId() + "/" + type.toLowerCase(java.util.Locale.ROOT),
                summary,
                preview(safeContent),
                sha256(safeContent),
                "{}",
                now
        );
        return artifactStore.save(artifact);
    }

    private AgentStageRun latest(String taskId, AgentRole role) {
        return runStore.listByTask(taskId).stream()
                .filter(stage -> stage.role() == role)
                .max(RECENCY)
                .orElse(null);
    }

    private static AgentStageRun requireRunning(AgentStageRun stage) {
        if (stage == null || stage.status() != AgentStageStatus.RUNNING) {
            throw new IllegalArgumentException("bug-fix stage must be RUNNING");
        }
        return stage;
    }

    private static boolean isEnded(AgentStageStatus status) {
        return status == AgentStageStatus.FAILED_RETRYABLE || status.isTerminal();
    }

    private static void requireBugFixRole(AgentRole role) {
        if (role == null || !AgentRole.bugFixOrder().contains(role)) {
            throw new IllegalArgumentException("role must be a bug-fix role: " + role);
        }
    }

    private static String requireText(String value, String fieldName) {
        String safeValue = value == null ? "" : value.strip();
        if (safeValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return safeValue;
    }

    private static String preview(String value) {
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "...";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
