package com.wish.rd.exec.repair.execution;

/**
 * 修复执行停止结果。
 *
 * @param taskId        RD 任务 ID
 * @param containerName 容器名称
 * @param stopped       是否已停止
 * @param message       结果消息
 */
public record RepairExecutionStopResult(
        String taskId,
        String containerName,
        boolean stopped,
        String message
) {

    public RepairExecutionStopResult {
        taskId = taskId == null ? "" : taskId.strip();
        containerName = containerName == null ? "" : containerName.strip();
        message = message == null ? "" : message.strip();
    }
}
