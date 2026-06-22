package com.wish.rd.engine.ticket;

import java.util.Map;

/**
 * 创建工单修复记录命令。
 *
 * @param ticketId      工单 ID
 * @param ticketUrl     工单 URL
 * @param title         修复标题
 * @param extensionJson 扩展字段
 */
public record CreateRepairRecordCommand(
        String ticketId,
        String ticketUrl,
        String title,
        Map<String, String> extensionJson
) {

    public CreateRepairRecordCommand {
        ticketId = ticketId == null ? "" : ticketId.strip();
        ticketUrl = ticketUrl == null ? "" : ticketUrl.strip();
        title = title == null ? "" : title.strip();
        extensionJson = extensionJson == null ? Map.of() : Map.copyOf(extensionJson);
    }
}
