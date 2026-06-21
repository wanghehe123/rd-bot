package com.wish.rd.engine.rag;

/**
 * V3 停止接口响应载体。
 *
 * <p>承载 V3 聊天停止接口的返回：任务 ID 与其停止后状态。供 V3 停止接口组装响应。
 *
 * @param taskId 任务 ID（null 归一为空串）
 * @param status 任务状态（null 归一为空串）
 */
public record RagV3StopResult(String taskId, String status) {

    public RagV3StopResult {
        taskId = taskId == null ? "" : taskId;
        status = status == null ? "" : status;
    }
}
