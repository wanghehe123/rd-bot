package com.wish.rd.adapter;

import java.util.List;
import java.util.Map;

/**
 * 工单更新命令：更新工单状态、标签、自定义字段等。
 *
 * <p>所有字段都为可选；{@code null} 或空表示不更新对应字段，具体合并语义由实现端口决定。
 *
 * @param ticketId      工单 ID
 * @param status        目标状态字符串（如 {@code processing}/{@code solved}），空表示不更新
 * @param tags          待追加的标签列表，缺省空列表
 * @param comment       附带评论文本，缺省 {@code ""}
 * @param customFields  待更新/合并的自定义字段，缺省空 Map
 * @param solved        是否标记为已解决，{@code null} 表示不更新
 * @param traceId       链路追踪 ID
 */
public record TicketUpdateCommand(
        String ticketId,
        String status,
        List<String> tags,
        String comment,
        Map<String, String> customFields,
        Boolean solved,
        String traceId
) {

    public TicketUpdateCommand {
        ticketId = ticketId == null ? "" : ticketId.trim();
        status = status == null ? "" : status.trim();
        tags = tags == null ? List.of() : List.copyOf(tags);
        comment = comment == null ? "" : comment;
        customFields = customFields == null ? Map.of() : Map.copyOf(customFields);
        traceId = traceId == null ? "" : traceId.trim();
    }
}
