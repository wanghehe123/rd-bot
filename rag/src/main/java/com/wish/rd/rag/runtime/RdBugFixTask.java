package com.wish.rd.rag.runtime;

/**
 * RD Bug 修复任务。
 *
 * <p>供 {@link RagStreamTaskRegistry} 管理从工单创建、RAG 检索、执行、提交 PR 到 RD 评审的状态流转。
 *
 * @param taskId                Snowflake 字符串任务 ID
 * @param taskType              任务类型
 * @param ticketId              工单 ID
 * @param ticketTitle           工单标题
 * @param priority              工单优先级
 * @param status                当前状态
 * @param messageId             兼容旧流式消息 ID
 * @param title                 展示标题
 * @param promptSnapshot        发送给执行器的 Prompt 快照
 * @param executionResultJson   执行器返回的结构化结果 JSON
 * @param pullRequestUrl        执行器创建的 PR 链接
 * @param errorMessage          错误或 RD 打回原因
 * @param createTimeEpochMillis 创建时间
 * @param updateTimeEpochMillis 更新时间
 */
public record RdBugFixTask(
        String taskId,
        String taskType,
        String ticketId,
        String ticketTitle,
        String priority,
        RdTaskStatus status,
        String messageId,
        String title,
        String promptSnapshot,
        String executionResultJson,
        String pullRequestUrl,
        String errorMessage,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) implements RdTask {

    public static final String TASK_TYPE = "BUG_FIX";

    public RdBugFixTask {
        taskId = safe(taskId);
        taskType = taskType == null || taskType.isBlank() ? TASK_TYPE : taskType.strip();
        ticketId = safe(ticketId);
        ticketTitle = safe(ticketTitle);
        priority = normalizePriority(priority);
        status = status == null ? RdTaskStatus.CREATED : status;
        messageId = safe(messageId);
        title = safe(title);
        promptSnapshot = safe(promptSnapshot);
        executionResultJson = safe(executionResultJson);
        pullRequestUrl = safe(pullRequestUrl);
        errorMessage = safe(errorMessage);
    }

    /**
     * 构造初始 Bug 修复任务。
     *
     * @param taskId                任务 ID
     * @param ticketId              工单 ID
     * @param ticketTitle           工单标题
     * @param priority              工单优先级
     * @param createTimeEpochMillis 创建时间
     * @return CREATED 状态任务
     */
    public static RdBugFixTask created(
            String taskId,
            String ticketId,
            String ticketTitle,
            String priority,
            long createTimeEpochMillis
    ) {
        return new RdBugFixTask(
                taskId,
                TASK_TYPE,
                ticketId,
                ticketTitle,
                priority,
                RdTaskStatus.CREATED,
                "",
                ticketTitle,
                "",
                "",
                "",
                "",
                createTimeEpochMillis,
                createTimeEpochMillis
        );
    }

    /**
     * 返回替换状态与运行时字段后的新快照。
     *
     * @param newStatus           新状态
     * @param newMessageId        消息 ID
     * @param newTitle            展示标题
     * @param newPromptSnapshot   Prompt 快照
     * @param newResultJson       执行结果 JSON
     * @param newPullRequestUrl   PR 链接
     * @param newErrorMessage     错误信息
     * @param updateTimeEpochMillis 更新时间
     * @return 新任务快照
     */
    public RdBugFixTask withState(
            RdTaskStatus newStatus,
            String newMessageId,
            String newTitle,
            String newPromptSnapshot,
            String newResultJson,
            String newPullRequestUrl,
            String newErrorMessage,
            long updateTimeEpochMillis
    ) {
        return new RdBugFixTask(
                taskId,
                taskType,
                ticketId,
                ticketTitle,
                priority,
                newStatus,
                keepOrReplace(messageId, newMessageId),
                keepOrReplace(title, newTitle),
                keepOrReplace(promptSnapshot, newPromptSnapshot),
                keepOrReplace(executionResultJson, newResultJson),
                keepOrReplace(pullRequestUrl, newPullRequestUrl),
                safe(newErrorMessage),
                createTimeEpochMillis,
                updateTimeEpochMillis
        );
    }

    private static String normalizePriority(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase();
        return normalized.isBlank() ? "P2" : normalized;
    }

    private static String keepOrReplace(String oldValue, String newValue) {
        String safeNewValue = safe(newValue);
        return safeNewValue.isBlank() ? safe(oldValue) : safeNewValue;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
