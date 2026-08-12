package com.wish.rd.rag.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;

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
 * @param version               乐观并发版本
 * @param fencingToken          单调 fencing token，拒绝租约失效后的旧写者
 * @param hostAssertionBundle   Host-owned structured assertion input; null keeps legacy text-only acceptance
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
        long tokenBudgetOverride,
        long version,
        long fencingToken,
        JsonNode hostAssertionBundle
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
        version = Math.max(0L, version);
        fencingToken = Math.max(0L, fencingToken);
        hostAssertionBundle = copyHostAssertionBundle(hostAssertionBundle);
    }

    /**
     * Backward-compatible constructor for snapshots that predate structured Host assertions.
     *
     * @param taskId task identifier
     * @param taskType task type
     * @param sourceType source type
     * @param sourceId source identifier
     * @param sourceUrl source URL
     * @param priority task priority
     * @param status task status
     * @param title task title
     * @param projectId project identifier
     * @param projectKey project key
     * @param projectName project name
     * @param repositoryUrl repository URL
     * @param repoOwner repository owner
     * @param repoName repository name
     * @param baseBranch base branch
     * @param workBranch work branch
     * @param expectedResult expected result
     * @param acceptanceCriteriaJson human-readable acceptance criteria JSON
     * @param promptSnapshot prompt snapshot
     * @param executionResultJson execution result JSON
     * @param pullRequestUrl pull request URL
     * @param errorMessage error message
     * @param createTimeEpochMillis creation time
     * @param updateTimeEpochMillis update time
     * @param paused paused marker
     * @param tokenBudgetOverride token budget override
     * @param version optimistic version
     * @param fencingToken fencing token
     */
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
            boolean paused,
            long tokenBudgetOverride,
            long version,
            long fencingToken
    ) {
        this(
                taskId, taskType, sourceType, sourceId, sourceUrl, priority, status, title,
                projectId, projectKey, projectName, repositoryUrl, repoOwner, repoName,
                baseBranch, workBranch, expectedResult, acceptanceCriteriaJson, promptSnapshot,
                executionResultJson, pullRequestUrl, errorMessage, createTimeEpochMillis,
                updateTimeEpochMillis, paused, tokenBudgetOverride, version, fencingToken, null
        );
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
                errorMessage, createTimeEpochMillis, updateTimeEpochMillis, paused, 0L, 0L, 0L
        );
    }

    /**
     * Backward-compatible constructor for callers that provide project metadata and a token budget
     * but predate task version and fencing metadata.
     *
     * @param taskId task identifier
     * @param taskType task type
     * @param sourceType source type
     * @param sourceId source identifier
     * @param sourceUrl source URL
     * @param priority task priority
     * @param status task status
     * @param title task title
     * @param projectId project identifier
     * @param projectKey project key
     * @param projectName project name
     * @param repositoryUrl repository URL
     * @param repoOwner repository owner
     * @param repoName repository name
     * @param baseBranch base branch
     * @param workBranch work branch
     * @param expectedResult expected result
     * @param acceptanceCriteriaJson acceptance criteria JSON
     * @param promptSnapshot prompt snapshot
     * @param executionResultJson execution result JSON
     * @param pullRequestUrl pull request URL
     * @param errorMessage error message
     * @param createTimeEpochMillis creation timestamp
     * @param updateTimeEpochMillis update timestamp
     * @param paused paused marker
     * @param tokenBudgetOverride token budget override
     */
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
            boolean paused,
            long tokenBudgetOverride
    ) {
        this(
                taskId, taskType, sourceType, sourceId, sourceUrl, priority, status, title,
                projectId, projectKey, projectName, repositoryUrl, repoOwner, repoName,
                baseBranch, workBranch, expectedResult, acceptanceCriteriaJson, promptSnapshot,
                executionResultJson, pullRequestUrl, errorMessage, createTimeEpochMillis,
                updateTimeEpochMillis, paused, tokenBudgetOverride, 0L, 0L
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
                0L,
                0L,
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
                safeCommand.tokenBudgetOverride(),
                0L,
                0L,
                safeCommand.hostAssertionBundle()
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
                tokenBudgetOverride,
                version,
                fencingToken,
                hostAssertionBundle
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
                tokenBudgetOverride,
                version,
                fencingToken,
                hostAssertionBundle
        );
    }

    /**
     * 返回替换管理台可编辑字段后的新快照，保留项目、验收标准、运行状态及并发元数据。
     *
     * @param newTitle 新展示标题，空值保留原值
     * @param newPriority 新优先级，空值保留原值
     * @param updateTimeEpochMillis 更新时间
     * @return 编辑后的需求任务快照
     */
    public RdRequirementTask withEditedFields(
            String newTitle,
            String newPriority,
            long updateTimeEpochMillis
    ) {
        return new RdRequirementTask(
                taskId,
                taskType,
                sourceType,
                sourceId,
                sourceUrl,
                newPriority == null || newPriority.isBlank() ? priority : newPriority.strip().toUpperCase(),
                status,
                newTitle == null || newTitle.isBlank() ? title : newTitle.strip(),
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
                tokenBudgetOverride,
                version,
                fencingToken,
                hostAssertionBundle
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
                tokenBudgetOverride,
                version,
                fencingToken,
                hostAssertionBundle
        );
    }

    /**
     * Returns a snapshot carrying persistence-layer concurrency metadata.
     *
     * @param newVersion new optimistic version
     * @param newFencingToken new fencing token
     * @return snapshot with updated concurrency metadata
     */
    public RdRequirementTask withConcurrency(long newVersion, long newFencingToken) {
        return new RdRequirementTask(
                taskId, taskType, sourceType, sourceId, sourceUrl, priority, status, title,
                projectId, projectKey, projectName, repositoryUrl, repoOwner, repoName,
                baseBranch, workBranch, expectedResult, acceptanceCriteriaJson, promptSnapshot,
                executionResultJson, pullRequestUrl, errorMessage, createTimeEpochMillis,
                updateTimeEpochMillis, paused, tokenBudgetOverride, newVersion, newFencingToken,
                hostAssertionBundle
        );
    }

    /**
     * Returns whether this requirement has an explicit Host assertion contract input.
     *
     * @return true when structured assertion input was supplied at task creation
     */
    public boolean hasHostAssertionBundle() {
        return hostAssertionBundle != null && !hostAssertionBundle.isNull();
    }

    /**
     * Returns a defensive structured-input copy so callers cannot mutate task state in place.
     *
     * @return Host assertion definition or null for legacy text-only tasks
     */
    public JsonNode hostAssertionBundle() {
        return hostAssertionBundle == null ? null : hostAssertionBundle.deepCopy();
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

    private static JsonNode copyHostAssertionBundle(JsonNode value) {
        return value == null || value.isNull() ? null : value.deepCopy();
    }
}
