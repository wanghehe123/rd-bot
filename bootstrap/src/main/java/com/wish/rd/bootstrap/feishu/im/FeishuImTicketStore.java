package com.wish.rd.bootstrap.feishu.im;

import com.wish.rd.adapter.TicketMessage;
import com.wish.rd.adapter.TicketMessageQuery;
import com.wish.rd.adapter.TicketMessages;
import com.wish.rd.adapter.TicketSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 飞书 IM 本地工单存储。
 *
 * <p>该存储负责把 IM 消息转换成 RD-Bot 标准工单快照，并为后续
 * {@code TicketProviderPort} 读取提供本地快照。MVP 阶段使用内存注册表模式；
 * 后续可替换为 PostgreSQL 实现而不影响 engine/rag 端口。
 */
public class FeishuImTicketStore {

    /** 飞书 IM 工单来源标识。 */
    public static final String SOURCE = "feishu-im";

    private final Map<String, TicketSnapshot> tickets = new LinkedHashMap<>();
    private final Map<String, List<TicketMessage>> messages = new LinkedHashMap<>();

    /**
     * 从一条飞书 IM 消息注册内部工单。
     *
     * @param ticketId     内部工单 ID
     * @param chatId       飞书会话 ID
     * @param senderOpenId 发送人 open_id
     * @param messageId    飞书消息 ID
     * @param text         清理后的用户消息文本
     * @param draft        解析后的工单草稿
     * @param createdAt    消息创建时间
     * @return 已注册的标准工单快照
     */
    public synchronized TicketSnapshot registerFromMessage(
            String ticketId,
            String chatId,
            String senderOpenId,
            String messageId,
            String text,
            FeishuImTicketDraft draft,
            Instant createdAt
    ) {
        FeishuImTicketDraft safeDraft = draft == null
                ? new FeishuImTicketParser().parse(text)
                : draft;
        Instant safeCreatedAt = createdAt == null ? Instant.now() : createdAt;
        TicketSnapshot snapshot = new TicketSnapshot(
                safe(ticketId),
                safeDraft.title(),
                safeDraft.description(),
                List.of(SOURCE),
                safeCreatedAt,
                safeDraft.priority(),
                "created",
                "",
                SOURCE,
                safe(chatId),
                safeDraft.customFields(),
                safeCreatedAt,
                Instant.EPOCH
        );
        tickets.put(snapshot.ticketId(), snapshot);
        TicketMessage message = new TicketMessage(
                safe(messageId),
                "2",
                safe(senderOpenId),
                "text",
                text == null ? "" : text,
                List.of(),
                Map.of("chatId", safe(chatId), "source", SOURCE),
                safeCreatedAt
        );
        messages.put(snapshot.ticketId(), List.of(message));
        return snapshot;
    }

    /**
     * 查询本地工单快照。
     *
     * @param ticketId 工单 ID
     * @return 工单快照；不存在时为空
     */
    public synchronized Optional<TicketSnapshot> findTicket(String ticketId) {
        return Optional.ofNullable(tickets.get(safe(ticketId)));
    }

    /**
     * 查询本地工单消息。
     *
     * @param ticketId 工单 ID
     * @param query    分页查询
     * @return 工单消息分页
     */
    public synchronized TicketMessages findMessages(String ticketId, TicketMessageQuery query) {
        TicketMessageQuery safeQuery = query == null ? TicketMessageQuery.defaults() : query;
        List<TicketMessage> all = messages.getOrDefault(safe(ticketId), List.of());
        List<TicketMessage> filtered = all.stream()
                .filter(message -> safeQuery.senderType().isBlank() || safeQuery.senderType().equals(message.senderType()))
                .toList();
        int fromIndex = Math.min((safeQuery.page() - 1) * safeQuery.pageSize(), filtered.size());
        int toIndex = Math.min(fromIndex + safeQuery.pageSize(), filtered.size());
        List<TicketMessage> page = new ArrayList<>(filtered.subList(fromIndex, toIndex));
        return new TicketMessages(
                page,
                safeQuery.page(),
                safeQuery.pageSize(),
                filtered.size(),
                toIndex < filtered.size()
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
