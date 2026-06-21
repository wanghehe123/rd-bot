package com.wish.rd.engine.bugfix;

/**
 * Bug 修复执行器结构化结果。
 *
 * <p>供 {@link RdBotFixEngine} 保存执行产物、推进任务状态并返回给上游流程。
 *
 * @param taskId          任务 ID
 * @param bugDescription  执行器识别的问题描述
 * @param solution        修复方案摘要
 * @param pullRequestUrl  PR 链接
 * @param resultJson      执行器返回的原始结构化 JSON
 */
public record BugFixExecutionResult(
        String taskId,
        String bugDescription,
        String solution,
        String pullRequestUrl,
        String resultJson
) {

    public BugFixExecutionResult {
        taskId = taskId == null ? "" : taskId.strip();
        bugDescription = bugDescription == null ? "" : bugDescription;
        solution = solution == null ? "" : solution;
        pullRequestUrl = pullRequestUrl == null ? "" : pullRequestUrl;
        resultJson = resultJson == null ? "" : resultJson;
    }

    /**
     * 构造空执行结果。
     *
     * @param taskId 任务 ID
     * @return 空执行结果
     */
    public static BugFixExecutionResult empty(String taskId) {
        return new BugFixExecutionResult(taskId, "", "", "", "{}");
    }
}
