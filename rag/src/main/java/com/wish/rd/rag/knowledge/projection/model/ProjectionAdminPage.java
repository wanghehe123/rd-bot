package com.wish.rd.rag.knowledge.projection.model;

import java.util.List;

/**
 * 管理面分页窗口。
 *
 * @param records 本页记录
 * @param total   过滤后的总数
 * @param page    1-based 页码
 * @param size    页大小
 */
public record ProjectionAdminPage<T>(List<T> records, int total, int page, int size) {

    public ProjectionAdminPage {
        records = records == null ? List.of() : List.copyOf(records);
        total = Math.max(0, total);
        page = Math.max(1, page);
        size = Math.max(1, size);
    }
}
