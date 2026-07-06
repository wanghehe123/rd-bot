package com.wish.rd.engine.agent.model;

/**
 * 单个 Agent 角色的一次阶段运行快照。
 *
 * <p>供阶段运行存储、任务恢复、执行派发和审计报告使用。重试必须创建新的 attemptNo，
 * 不覆盖旧阶段产物。
 *
 * @param stageRunId              阶段运行 ID
 * @param taskId                  RD 任务 ID
 * @param role                    Agent 角色
 * @param status                  阶段状态
 * @param attemptNo               尝试次数
 * @param idempotencyKey          幂等键
 * @param contextPackageId        角色上下文包 ID
 * @param promptArtifactId        prompt 产物 ID
 * @param resultArtifactId        结果产物 ID
 * @param providerName            最终 provider 名称
 * @param providerAttemptsJson    provider 尝试记录 JSON
 * @param reviewResultJson        复核结果 JSON
 * @param errorCategory           错误分类
 * @param errorMessage            错误信息
 * @param createTimeEpochMillis   创建时间
 * @param updateTimeEpochMillis   更新时间
 * @param startedAtEpochMillis    开始时间
 * @param finishedAtEpochMillis   结束时间
 */
public record AgentStageRun(
        String stageRunId,
        String taskId,
        AgentRole role,
        AgentStageStatus status,
        int attemptNo,
        String idempotencyKey,
        String contextPackageId,
        String promptArtifactId,
        String resultArtifactId,
        String providerName,
        String providerAttemptsJson,
        String reviewResultJson,
        String errorCategory,
        String errorMessage,
        long createTimeEpochMillis,
        long updateTimeEpochMillis,
        long startedAtEpochMillis,
        long finishedAtEpochMillis
) {

    public AgentStageRun {
        stageRunId = requireText(stageRunId, "stageRunId");
        taskId = requireText(taskId, "taskId");
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        status = status == null ? AgentStageStatus.PENDING : status;
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        contextPackageId = safe(contextPackageId);
        promptArtifactId = safe(promptArtifactId);
        resultArtifactId = safe(resultArtifactId);
        providerName = safe(providerName);
        providerAttemptsJson = providerAttemptsJson == null || providerAttemptsJson.isBlank()
                ? "[]"
                : providerAttemptsJson.strip();
        reviewResultJson = reviewResultJson == null || reviewResultJson.isBlank()
                ? "{}"
                : reviewResultJson.strip();
        errorCategory = safe(errorCategory);
        errorMessage = safe(errorMessage);
    }

    /**
     * 创建待运行阶段。
     *
     * @param stageRunId             阶段运行 ID
     * @param taskId                 RD 任务 ID
     * @param role                   Agent 角色
     * @param attemptNo              尝试次数
     * @param idempotencyKey         幂等键
     * @param createTimeEpochMillis  创建时间
     * @return PENDING 阶段运行
     */
    public static AgentStageRun pending(
            String stageRunId,
            String taskId,
            AgentRole role,
            int attemptNo,
            String idempotencyKey,
            long createTimeEpochMillis
    ) {
        return new AgentStageRun(
                stageRunId,
                taskId,
                role,
                AgentStageStatus.PENDING,
                attemptNo,
                idempotencyKey,
                "",
                "",
                "",
                "",
                "[]",
                "{}",
                "",
                "",
                createTimeEpochMillis,
                createTimeEpochMillis,
                0L,
                0L
        );
    }

    /**
     * 返回替换状态和错误字段后的新快照。
     *
     * @param newStatus             新阶段状态
     * @param newErrorCategory      新错误分类
     * @param newErrorMessage       新错误信息
     * @param updateTimeEpochMillis 更新时间
     * @return 新阶段运行快照
     */
    public AgentStageRun withStatus(
            AgentStageStatus newStatus,
            String newErrorCategory,
            String newErrorMessage,
            long updateTimeEpochMillis
    ) {
        long nextStartedAt = startedAtEpochMillis;
        if (newStatus == AgentStageStatus.RUNNING && nextStartedAt <= 0L) {
            nextStartedAt = updateTimeEpochMillis;
        }
        long nextFinishedAt = finishedAtEpochMillis;
        if (newStatus != null && newStatus.isTerminal() && nextFinishedAt <= 0L) {
            nextFinishedAt = updateTimeEpochMillis;
        }
        return new AgentStageRun(
                stageRunId,
                taskId,
                role,
                newStatus,
                attemptNo,
                idempotencyKey,
                contextPackageId,
                promptArtifactId,
                resultArtifactId,
                providerName,
                providerAttemptsJson,
                reviewResultJson,
                safe(newErrorCategory),
                safe(newErrorMessage),
                createTimeEpochMillis,
                updateTimeEpochMillis,
                nextStartedAt,
                nextFinishedAt
        );
    }

    /**
     * 返回绑定角色上下文包后的新快照。
     *
     * @param newContextPackageId   角色上下文包 ID
     * @param updateTimeEpochMillis 更新时间
     * @return 新阶段运行快照
     */
    public AgentStageRun withContextPackageId(String newContextPackageId, long updateTimeEpochMillis) {
        return new AgentStageRun(
                stageRunId,
                taskId,
                role,
                status,
                attemptNo,
                idempotencyKey,
                newContextPackageId,
                promptArtifactId,
                resultArtifactId,
                providerName,
                providerAttemptsJson,
                reviewResultJson,
                errorCategory,
                errorMessage,
                createTimeEpochMillis,
                updateTimeEpochMillis,
                startedAtEpochMillis,
                finishedAtEpochMillis
        );
    }

    /**
     * 返回绑定执行 provider 元数据后的新快照。
     *
     * @param newProviderName         最终 provider 名称
     * @param newProviderAttemptsJson provider 尝试记录 JSON
     * @param updateTimeEpochMillis   更新时间
     * @return 新阶段运行快照
     */
    public AgentStageRun withProviderMetadata(
            String newProviderName,
            String newProviderAttemptsJson,
            long updateTimeEpochMillis
    ) {
        String safeProviderName = safe(newProviderName);
        String safeProviderAttemptsJson = newProviderAttemptsJson == null || newProviderAttemptsJson.isBlank()
                ? providerAttemptsJson
                : newProviderAttemptsJson.strip();
        return new AgentStageRun(
                stageRunId,
                taskId,
                role,
                status,
                attemptNo,
                idempotencyKey,
                contextPackageId,
                promptArtifactId,
                resultArtifactId,
                safeProviderName.isBlank() ? providerName : safeProviderName,
                safeProviderAttemptsJson,
                reviewResultJson,
                errorCategory,
                errorMessage,
                createTimeEpochMillis,
                updateTimeEpochMillis,
                startedAtEpochMillis,
                finishedAtEpochMillis
        );
    }

    /**
     * 返回绑定 prompt 产物后的新快照。
     *
     * @param newPromptArtifactId   prompt 产物 ID
     * @param updateTimeEpochMillis 更新时间
     * @return 新阶段运行快照
     */
    public AgentStageRun withPromptArtifactId(String newPromptArtifactId, long updateTimeEpochMillis) {
        return new AgentStageRun(
                stageRunId,
                taskId,
                role,
                status,
                attemptNo,
                idempotencyKey,
                contextPackageId,
                newPromptArtifactId,
                resultArtifactId,
                providerName,
                providerAttemptsJson,
                reviewResultJson,
                errorCategory,
                errorMessage,
                createTimeEpochMillis,
                updateTimeEpochMillis,
                startedAtEpochMillis,
                finishedAtEpochMillis
        );
    }

    /**
     * 返回绑定结果产物后的新快照。
     *
     * @param newResultArtifactId   结果产物 ID
     * @param updateTimeEpochMillis 更新时间
     * @return 新阶段运行快照
     */
    public AgentStageRun withResultArtifactId(String newResultArtifactId, long updateTimeEpochMillis) {
        return new AgentStageRun(
                stageRunId,
                taskId,
                role,
                status,
                attemptNo,
                idempotencyKey,
                contextPackageId,
                promptArtifactId,
                newResultArtifactId,
                providerName,
                providerAttemptsJson,
                reviewResultJson,
                errorCategory,
                errorMessage,
                createTimeEpochMillis,
                updateTimeEpochMillis,
                startedAtEpochMillis,
                finishedAtEpochMillis
        );
    }

    private static String requireText(String value, String fieldName) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
