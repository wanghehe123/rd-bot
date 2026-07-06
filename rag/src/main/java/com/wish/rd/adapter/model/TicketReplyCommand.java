package com.wish.rd.adapter.model;

import java.util.List;
import java.util.Map;

/**
 * 工单回复命令：向工单会话发送一条消息。
 *
 * @param ticketId       工单 ID
 * @param messageType    消息类型，如 {@code text}/{@code post}，缺省按 {@code text}
 * @param content        消息文本内容
 * @param attachments    附件 URI 列表，缺省空列表
 * @param metadata       额外元数据，缺省空 Map
 * @param traceId        链路追踪 ID
 * @param repairRecordId 关联修复记录 ID
 */
public record TicketReplyCommand(
        String ticketId,
        String messageType,
        String content,
        List<String> attachments,
        Map<String, String> metadata,
        String traceId,
        String repairRecordId
) {

    public TicketReplyCommand {
        ticketId = ticketId == null ? "" : ticketId.trim();
        messageType = messageType == null || messageType.isBlank() ? "text" : messageType.trim();
        content = content == null ? "" : content;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        traceId = traceId == null ? "" : traceId.trim();
        repairRecordId = repairRecordId == null ? "" : repairRecordId.trim();
    }
}
