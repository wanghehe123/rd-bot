package com.wish.rd.engine.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多 Agent 工作流告警事件。
 *
 * @param taskId               RD 任务 ID
 * @param stageRunId           阶段运行 ID
 * @param type                 告警类型
 * @param message              告警内容
 * @param metadata             告警元数据
 * @param createdAtEpochMillis 创建时间
 */
public record AgentWorkflowAlert(
        String taskId,
        String stageRunId,
        AgentWorkflowAlertType type,
        String message,
        Map<String, String> metadata,
        long createdAtEpochMillis
) {

    public AgentWorkflowAlert {
        taskId = requireText(taskId, "taskId");
        stageRunId = safe(stageRunId);
        type = type == null ? AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN : type;
        message = safe(message);
        metadata = copyMetadata(metadata);
    }

    private static Map<String, String> copyMetadata(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = requireText(key, "metadata key");
            copied.put(normalizedKey, safe(value));
        });
        return Map.copyOf(copied);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
