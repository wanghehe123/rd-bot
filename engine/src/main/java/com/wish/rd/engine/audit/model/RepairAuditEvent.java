package com.wish.rd.engine.audit.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 修复工作流审计事件，用于按 repair_record_id 串起完整操作链路。
 *
 * @param repairRecordId       修复记录 ID
 * @param taskId               RD 任务 ID
 * @param ticketId             外部工单 ID
 * @param type                 审计事件类型
 * @param externalSystem       外部系统名称
 * @param summary              事件摘要
 * @param metadata             扩展元数据
 * @param createdAtEpochMillis 创建时间
 */
public record RepairAuditEvent(
        String repairRecordId,
        String taskId,
        String ticketId,
        RepairAuditEventType type,
        String externalSystem,
        String summary,
        Map<String, String> metadata,
        long createdAtEpochMillis
) {

    public RepairAuditEvent {
        repairRecordId = normalize(repairRecordId);
        taskId = normalize(taskId);
        ticketId = normalize(ticketId);
        type = type == null ? RepairAuditEventType.EXECUTION_FINISHED : type;
        externalSystem = normalize(externalSystem);
        summary = normalize(summary);
        metadata = copyMetadata(metadata);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    /**
     * 创建当前时间的审计事件。
     *
     * @param repairRecordId 修复记录 ID
     * @param taskId         RD 任务 ID
     * @param ticketId       外部工单 ID
     * @param type           审计类型
     * @param externalSystem 外部系统名称
     * @param summary        摘要
     * @param metadata       扩展元数据
     * @return 审计事件
     */
    public static RepairAuditEvent now(
            String repairRecordId,
            String taskId,
            String ticketId,
            RepairAuditEventType type,
            String externalSystem,
            String summary,
            Map<String, String> metadata
    ) {
        return new RepairAuditEvent(
                repairRecordId,
                taskId,
                ticketId,
                type,
                externalSystem,
                summary,
                metadata,
                System.currentTimeMillis()
        );
    }

    private static Map<String, String> copyMetadata(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = normalize(key);
            if (!normalizedKey.isBlank()) {
                copied.put(normalizedKey, redactValue(normalizedKey, value));
            }
        });
        return Map.copyOf(copied);
    }

    private static String redactValue(String key, String value) {
        String normalizedValue = normalize(value);
        String normalizedKey = key == null ? "" : key.toUpperCase();
        if (!normalizedValue.isBlank()
                && (normalizedKey.contains("SECRET")
                || normalizedKey.contains("TOKEN")
                || normalizedKey.contains("PASSWORD")
                || normalizedKey.contains("API_KEY")
                || normalizedKey.contains("APIKEY")
                || normalizedKey.contains("ACCESS_KEY")
                || normalizedKey.contains("ACCESSKEY")
                || normalizedKey.equals("PAT")
                || normalizedKey.endsWith("_PAT")
                || normalizedKey.equals("AUTHORIZATION"))) {
            return "<redacted>";
        }
        return normalizedValue
                .replaceAll("(?i)(authorization\\s*[=:]\\s*bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1<redacted>")
                .replaceAll("(?i)(token|api[_-]?key|password|secret|authorization)=([^\\s,;]+)", "$1=<redacted>")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1<redacted>");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
