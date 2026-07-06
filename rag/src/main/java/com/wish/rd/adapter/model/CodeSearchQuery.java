package com.wish.rd.adapter.model;

import java.util.List;

/**
 * 代码检索查询条件，作为 {@link CodeRepositorySearchPort#search(CodeSearchQuery)} 的入参。
 *
 * @param repositoryId 目标代码仓库 ID
 * @param query        查询文本
 * @param paths        文件路径过滤（为空则全仓库）
 * @param topK         返回上限，<=0 时取默认 10
 */
public record CodeSearchQuery(
        String repositoryId,
        String query,
        List<String> paths,
        int topK
) {

    public CodeSearchQuery {
        paths = paths == null ? List.of() : List.copyOf(paths);
        topK = topK <= 0 ? 10 : topK;
    }
}
