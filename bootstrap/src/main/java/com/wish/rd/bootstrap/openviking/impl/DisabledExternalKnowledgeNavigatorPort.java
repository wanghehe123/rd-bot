package com.wish.rd.bootstrap.openviking.impl;

import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.retrieval.navigator.ExternalKnowledgeNavigatorPort;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorContent;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorDocument;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorQuery;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorSearch;

/**
 * 未启用外部索引时的只读导航端口。永远 {@code ready() == false}，
 * 所有读取都返回 {@code CONFIGURATION_BLOCKED}，与写端口的 Disabled 实现对称。
 */
public final class DisabledExternalKnowledgeNavigatorPort implements ExternalKnowledgeNavigatorPort {

    private static final String REASON = "external knowledge navigator is disabled";

    /**
     * @param query 查询，本实现忽略
     * @return 配置阻断
     */
    @Override
    public ExternalNavigatorSearch searchAbstracts(ExternalNavigatorQuery query) {
        return ExternalNavigatorSearch.failed(
                ExternalIndexFailureClass.CONFIGURATION_BLOCKED, "NAVIGATOR_DISABLED", REASON);
    }

    /**
     * @param resourceUri 资源 URI
     * @return 配置阻断
     */
    @Override
    public ExternalNavigatorDocument readOverview(String resourceUri) {
        return ExternalNavigatorDocument.failed(
                resourceUri, ExternalIndexFailureClass.CONFIGURATION_BLOCKED, "NAVIGATOR_DISABLED", REASON);
    }

    /**
     * @param resourceUri 资源 URI
     * @param offset      切片起点
     * @param limit       切片长度
     * @return 配置阻断
     */
    @Override
    public ExternalNavigatorContent readContent(String resourceUri, int offset, int limit) {
        return ExternalNavigatorContent.failed(
                resourceUri, offset, limit,
                ExternalIndexFailureClass.CONFIGURATION_BLOCKED, "NAVIGATOR_DISABLED", REASON);
    }

    /**
     * @return 始终 false
     */
    @Override
    public boolean ready() {
        return false;
    }
}
