package com.wish.rd.engine.ticket;

import java.util.Map;
import java.util.Objects;

/**
 * 工单修复编排可见的修复记录快照。
 *
 * @param id                   修复记录 ID
 * @param ticketId             工单 ID
 * @param ticketUrl            工单 URL
 * @param title                修复标题
 * @param status               当前状态
 * @param ragSummary           RAG 摘要
 * @param extensionJson        扩展字段
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

    /**
     * 生成状态更新后的记录快照。
     *
     * @param newStatus        新状态
     * @param newRagSummary    新 RAG 摘要
     * @param nowEpochMillis   更新时间
     * @return 更新后的记录
     */
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
