package com.wish.rd.engine.requirement.model;

import com.wish.rd.rag.runtime.model.RdTaskStatus;

/**
 * 需求交付编排结果。
 *
 * @param taskId         任务 ID
 * @param status         任务状态
 * @param pullRequestUrl PR 链接
 * @param resultJson     执行结果 JSON
 * @param errorMessage   错误信息
 */
public record RequirementDeliveryResult(
        String taskId,
        RdTaskStatus status,
        String pullRequestUrl,
        String resultJson,
        String errorMessage
) {

    public RequirementDeliveryResult {
        taskId = taskId == null ? "" : taskId.strip();
        status = status == null ? RdTaskStatus.REJECTED : status;
        pullRequestUrl = pullRequestUrl == null ? "" : pullRequestUrl.strip();
        resultJson = resultJson == null || resultJson.isBlank() ? "{}" : resultJson.strip();
        errorMessage = errorMessage == null ? "" : errorMessage.strip();
    }
}
