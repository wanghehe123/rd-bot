package com.wish.rd.exec.repair.execution;

/**
 * 修复执行停止命令。
 *
 * @param repairRecordId 修复记录 ID
 * @param taskId         RD 任务 ID
 * @param containerName  容器名称
 * @param reason         停止原因
 */
public record RepairExecutionStopCommand(
        String repairRecordId,
        String taskId,
        String containerName,
        String reason
) {

    public RepairExecutionStopCommand {
        repairRecordId = normalize(repairRecordId);
        taskId = requireTaskId(taskId);
        containerName = normalize(containerName);
        reason = normalize(reason);
    }

    private static String requireTaskId(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
