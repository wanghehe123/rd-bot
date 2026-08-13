package com.wish.rd.rag.retrieval.navigator;

import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorContent;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorDocument;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorQuery;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorSearch;

/**
 * 外部知识的只读导航端口。与投影写端口物理分离：实现不得依赖 outbox/binding
 * 的任何写入，也不得把检索结果写回 {@code knowledge_documents}。
 *
 * <p>调用方是需求交付读路径。L0/L1/L2 的失败必须翻译成
 * {@link com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass}，
 * 不得抛出未分类的远端异常。
 */
public interface ExternalKnowledgeNavigatorPort {

    /**
     * L0：按查询检索摘要候选。
     *
     * @param query 查询与范围
     * @return 命中或已分类的失败
     */
    ExternalNavigatorSearch searchAbstracts(ExternalNavigatorQuery query);

    /**
     * L1：读取资源 overview。
     *
     * @param resourceUri 资源 URI
     * @return overview 或已分类的失败/缺席
     */
    ExternalNavigatorDocument readOverview(String resourceUri);

    /**
     * L2：读取资源正文。远端只接受 {@code uri}；offset/limit 由实现本地切片。
     *
     * @param resourceUri 资源 URI
     * @param offset      本地切片起点
     * @param limit       本地切片长度，{@code <=0} 表示读到末尾
     * @return 正文或已分类的失败/缺席
     */
    ExternalNavigatorContent readContent(String resourceUri, int offset, int limit);

    /**
     * 远端是否可接受新的只读请求。必须与写端口共用同一套 API key 解析，
     * 禁止 ready 与实际请求各判一把钥匙。
     *
     * @return 可检索时为 true
     */
    boolean ready();
}
