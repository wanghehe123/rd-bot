package com.wish.rd.rag.project.model;

/**
 * 创建或更新 RD 项目的命令对象。
 *
 * @param projectKey    业务唯一标识
 * @param name          项目名称
 * @param description   项目描述
 * @param repositoryUrl Git 仓库地址
 * @param repoOwner     仓库 owner，留空时从 repositoryUrl 解析
 * @param repoName      仓库名，留空时从 repositoryUrl 解析
 * @param defaultBranch 默认基准分支
 * @param enabled       是否启用
 * @param knowledgeBaseId 可选的项目知识库 ID
 */
public record RdProjectCommand(
        String projectKey,
        String name,
        String description,
        String repositoryUrl,
        String repoOwner,
        String repoName,
        String defaultBranch,
        boolean enabled,
        String knowledgeBaseId
) {

    public RdProjectCommand {
        projectKey = normalizeKey(projectKey);
        name = safe(name);
        description = safe(description);
        repositoryUrl = safe(repositoryUrl);
        repoOwner = safe(repoOwner);
        repoName = safe(repoName);
        defaultBranch = safe(defaultBranch);
        knowledgeBaseId = safe(knowledgeBaseId);
    }

    /**
     * 兼容未绑定知识库的既有项目调用方。
     */
    public RdProjectCommand(
            String projectKey,
            String name,
            String description,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String defaultBranch,
            boolean enabled
    ) {
        this(projectKey, name, description, repositoryUrl, repoOwner, repoName, defaultBranch, enabled, "");
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
