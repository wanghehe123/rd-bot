package com.wish.rd.exec.repair;

import java.util.Map;
import java.util.Objects;

/**
 * 修复记录主实体，对应 {@code repair_records}。
 *
 * @param id                  主键 Snowflake ID
 * @param ticketId            工单 ID
 * @param ticketUrl           工单 URL
 * @param title               修复标题
 * @param status              当前状态
 * @param ragSummary          RAG 摘要
 * @param extensionJson       扩展字段
 * @param createdAtEpochMillis 创建时间
 * @param updatedAtEpochMillis 更新时间
 */
public record RepairRecord(
        String id,
        String ticketId,
        String ticketUrl,
        String title,
        RepairRecordStatus status,
        String ragSummary,
        Map<String, String> extensionJson,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {

    public RepairRecord {
        Objects.requireNonNull(id, "id must not be null");
        ticketId = ticketId == null ? "" : ticketId;
        ticketUrl = ticketUrl == null ? "" : ticketUrl;
        title = title == null ? "" : title;
        status = status == null ? RepairRecordStatus.CREATED : status;
        ragSummary = ragSummary == null ? "" : ragSummary;
        extensionJson = extensionJson == null ? Map.of() : Map.copyOf(extensionJson);
    }

    public RepairRecord withStatus(RepairRecordStatus newStatus, String newRagSummary, long nowEpochMillis) {
        return new RepairRecord(
                id,
                ticketId,
                ticketUrl,
                title,
                newStatus,
                newRagSummary,
                extensionJson,
                createdAtEpochMillis,
                nowEpochMillis
        );
    }
}
