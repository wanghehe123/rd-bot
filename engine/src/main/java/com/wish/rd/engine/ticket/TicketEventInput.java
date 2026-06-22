package com.wish.rd.engine.ticket;

import java.time.Instant;
import java.util.Map;

/**
 * 工单事件输入：引擎层接收的标准化事件形状，已与具体厂商（飞书）的原始 envelope 解耦。
 *
 * <p>bootstrap 的 webhook 控制器负责把飞书原始事件解析成本 record，再交给
 * {@link TicketEventIngestionEngine} 派发到队列。事件本身只承载路由元数据，
 * 详情由消费端通过 {@code TicketProviderPort} 重新拉取。
 *
 * @param ticketId     工单 ID（必填）
 * @param eventId      事件 ID，用于幂等
 * @param eventType    事件类型，如 {@code helpdesk.ticket.created_v1}
 * @param source       来源渠道，如 {@code feishu}
 * @param priority     优先级，空表示让引擎按默认规则归一
 * @param traceId      链路追踪 ID
 * @param occurredAt   事件发生时间
 * @param metadata     额外元数据（如 {@code sender_type}），缺省空 Map
 */
public record TicketEventInput(
        String ticketId,
        String eventId,
        String eventType,
        String source,
        String priority,
        String traceId,
        Instant occurredAt,
        Map<String, String> metadata
) {

    public static final String TYPE_TICKET_CREATED = "helpdesk.ticket.created_v1";
    public static final String TYPE_TICKET_UPDATED = "helpdesk.ticket.updated_v1";
    public static final String TYPE_TICKET_MESSAGE_CREATED = "helpdesk.ticket_message.created_v1";

    public TicketEventInput {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId must not be blank");
        }
        ticketId = ticketId.trim();
        eventId = eventId == null ? "" : eventId.trim();
        eventType = eventType == null ? "" : eventType.trim();
        source = source == null ? "" : source.trim();
        priority = priority == null ? "" : priority.trim();
        traceId = traceId == null ? "" : traceId.trim();
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
