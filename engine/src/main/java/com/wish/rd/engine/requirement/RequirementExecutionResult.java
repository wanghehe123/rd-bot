package com.wish.rd.engine.requirement;

/**
 * 需求交付执行结果。
 *
 * @param taskId         任务 ID
 * @param success        是否执行成功
 * @param summary        执行摘要
 * @param pullRequestUrl PR 链接
 * @param resultJson     结构化结果 JSON
 * @param errorMessage   失败原因
 */
public record RequirementExecutionResult(
        String taskId,
        boolean success,
        String summary,
        String pullRequestUrl,
        String resultJson,
        String errorMessage
) {

    public RequirementExecutionResult {
        taskId = safe(taskId);
        summary = safe(summary);
        pullRequestUrl = safe(pullRequestUrl);
        resultJson = resultJson == null || resultJson.isBlank() ? "{}" : resultJson.strip();
        errorMessage = safe(errorMessage);
    }

    /**
     * 创建成功结果。
     */
    public static RequirementExecutionResult success(
            String taskId,
            String summary,
            String pullRequestUrl,
            String resultJson
    ) {
        return new RequirementExecutionResult(taskId, true, summary, pullRequestUrl, resultJson, "");
    }

    /**
     * 创建失败结果。
     */
    public static RequirementExecutionResult failure(String taskId, String errorMessage, String resultJson) {
        return new RequirementExecutionResult(taskId, false, "", "", resultJson, errorMessage);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
