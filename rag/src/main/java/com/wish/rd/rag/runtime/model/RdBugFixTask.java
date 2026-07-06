package com.wish.rd.rag.runtime.model;

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
 * @param projectId             项目 ID
 * @param projectKey            项目 key
 * @param projectName           项目名称
 * @param repositoryUrl         仓库地址
 * @param repoOwner             仓库 owner
 * @param repoName              仓库名
 * @param baseBranch            基准分支
 * @param createTimeEpochMillis 创建时间
 * @param updateTimeEpochMillis 更新时间
 * @param paused                是否被管理台暂停（仅标记，不改变状态机合法性）
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
        String projectId,
        String projectKey,
        String projectName,
        String repositoryUrl,
        String repoOwner,
        String repoName,
        String baseBranch,
        long createTimeEpochMillis,
        long updateTimeEpochMillis,
        boolean paused
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
        projectId = safe(projectId);
        projectKey = safe(projectKey);
        projectName = safe(projectName);
        repositoryUrl = safe(repositoryUrl);
        repoOwner = safe(repoOwner);
        repoName = safe(repoName);
        baseBranch = safe(baseBranch);
    }

    public RdBugFixTask(
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
            long updateTimeEpochMillis,
            boolean paused
    ) {
        this(
                taskId,
                taskType,
                ticketId,
                ticketTitle,
                priority,
                status,
                messageId,
                title,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                createTimeEpochMillis,
                updateTimeEpochMillis,
                paused
        );
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
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                createTimeEpochMillis,
                createTimeEpochMillis,
                false
        );
    }

    /**
     * 返回替换状态与运行时字段后的新快照（paused 保持不变）。
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
                projectId,
                projectKey,
                projectName,
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                createTimeEpochMillis,
                updateTimeEpochMillis,
                paused
        );
    }

    /**
     * 返回替换编辑字段（标题/优先级/工单标题）后的新快照，状态与暂停标记不变。
     *
     * @param newTitle      展示标题
     * @param newPriority   优先级
     * @param newTicketTitle 工单标题
     * @param updateTimeEpochMillis 更新时间
     * @return 新任务快照
     */
    public RdBugFixTask withEditedFields(
            String newTitle,
            String newPriority,
            String newTicketTitle,
            long updateTimeEpochMillis
    ) {
        return new RdBugFixTask(
                taskId,
                taskType,
                ticketId,
                newTicketTitle == null || newTicketTitle.isBlank() ? ticketTitle : newTicketTitle.strip(),
                newPriority == null || newPriority.isBlank() ? priority : newPriority.strip().toUpperCase(),
                status,
                messageId,
                newTitle == null || newTitle.isBlank() ? title : newTitle.strip(),
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                projectId,
                projectKey,
                projectName,
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                createTimeEpochMillis,
                updateTimeEpochMillis,
                paused
        );
    }

    /**
     * 返回切换暂停标记后的新快照。
     *
     * @param newPaused 新暂停标记
     * @param updateTimeEpochMillis 更新时间
     * @return 新任务快照
     */
    public RdBugFixTask withPaused(boolean newPaused, long updateTimeEpochMillis) {
        return new RdBugFixTask(
                taskId,
                taskType,
                ticketId,
                ticketTitle,
                priority,
                status,
                messageId,
                title,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                projectId,
                projectKey,
                projectName,
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                createTimeEpochMillis,
                updateTimeEpochMillis,
                newPaused
        );
    }

    /** 返回将状态置为 DELETED 逻辑删后的快照（沿用 DELETED 字符串语义，状态机外）。 */
    public RdBugFixTask deleted(long updateTimeEpochMillis) {
        return new RdBugFixTask(
                taskId,
                taskType,
                ticketId,
                ticketTitle,
                priority,
                RdTaskStatus.DELETED,
                messageId,
                title,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                projectId,
                projectKey,
                projectName,
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                createTimeEpochMillis,
                updateTimeEpochMillis,
                paused
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
