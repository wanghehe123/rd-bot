package com.wish.rd.rag.retrieval;

/**
 * 检索通道统一接口。
 *
 * <p>每个通道负责一类证据来源（向量、关键词、日志、代码），由
 * {@link MultiChannelRetrievalEngine} 根据请求上下文动态筛选启用并并行调度。
 */
public interface SearchChannel {

    /** 通道名称，用于结果合并后的来源标识。 */
    String name();

    /**
     * 判断当前请求下该通道是否启用。
     *
     * <p>典型策略：意图命中→启用意图导向/日志/代码通道；意图缺失→启用全局向量；关键词始终启用。
     *
     * @param request 检索请求
     * @return true 表示参与本轮检索
     */
    boolean isEnabled(RetrievalRequest request);

    /**
     * 执行实际检索，返回该通道命中的证据块。
     *
     * @param request 检索请求
     * @return 通道检索结果（含通道名与证据块列表）
     */
    ChannelSearchResult search(RetrievalRequest request);
}
