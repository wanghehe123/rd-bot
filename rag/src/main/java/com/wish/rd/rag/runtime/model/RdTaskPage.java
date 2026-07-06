package com.wish.rd.rag.runtime.model;

import java.util.List;

/**
 * RD 任务分页结果。
 *
 * @param records 当前页记录
 * @param total   总条数
 * @param page    当前页码
 * @param pageSize 每页大小
 * @param total   总条数
 * @param pages   总页数
 */
public record RdTaskPage(
        List<RdTask> records,
        long total,
        int page,
        int pageSize,
        int pages
) {

    public RdTaskPage {
        records = records == null ? List.of() : List.copyOf(records);
        total = Math.max(0L, total);
        page = Math.max(1, page);
        pageSize = pageSize <= 0 ? 20 : pageSize;
        pages = pages < 0 ? 0 : pages;
    }
}
