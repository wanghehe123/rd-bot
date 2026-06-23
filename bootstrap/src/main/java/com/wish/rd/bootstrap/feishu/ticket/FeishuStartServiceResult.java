package com.wish.rd.bootstrap.feishu.ticket;

import java.util.Map;

/**
 * 创建服务台对话结果，对应飞书 start_service 响应中的 {@code data}。
 *
 * @param chatId   创建的客服群 open ID
 * @param ticketId 创建的工单 ID；仅人工工单通常会返回
 * @param metadata 扩展元数据
 */
public record FeishuStartServiceResult(
        String chatId,
        String ticketId,
        Map<String, String> metadata
) {

    public FeishuStartServiceResult {
        chatId = normalize(chatId);
        ticketId = normalize(ticketId);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
