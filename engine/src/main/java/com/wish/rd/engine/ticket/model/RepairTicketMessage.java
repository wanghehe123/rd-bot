package com.wish.rd.engine.ticket.model;

import java.time.Instant;

/**
 * 工单修复队列消息：传输安全的瘦消息，只承载路由与去重元数据。
 *
 * <p>关键约束：
 * <ul>
 *   <li><b>禁止</b>包含工单标题/描述/日志/原始响应/Access Token/Helpdesk Token 等敏感或大体量内容。</li>
 *   <li>消息键 = {@code ticketId}（非空），用于队列路由与去重。</li>
 *   <li>{@code priority} 归一化为 {@code P0}/{@code P1}/{@code P2}，作为兼容标签和处理优先级。</li>
 *   <li>{@code eventId + eventType + ticketId} 用于幂等去重判断。</li>
 * </ul>
 *
 * <p>消息体由 {@link TicketEventIngestionEngine} 组装，由 {@link RepairQueuePublisher} 发布，
 * 由 {@code TicketRepairEngine} 消费——消费端通过 {@code TicketProviderPort} 重新拉取工单明细。
 *
 * @param ticketId  工单 ID（非空，作为消息键）
 * @param priority  优先级 {@code P0}/{@code P1}/{@code P2}，缺省 {@code P2}
 * @param traceId   链路追踪 ID，缺省 {@code ""}
 * @param attempt   重试次数，首次为 {@code 1}
 * @param source    来源渠道（如 {@code feishu}），缺省 {@code ""}
 * @param eventId   事件 ID（提供方原始事件 ID），用于幂等
 * @param eventType 事件类型（如 {@code helpdesk.ticket.created_v1}），缺省 {@code ""}
 * @param createdAt 消息创建时间，缺省 {@link Instant#EPOCH}
 */
public record RepairTicketMessage(
        String ticketId,
        String priority,
        String traceId,
        int attempt,
        String source,
        String eventId,
        String eventType,
        Instant createdAt
) {

    public static final String DEFAULT_PRIORITY = "P2";
    public static final int FIRST_ATTEMPT = 1;
    public static final String SOURCE_FEISHU = "feishu";

    public RepairTicketMessage {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId must not be blank");
        }
        ticketId = ticketId.trim();
        priority = normalizePriority(priority);
        traceId = traceId == null ? "" : traceId.trim();
        attempt = attempt <= 0 ? FIRST_ATTEMPT : attempt;
        source = source == null ? "" : source.trim();
        eventId = eventId == null ? "" : eventId.trim();
        eventType = eventType == null ? "" : eventType.trim();
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }

    /**
     * 构造下一次重试消息：保留 {@code traceId}/{@code eventId}/{@code eventType}，
     * attempt 自增。
     *
     * @return 自增 attempt 的新消息
     */
    public RepairTicketMessage nextAttempt() {
        return new RepairTicketMessage(
                ticketId,
                priority,
                traceId,
                attempt + 1,
                source,
                eventId,
                eventType,
                Instant.now()
        );
    }

    /**
     * 兼容优先级标签：{@code priority} 本身（{@code P0}/{@code P1}/{@code P2}）。
     *
     * @return 队列 tag
     */
    public String tag() {
        return priority;
    }

    /**
     * 去重键：{@code eventId + "|" + ticketId + "|" + eventType}。
     *
     * @return 去重键
     */
    public String deduplicationKey() {
        return eventId + "|" + ticketId + "|" + eventType;
    }

    private static String normalizePriority(String priority) {
        if (priority == null) {
            return DEFAULT_PRIORITY;
        }
        String upper = priority.trim().toUpperCase();
        return switch (upper) {
            case "P0", "P1", "P2" -> upper;
            default -> DEFAULT_PRIORITY;
        };
    }
}
