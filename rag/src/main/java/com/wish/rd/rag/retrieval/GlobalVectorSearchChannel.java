package com.wish.rd.rag.retrieval;

import com.wish.rd.rag.vector.InMemoryVectorStore;

import java.util.List;

/**
 * 全局向量检索通道。
 *
 * <p>仅当未命中任何意图时启用，作为兜底检索：不限定知识库范围，在全库做语义检索。
 * 与 {@link IntentDirectedVectorSearchChannel} 互斥，二者不会同时启用。
 */
public final class GlobalVectorSearchChannel implements SearchChannel {

    private final InMemoryVectorStore vectorStore;

    public GlobalVectorSearchChannel(InMemoryVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public String name() {
        return "GlobalVectorSearch";
    }

    /** 启用条件：未命中任何意图（兜底场景）。 */
    @Override
    public boolean isEnabled(RetrievalRequest request) {
        return request.primaryIntent().isEmpty();
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
