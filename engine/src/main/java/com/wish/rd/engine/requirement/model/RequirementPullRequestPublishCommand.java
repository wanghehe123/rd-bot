package com.wish.rd.engine.requirement.model;

/**
 * 需求交付复核通过后的 PR 发布命令。
 *
 * @param taskId             RD 任务 ID
 * @param title              需求标题
 * @param repositoryUrl      仓库地址
 * @param repoOwner          仓库所属空间
 * @param repoName           仓库名称
 * @param baseBranch         目标分支
 * @param workBranch         工作分支
 * @param deliveryResultJson 已通过复核的交付结果 JSON
 * @param operationId        publication operation id for PR body marker / open-PR reuse
 */
public record RequirementPullRequestPublishCommand(
        String taskId,
        String title,
        String repositoryUrl,
        String repoOwner,
        String repoName,
        String baseBranch,
        String workBranch,
        String deliveryResultJson,
        String operationId
) {

    public RequirementPullRequestPublishCommand(
            String taskId,
            String title,
            String repositoryUrl,
            String repoOwner,
            String repoName,
            String baseBranch,
            String workBranch,
            String deliveryResultJson
    ) {
        this(taskId, title, repositoryUrl, repoOwner, repoName, baseBranch, workBranch, deliveryResultJson, "");
    }

    public RequirementPullRequestPublishCommand {
        taskId = safe(taskId);
        title = safe(title);
        repositoryUrl = safe(repositoryUrl);
        repoOwner = safe(repoOwner);
        repoName = safe(repoName);
        baseBranch = safe(baseBranch);
        workBranch = safe(workBranch);
        deliveryResultJson = deliveryResultJson == null || deliveryResultJson.isBlank()
                ? "{}"
                : deliveryResultJson.strip();
        operationId = safe(operationId);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
