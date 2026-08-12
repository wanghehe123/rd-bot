package com.wish.rd.rag.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 创建需求交付任务命令。
 *
 * @param title              需求任务标题
 * @param priority           优先级
 * @param sourceType         来源类型，如 ADMIN / FEISHU_IM
 * @param sourceId           来源 ID，如 Feishu message id
 * @param sourceUrl          来源 URL
 * @param projectId          项目 ID
 * @param projectKey         项目 key
 * @param projectName        项目名称
 * @param repositoryUrl      仓库地址
 * @param repoOwner          仓库 owner
 * @param repoName           仓库名
 * @param baseBranch         基准分支
 * @param expectedResult     预期结果
 * @param acceptanceCriteria 验收标准
 * @param materials          需求材料输入
 * @param autoExecute        是否创建后自动进入执行队列
 * @param hostAssertionBundle 明确的 Host 断言定义；空值保留纯文字验收标准兼容路径
 */
public record CreateRequirementTaskCommand(
        String title,
        String priority,
        String sourceType,
        String sourceId,
        String sourceUrl,
        String projectId,
        String projectKey,
        String projectName,
        String repositoryUrl,
        String repoOwner,
        String repoName,
        String baseBranch,
        String expectedResult,
        List<String> acceptanceCriteria,
        List<RequirementMaterialInput> materials,
        boolean autoExecute,
        long tokenBudgetOverride,
        JsonNode hostAssertionBundle
) {

    /**
     * Backward-compatible constructor for callers that predate structured Host assertions.
     *
     * @param title task title
     * @param priority task priority
     * @param sourceType source type
     * @param sourceId source identifier
     * @param sourceUrl source URL
     * @param projectId project identifier
     * @param projectKey project key
     * @param projectName project name
     * @param repositoryUrl repository URL
     * @param repoOwner repository owner
     * @param repoName repository name
     * @param baseBranch base branch
     * @param expectedResult expected outcome
     * @param acceptanceCriteria human-readable acceptance criteria
     * @param materials task materials
     * @param autoExecute whether to enqueue immediately
     * @param tokenBudgetOverride token budget override
     */
    public CreateRequirementTaskCommand(
            String title,
            String priority,
            String sourceType,
            String sourceId,
            String sourceUrl,
            String projectId,
            String projectKey,
            String projectName,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch,
            String expectedResult,
            List<String> acceptanceCriteria,
            List<RequirementMaterialInput> materials,
            boolean autoExecute,
            long tokenBudgetOverride
    ) {
        this(
                title, priority, sourceType, sourceId, sourceUrl, projectId, projectKey, projectName,
                repositoryUrl, repoOwner, repoName, baseBranch, expectedResult, acceptanceCriteria,
                materials, autoExecute, tokenBudgetOverride, null
        );
    }

    /** Backward-compatible constructor for callers without a task token-budget override. */
    public CreateRequirementTaskCommand(
            String title,
            String priority,
            String sourceType,
            String sourceId,
            String sourceUrl,
            String projectId,
            String projectKey,
            String projectName,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch,
            String expectedResult,
            List<String> acceptanceCriteria,
            List<RequirementMaterialInput> materials,
            boolean autoExecute
    ) {
        this(
                title, priority, sourceType, sourceId, sourceUrl, projectId, projectKey, projectName,
                repositoryUrl, repoOwner, repoName, baseBranch, expectedResult, acceptanceCriteria,
                materials, autoExecute, 0L
        );
    }

    public CreateRequirementTaskCommand(
            String title,
            String priority,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch,
            String expectedResult,
            List<String> acceptanceCriteria,
            boolean autoExecute
    ) {
        this(
                title,
                priority,
                "ADMIN",
                "",
                "",
                "",
                "",
                "",
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                expectedResult,
                acceptanceCriteria,
                List.of(),
                autoExecute,
                0L
        );
    }

    public CreateRequirementTaskCommand(
            String title,
            String priority,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch,
            String expectedResult,
            List<String> acceptanceCriteria,
            List<RequirementMaterialInput> materials,
            boolean autoExecute
    ) {
        this(
                title,
                priority,
                "ADMIN",
                "",
                "",
                "",
                "",
                "",
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                expectedResult,
                acceptanceCriteria,
                materials,
                autoExecute,
                0L
        );
    }

    public CreateRequirementTaskCommand(
            String title,
            String priority,
            String sourceType,
            String sourceId,
            String sourceUrl,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch,
            String expectedResult,
            List<String> acceptanceCriteria,
            boolean autoExecute
    ) {
        this(
                title,
                priority,
                sourceType,
                sourceId,
                sourceUrl,
                "",
                "",
                "",
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                expectedResult,
                acceptanceCriteria,
                List.of(),
                autoExecute,
                0L
        );
    }

    public CreateRequirementTaskCommand {
        title = safe(title);
        priority = normalizePriority(priority);
        sourceType = normalizeSourceType(sourceType);
        sourceId = safe(sourceId);
        sourceUrl = safe(sourceUrl);
        projectId = safe(projectId);
        projectKey = safe(projectKey);
        projectName = safe(projectName);
        repositoryUrl = safe(repositoryUrl);
        repoOwner = safe(repoOwner);
        repoName = safe(repoName);
        baseBranch = safe(baseBranch);
        expectedResult = safe(expectedResult);
        acceptanceCriteria = acceptanceCriteria == null
                ? List.of()
                : acceptanceCriteria.stream()
                .map(CreateRequirementTaskCommand::safe)
                .filter(value -> !value.isBlank())
                .toList();
        materials = materials == null
                ? List.of()
                : materials.stream()
                .filter(input -> input != null && input.hasUsableInput())
                .toList();
        if (tokenBudgetOverride < 0L) {
            throw new IllegalArgumentException("tokenBudgetOverride must not be negative");
        }
        hostAssertionBundle = copyHostAssertionBundle(hostAssertionBundle);
    }

    /**
     * Returns whether this task explicitly requested Host-owned executable assertions.
     *
     * @return true when a structured assertion definition was supplied
     */
    public boolean hasHostAssertionBundle() {
        return hostAssertionBundle != null && !hostAssertionBundle.isNull();
    }

    /**
     * Returns a defensive copy so caller mutation cannot alter the persisted task input.
     *
     * @return structured Host assertion definition or null for legacy text-only tasks
     */
    public JsonNode hostAssertionBundle() {
        return hostAssertionBundle == null ? null : hostAssertionBundle.deepCopy();
    }

    /**
     * 创建需求任务时携带的材料输入。
     *
     * @param materialType 材料类型
     * @param sourceType   来源类型
     * @param title        展示标题
     * @param sourceUri    来源 URI
     * @param content      文本内容
     * @param mimeType     MIME 类型
     * @param revisionId   来源修订 ID
     */
    public record RequirementMaterialInput(
            String materialType,
            String sourceType,
            String title,
            String sourceUri,
            String content,
            String mimeType,
            String revisionId
    ) {
        public RequirementMaterialInput {
            materialType = safe(materialType);
            sourceType = safe(sourceType);
            title = safe(title);
            sourceUri = safe(sourceUri);
            content = safe(content);
            mimeType = safe(mimeType);
            revisionId = safe(revisionId);
        }

        boolean hasUsableInput() {
            return !content.isBlank() || !sourceUri.isBlank();
        }
    }

    private static String normalizePriority(String value) {
        String normalized = safe(value).toUpperCase();
        return normalized.isBlank() ? "P2" : normalized;
    }

    private static String normalizeSourceType(String value) {
        String normalized = safe(value).toUpperCase();
        return normalized.isBlank() ? "ADMIN" : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static JsonNode copyHostAssertionBundle(JsonNode value) {
        return value == null || value.isNull() ? null : value.deepCopy();
    }
}
