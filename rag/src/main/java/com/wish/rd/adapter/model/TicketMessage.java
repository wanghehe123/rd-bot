package com.wish.rd.adapter.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 工单消息记录，对应一条聊天消息（用户 / 客服 / 系统）。
 *
 * <p>该 record 是标准形状，{@code content} 已被归一化为可读文本；图片等富媒体以
 * URI 形式落在 {@code attachments}。
 *
 * @param messageId   消息 ID（工单提供方原始 ID）
 * @param senderType  发送方类型字符串（如飞书 {@code 1}=客服、{@code 2}=用户）
 * @param senderId    发送方 ID
 * @param messageType 消息类型，如 {@code text}/{@code post}/{@code image}
 * @param content     归一化后的可读文本内容
 * @param attachments 附件 URI 列表，缺省空列表
 * @param metadata    额外元数据，缺省空 Map
 * @param createdAt   消息创建时间，缺省 {@link Instant#EPOCH}
 */
public record TicketMessage(
        String messageId,
        String senderType,
        String senderId,
        String messageType,
        String content,
        List<String> attachments,
        Map<String, String> metadata,
        Instant createdAt
) {

    public TicketMessage {
        messageId = messageId == null ? "" : messageId.trim();
        senderType = senderType == null ? "" : senderType.trim();
        senderId = senderId == null ? "" : senderId.trim();
        messageType = messageType == null ? "" : messageType.trim();
        content = content == null ? "" : content;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }

    /**
     * 判断该消息是否来自用户（而非客服/系统）。
     *
     * @return 用户消息返回 true
     */
    public boolean isFromUser() {
        return "2".equals(senderType);
    }
}
