package com.wish.rd.rag.runtime;

import java.util.List;

/**
 * 创建需求交付任务命令。
 *
 * @param title              需求任务标题
 * @param priority           优先级
 * @param sourceType         来源类型，如 ADMIN / FEISHU_IM
 * @param sourceId           来源 ID，如 Feishu message id
 * @param sourceUrl          来源 URL
 * @param repositoryUrl      仓库地址
 * @param repoOwner          仓库 owner
 * @param repoName           仓库名
 * @param baseBranch         基准分支
 * @param expectedResult     预期结果
 * @param acceptanceCriteria 验收标准
 * @param materials          需求材料输入
 * @param autoExecute        是否创建后自动进入执行队列
 */
public record CreateRequirementTaskCommand(
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
        List<RequirementMaterialInput> materials,
        boolean autoExecute
) {

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
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                expectedResult,
                acceptanceCriteria,
                List.of(),
                autoExecute
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
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                expectedResult,
                acceptanceCriteria,
                materials,
                autoExecute
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
                repositoryUrl,
                repoOwner,
                repoName,
                baseBranch,
                expectedResult,
                acceptanceCriteria,
                List.of(),
                autoExecute
        );
    }

    public CreateRequirementTaskCommand {
        title = safe(title);
        priority = normalizePriority(priority);
        sourceType = normalizeSourceType(sourceType);
        sourceId = safe(sourceId);
        sourceUrl = safe(sourceUrl);
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
}
