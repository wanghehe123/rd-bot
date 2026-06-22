package com.wish.rd.exec.repair.alert;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 修复执行告警记录，对应未来告警持久化、通知和审计入口。
 *
 * @param repairRecordId       修复记录 ID
 * @param taskId               RD 任务 ID
 * @param type                 告警类型
 * @param message              告警消息
 * @param metadata             告警扩展元数据
 * @param createdAtEpochMillis 告警创建时间
 */
public record RepairAlert(
        String repairRecordId,
        String taskId,
        RepairAlertType type,
        String message,
        Map<String, String> metadata,
        long createdAtEpochMillis
) {

    public RepairAlert {
        repairRecordId = requireId(repairRecordId, "repairRecordId");
        taskId = requireId(taskId, "taskId");
        type = type == null ? RepairAlertType.VALIDATION_FAILED : type;
        message = message == null ? "" : message;
        metadata = copyMetadata(metadata);
    }

    private static Map<String, String> copyMetadata(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = requireMetadataKey(key);
            if (copied.containsKey(normalizedKey)) {
                throw new IllegalArgumentException("metadata key must be unique after normalization: " + normalizedKey);
            }
            copied.put(normalizedKey, value == null ? "" : value);
        });
        return Map.copyOf(copied);
    }

    private static String requireMetadataKey(String key) {
        String normalized = key == null ? "" : key.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("metadata key must not be blank");
        }
        return normalized;
    }

    private static String requireId(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
