package com.wish.rd.rag.project.model;

/**
 * RD 项目分页查询条件。
 *
 * @param keyword 关键词，匹配 key、名称、仓库 owner/name/url
 * @param enabled 启用状态过滤，null 表示全部
 * @param page    页码，从 1 开始
 * @param pageSize 每页大小
 */
public record RdProjectQuery(
        String keyword,
        Boolean enabled,
        int page,
        int pageSize
) {

    public RdProjectQuery {
        keyword = keyword == null ? "" : keyword.strip();
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), 200);
    }
}
