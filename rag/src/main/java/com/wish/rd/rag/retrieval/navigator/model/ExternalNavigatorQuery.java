package com.wish.rd.rag.retrieval.navigator.model;

import java.util.List;

/**
 * 一次 L0 摘要检索的入参。只携带查询与知识库范围，不携带任何投影写意图。
 *
 * <p>{@code targetUri} 为空时由适配器按知识库范围推导检索根；显式传入时必须先过
 * owned-root 校验，越界不得发请求。
 *
 * @param query            检索文本
 * @param knowledgeBaseIds 本次允许的知识库 ID，空表示调用方尚未收窄范围
 * @param limit            希望返回的候选上限
 * @param targetUri        显式检索根；空则由适配器推导
 */
public record ExternalNavigatorQuery(
        String query,
        List<String> knowledgeBaseIds,
        int limit,
        String targetUri
) {

    public ExternalNavigatorQuery {
        query = query == null ? "" : query;
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        limit = Math.max(1, limit);
        targetUri = targetUri == null ? "" : targetUri.strip();
    }

    /**
     * 构造一次按知识库范围检索的查询。
     *
     * @param query            检索文本
     * @param knowledgeBaseIds 知识库范围
     * @param limit            候选上限
     * @return 查询
     */
    public static ExternalNavigatorQuery of(String query, List<String> knowledgeBaseIds, int limit) {
        return new ExternalNavigatorQuery(query, knowledgeBaseIds, limit, "");
    }

    /**
     * 覆盖检索根。用于调用方已经持有远端 URI、需要适配器做 owned-root 闸门的场景。
     *
     * @param nextTargetUri 显式检索根
     * @return 新查询
     */
    public ExternalNavigatorQuery withTargetUri(String nextTargetUri) {
        return new ExternalNavigatorQuery(query, knowledgeBaseIds, limit, nextTargetUri);
    }
}
