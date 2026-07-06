package com.wish.rd.exec.repair.model;

import java.util.Map;

/**
 * 创建修复记录命令。
 *
 * @param ticketId       外部工单 ID
 * @param ticketUrl      工单 URL
 * @param title          修复任务标题
 * @param extensionJson  扩展字段
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
