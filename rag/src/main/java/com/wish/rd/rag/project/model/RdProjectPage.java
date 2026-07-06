package com.wish.rd.rag.project.model;

import java.util.List;

/**
 * RD 项目分页结果。
 *
 * @param records  项目记录
 * @param total    总数
 * @param page     当前页
 * @param pageSize 每页大小
 * @param pages    总页数
 */
public record RdProjectPage(
        List<RdProject> records,
        long total,
        int page,
        int pageSize,
        int pages
) {

    public RdProjectPage {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
