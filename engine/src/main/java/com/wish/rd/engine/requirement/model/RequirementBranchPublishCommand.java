package com.wish.rd.engine.requirement.model;

/**
 * 需求交付复核通过后的工作分支推送命令。
 *
 * <p>在创建 PR 之前，需要先把已复核的候选补丁提交并推送到工作分支；否则代码平台会因
 * head 分支不存在而拒绝创建 PR（GitHub 返回 422）。
 *
 * @param taskId             RD 任务 ID
 * @param title              需求标题
 * @param repositoryUrl      仓库地址
 * @param repoOwner          仓库所属空间
 * @param repoName           仓库名称
 * @param baseBranch         目标分支
 * @param workBranch         工作分支
 * @param deliveryResultJson 已通过复核的交付结果 JSON（用于定位候选补丁）
 */
public record RequirementBranchPublishCommand(
        String taskId,
        String title,
        String repositoryUrl,
        String repoOwner,
        String repoName,
        String baseBranch,
        String workBranch,
        String deliveryResultJson
) {

    public RequirementBranchPublishCommand {
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
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
