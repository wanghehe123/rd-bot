package com.wish.rd.adapter;

import com.wish.rd.framework.convention.RetrievedChunk;

import java.util.List;

/**
 * 代码仓库检索端口：按 {@link CodeSearchQuery} 在指定仓库中检索相关代码片段。
 *
 * <p>RAG 能力层只定义契约，具体实现由外部适配器（当前为测试通道的 mock）提供。
 * 被 {@link com.wish.rd.rag.retrieval.CodeRepositorySearchChannel} 用于拉取"代码证据"。
 */
public interface CodeRepositorySearchPort {

    /**
     * 检索代码片段。
     *
     * @param query 代码查询条件（仓库 ID、查询文本、文件过滤、topK）
     * @return 命中的代码块列表（包装为 {@link RetrievedChunk}）
     */
    List<RetrievedChunk> search(CodeSearchQuery query);
}
