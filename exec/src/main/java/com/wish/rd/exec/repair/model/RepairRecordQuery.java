package com.wish.rd.exec.repair.model;

/**
 * 修复记录查询条件。
 *
 * <p>所有字段都可选；{@code null} 或空表示不过滤。
 *
 * @param ticketId    精确匹配工单 ID
 * @param status      精确匹配状态名（{@link RepairRecordStatus#name()}）
 * @param priority    精确匹配优先级（存在 {@code extension_json.priority} 时生效）
 * @param createdFrom 创建时间起始（epoch millis，含），{@code <=0} 表示不限
 * @param createdTo   创建时间截止（epoch millis，含），{@code <=0} 表示不限
 * @param page        页码，从 1 开始
 * @param pageSize    页大小
 */
public record RepairRecordQuery(
        String ticketId,
        String status,
        String priority,
        long createdFrom,
        long createdTo,
        int page,
        int pageSize
) {

    public static final int DEFAULT_PAGE_SIZE = 20;

    public RepairRecordQuery {
        ticketId = ticketId == null ? "" : ticketId.trim();
        status = status == null ? "" : status.trim();
        priority = priority == null ? "" : priority.trim();
        page = page <= 0 ? 1 : page;
        pageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
    }

    /**
     * 构造空查询（返回首页默认大小）。
     *
     * @return 空查询
     */
    public static RepairRecordQuery empty() {
        return new RepairRecordQuery("", "", "", 0L, 0L, 1, DEFAULT_PAGE_SIZE);
    }
}
