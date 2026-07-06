package com.wish.rd.adapter.model;

import java.util.List;

/**
 * 工单消息分页结果。
 *
 * @param messages   当前页消息列表，缺省空列表
 * @param page       当前页码
 * @param pageSize   页大小
 * @param total      满足条件的消息总数
 * @param hasMore    是否还有更多页
 */
public record TicketMessages(
        List<TicketMessage> messages,
        int page,
        int pageSize,
        long total,
        boolean hasMore
) {

    public TicketMessages {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * 构造空结果。
     *
     * @return 空消息分页
     */
    public static TicketMessages empty() {
        return new TicketMessages(List.of(), 1, TicketMessageQuery.DEFAULT_PAGE_SIZE, 0L, false);
    }
}
