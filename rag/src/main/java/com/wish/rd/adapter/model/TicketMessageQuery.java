package com.wish.rd.adapter.model;

import java.time.Instant;

/**
 * 工单消息查询条件，作为 {@link TicketProviderPort#findMessages(String, TicketMessageQuery)} 的入参。
 *
 * <p>承载分页与时间窗约束，避免一次性拉取大量消息。
 *
 * @param senderType 发送方类型筛选；{@code null}/{@code ""} 表示不限。飞书 Helpdesk 中
 *                   {@code 1}=客服、{@code 2}=用户。
 * @param fromTime   查询起始时间（含），{@code null} 表示不限
 * @param toTime     查询截止时间（含），{@code null} 表示不限
 * @param pageSize   单页大小，缺省 {@link #DEFAULT_PAGE_SIZE}
 * @param page       页码，从 1 开始，缺省 {@code 1}
 */
public record TicketMessageQuery(
        String senderType,
        Instant fromTime,
        Instant toTime,
        int pageSize,
        int page
) {

    public static final int DEFAULT_PAGE_SIZE = 50;

    public TicketMessageQuery {
        senderType = senderType == null ? "" : senderType.trim();
        fromTime = fromTime == null ? Instant.EPOCH : fromTime;
        toTime = toTime == null ? Instant.EPOCH : toTime;
        pageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
        page = page <= 0 ? 1 : page;
    }

    /**
     * 创建默认查询（仅分页）。
     *
     * @return 默认查询条件
     */
    public static TicketMessageQuery defaults() {
        return new TicketMessageQuery("", Instant.EPOCH, Instant.EPOCH, DEFAULT_PAGE_SIZE, 1);
    }
}
