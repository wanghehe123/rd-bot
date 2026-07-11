package com.wish.rd.rag.retrieval.impl;

import com.wish.rd.rag.retrieval.SearchChannel;

import com.wish.rd.rag.vector.VectorStore;

import java.util.List;
import com.wish.rd.rag.retrieval.model.ChannelSearchResult;
import com.wish.rd.rag.retrieval.model.RetrievalRequest;

/**
 * 全局向量检索通道。
 *
 * <p>仅当未命中任何意图时启用，作为兜底检索：不限定知识库范围，在全库做语义检索。
 * 与 {@link IntentDirectedVectorSearchChannel} 互斥，二者不会同时启用。
 */
public final class GlobalVectorSearchChannel implements SearchChannel {

    private final VectorStore vectorStore;

    public GlobalVectorSearchChannel(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public String name() {
        return "GlobalVectorSearch";
    }

    /** 启用条件：未命中意图且项目未绑定知识库时，才允许全库兜底。 */
    @Override
    public boolean isEnabled(RetrievalRequest request) {
        return request.primaryIntent().isEmpty() && request.targetKnowledgeBaseIds().isEmpty();
    }

    @Override
    public ChannelSearchResult search(RetrievalRequest request) {
        // 知识库 ID 传空列表表示不限范围
        return new ChannelSearchResult(
                name(),
                vectorStore.vectorSearch(request.query(), List.of(), request.topK())
        );
    }
}
