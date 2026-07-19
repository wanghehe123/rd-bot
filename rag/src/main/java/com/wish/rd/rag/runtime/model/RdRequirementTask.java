package com.wish.rd.rag.runtime.model;

import java.util.List;

/**
 * RD 需求交付任务。
 *
 * <p>需求任务承载仓库、分支、预期结果和验收标准等交付输入。需求正文、飞书文档和本地文件
 * 作为 {@link TaskMaterial} 独立保存，不塞入 prompt 快照。
 *
 * @param taskId                Snowflake 字符串任务 ID
 * @param taskType              任务类型
 * @param sourceType            来源类型，如 ADMIN / FEISHU_IM
 * @param sourceId              来源 ID，如 Feishu message id
 * @param sourceUrl             来源 URL
 * @param priority              优先级
 * @param status                当前状态
 * @param title                 展示标题
 * @param projectId             项目 ID
 * @param projectKey            项目 key
 * @param projectName           项目名称
 * @param repositoryUrl         仓库地址
 * @param repoOwner             仓库 owner
 * @param repoName              仓库名
 * @param baseBranch            基准分支
 * @param workBranch            工作分支
 * @param expectedResult        预期结果
 * @param acceptanceCriteriaJson 验收标准 JSON 数组
 * @param promptSnapshot        发送给执行器的 Prompt 快照
 * @param executionResultJson   执行器结构化结果
 * @param pullRequestUrl        PR 链接
 * @param errorMessage          错误或打回原因
 * @param createTimeEpochMillis 创建时间
 * @param updateTimeEpochMillis 更新时间
 * @param paused                是否暂停
 * @param tokenBudgetOverride   任务 token 预算覆盖额度，0 表示使用项目默认或不限制
 */
public record RdRequirementTask(
        String taskId,
        String taskType,
        String sourceType,
        String sourceId,
        String sourceUrl,
        String priority,
        RdTaskStatus status,
        String title,
        String projectId,
        String projectKey,
        String projectName,
        String repositoryUrl,
        String repoOwner,
        String repoName,
        String baseBranch,
        String workBranch,
        String expectedResult,
        String acceptanceCriteriaJson,
        String promptSnapshot,
        String executionResultJson,
        String pullRequestUrl,
        String errorMessage,
        long createTimeEpochMillis,
        long updateTimeEpochMillis,
        boolean paused,
        long tokenBudgetOverride
) implements RdTask {

    public static final String TASK_TYPE = "REQUIREMENT";

    public RdRequirementTask {
        taskId = safe(taskId);
        taskType = taskType == null || taskType.isBlank() ? TASK_TYPE : taskType.strip();
        sourceType = safe(sourceType);
        sourceId = safe(sourceId);
        sourceUrl = safe(sourceUrl);
        priority = normalizePriority(priority);
        status = status == null ? RdTaskStatus.CREATED : status;
        title = safe(title);
        projectId = safe(projectId);
        projectKey = safe(projectKey);
        projectName = safe(projectName);
        repositoryUrl = safe(repositoryUrl);
        repoOwner = safe(repoOwner);
        repoName = safe(repoName);
        baseBranch = safe(baseBranch);
        workBranch = safe(workBranch);
        expectedResult = safe(expectedResult);
        acceptanceCriteriaJson = acceptanceCriteriaJson == null || acceptanceCriteriaJson.isBlank()
                ? "[]"
                : acceptanceCriteriaJson.strip();
        promptSnapshot = safe(promptSnapshot);
        executionResultJson = executionResultJson == null || executionResultJson.isBlank()
                ? "{}"
                : executionResultJson.strip();
        pullRequestUrl = safe(pullRequestUrl);
        errorMessage = safe(errorMessage);
        if (tokenBudgetOverride < 0L) {
            throw new IllegalArgumentException("tokenBudgetOverride must not be negative");
        }
    }

    /** Backward-compatible constructor for persisted task snapshots without a token override. */
    public RdRequirementTask(
            String taskId,
            String taskType,
            String sourceType,
            String sourceId,
            String sourceUrl,
            String priority,
            RdTaskStatus status,
            String title,
            String projectId,
            String projectKey,
            String projectName,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch,
            String workBranch,
            String expectedResult,
            String acceptanceCriteriaJson,
            String promptSnapshot,
            String executionResultJson,
            String pullRequestUrl,
            String errorMessage,
            long createTimeEpochMillis,
            long updateTimeEpochMillis,
            boolean paused
    ) {
        this(
                taskId, taskType, sourceType, sourceId, sourceUrl, priority, status, title,
                projectId, projectKey, projectName, repositoryUrl, repoOwner, repoName, baseBranch, workBranch,
                expectedResult, acceptanceCriteriaJson, promptSnapshot, executionResultJson, pullRequestUrl,
                errorMessage, createTimeEpochMillis, updateTimeEpochMillis, paused, 0L
        );
    }

    public RdRequirementTask(
            String taskId,
            String taskType,
            String sourceType,
            String sourceId,
            String sourceUrl,
            String priority,
            RdTaskStatus status,
            String title,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch,
            String workBranch,
            String expectedResult,
            String acceptanceCriteriaJson,
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
                sourceType,
                sourceId,
                sourceUrl,
                priority,
                status,
                title,
                "",
                "",
                "",
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                workBranch,
                expectedResult,
                acceptanceCriteriaJson,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                createTimeEpochMillis,
                updateTimeEpochMillis,
                paused,
                0L
        );
    }

    /**
     * 构造管理台创建的需求任务。
     *
     * @param taskId                任务 ID
     * @param command               创建命令
     * @param createTimeEpochMillis 创建时间
     * @return CREATED 状态需求任务
     */
    public static RdRequirementTask created(
            String taskId,
            CreateRequirementTaskCommand command,
            long createTimeEpochMillis
    ) {
        CreateRequirementTaskCommand safeCommand = command == null
                ? new CreateRequirementTaskCommand("", "P2", "", "", "", "", "", List.of(), false)
                : command;
        return new RdRequirementTask(
                taskId,
                TASK_TYPE,
                safeCommand.sourceType(),
                safeCommand.sourceId(),
                safeCommand.sourceUrl(),
                safeCommand.priority(),
                RdTaskStatus.CREATED,
                safeCommand.title(),
                safeCommand.projectId(),
                safeCommand.projectKey(),
                safeCommand.projectName(),
                safeCommand.repositoryUrl(),
                safeCommand.repoOwner(),
                safeCommand.repoName(),
                safeCommand.baseBranch(),
                "",
                safeCommand.expectedResult(),
                toJsonArray(safeCommand.acceptanceCriteria()),
                "",
                "{}",
                "",
                "",
                createTimeEpochMillis,
                createTimeEpochMillis,
                false,
                safeCommand.tokenBudgetOverride()
        );
    }

    /**
     * 返回切换暂停标记后的新快照。
     *
     * @param newPaused 新暂停标记
     * @param updateTimeEpochMillis 更新时间
     * @return 新任务快照
     */
    public RdRequirementTask withPaused(boolean newPaused, long updateTimeEpochMillis) {
        return new RdRequirementTask(
                taskId,
                taskType,
                sourceType,
                sourceId,
                sourceUrl,
                priority,
                status,
                title,
                projectId,
                projectKey,
                projectName,
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                workBranch,
                expectedResult,
                acceptanceCriteriaJson,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                createTimeEpochMillis,
                updateTimeEpochMillis,
                newPaused,
                tokenBudgetOverride
        );
    }

    /**
     * 返回替换状态与运行时字段后的新快照。
     *
     * @param newStatus             新状态
     * @param newPromptSnapshot     Prompt 快照
     * @param newResultJson         执行结果 JSON
     * @param newPullRequestUrl     PR 链接
     * @param newErrorMessage       错误信息
     * @param updateTimeEpochMillis 更新时间
     * @return 新任务快照
     */
    public RdRequirementTask withState(
            RdTaskStatus newStatus,
            String newPromptSnapshot,
            String newResultJson,
            String newPullRequestUrl,
            String newErrorMessage,
            long updateTimeEpochMillis
    ) {
        return new RdRequirementTask(
                taskId,
                taskType,
                sourceType,
                sourceId,
                sourceUrl,
                priority,
                newStatus,
                title,
                projectId,
                projectKey,
                projectName,
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                workBranch,
                expectedResult,
                acceptanceCriteriaJson,
                keepOrReplace(promptSnapshot, newPromptSnapshot),
                keepOrReplace(executionResultJson, newResultJson),
                keepOrReplace(pullRequestUrl, newPullRequestUrl),
                safe(newErrorMessage),
                createTimeEpochMillis,
                updateTimeEpochMillis,
                paused,
                tokenBudgetOverride
        );
    }

    /**
     * 返回逻辑删除后的快照。
     *
     * @param updateTimeEpochMillis 更新时间
     * @return DELETED 状态任务
     */
    public RdRequirementTask deleted(long updateTimeEpochMillis) {
        return new RdRequirementTask(
                taskId,
                taskType,
                sourceType,
                sourceId,
                sourceUrl,
                priority,
                RdTaskStatus.DELETED,
                title,
                projectId,
                projectKey,
                projectName,
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                workBranch,
                expectedResult,
                acceptanceCriteriaJson,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                createTimeEpochMillis,
                updateTimeEpochMillis,
                paused,
                tokenBudgetOverride
        );
    }

    private static String normalizePriority(String value) {
        String normalized = safe(value).toUpperCase();
        return normalized.isBlank() ? "P2" : normalized;
    }

    private static String keepOrReplace(String oldValue, String newValue) {
        String safeNewValue = safe(newValue);
        return safeNewValue.isBlank() ? safe(oldValue) : safeNewValue;
    }

    private static String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .map(RdRequirementTask::toJsonString)
                .reduce((left, right) -> left + "," + right)
                .map(value -> "[" + value + "]")
                .orElse("[]");
    }

    private static String toJsonString(String value) {
        return "\"" + safe(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                + "\"";
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
