package com.wish.rd.engine.requirement.model;

/**
 * 需求交付 PR 发布结果。
 *
 * @param taskId            RD 任务 ID
 * @param success           是否发布成功
 * @param pullRequestUrl    PR 链接
 * @param pullRequestNumber PR 编号
 * @param metadataJson      代码平台元数据 JSON
 * @param errorMessage      失败原因
 */
public record RequirementPullRequestPublication(
        String taskId,
        boolean success,
        String pullRequestUrl,
        String pullRequestNumber,
        String metadataJson,
        String errorMessage
) {

    public RequirementPullRequestPublication {
        taskId = safe(taskId);
        pullRequestUrl = safe(pullRequestUrl);
        pullRequestNumber = safe(pullRequestNumber);
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson.strip();
        errorMessage = safe(errorMessage);
    }

    /**
     * 创建成功发布结果。
     */
    public static RequirementPullRequestPublication success(
            String taskId,
            String pullRequestUrl,
            String pullRequestNumber,
            String metadataJson
    ) {
        return new RequirementPullRequestPublication(taskId, true, pullRequestUrl, pullRequestNumber, metadataJson, "");
    }

    /**
     * 创建失败发布结果。
     */
    public static RequirementPullRequestPublication failure(String taskId, String errorMessage) {
        return new RequirementPullRequestPublication(taskId, false, "", "", "{}", errorMessage);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
