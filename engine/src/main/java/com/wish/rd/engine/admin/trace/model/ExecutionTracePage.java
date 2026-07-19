package com.wish.rd.engine.admin.trace.model;

import java.util.List;

/** Paginated execution-trace result. */
public record ExecutionTracePage(
        List<ExecutionTraceRecord> records, long total, int page, int pageSize, int pages
) {
    public ExecutionTracePage {
        records = records == null ? List.of() : List.copyOf(records);
        total = Math.max(0L, total);
        page = Math.max(1, page);
        pageSize = pageSize <= 0 ? 20 : pageSize;
        pages = Math.max(0, pages);
    }
}
