package com.wish.rd.adapter;

import java.time.Instant;
import java.util.List;

/**
 * 工单快照，作为 {@link TicketSystemPort#findTicket(String)} 的返回值。
 *
 * <p>承载工单的核心信息，供后续基于工单的修复流程使用。
 *
 * @param ticketId    工单 ID
 * @param title       工单标题
 * @param description 工单描述（修复请求的主要来源）
 * @param labels      工单标签（可用于辅助意图分类）
 * @param createdAt   工单创建时间
 */
public record TicketSnapshot(
        String ticketId,
        String title,
        String description,
        List<String> labels,
        Instant createdAt
) {

    public TicketSnapshot {
        labels = labels == null ? List.of() : List.copyOf(labels);
    }
}
