package com.wish.rd.rag.retrieval.impl;

import com.wish.rd.rag.retrieval.SearchChannel;

import com.wish.rd.rag.vector.VectorStore;
import com.wish.rd.rag.retrieval.model.ChannelSearchResult;
import com.wish.rd.rag.retrieval.model.RetrievalRequest;

/**
 * 意图导向向量检索通道。
 *
 * <p>仅当主意图命中且意图关联了知识库时启用，把检索范围限定在意图绑定的知识库内，
 * 提升证据与目标系统的相关性。
 */
public final class IntentDirectedVectorSearchChannel implements SearchChannel {

    private final VectorStore vectorStore;

    public IntentDirectedVectorSearchChannel(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public String name() {
        return "IntentDirectedVectorSearch";
    }

    /** 启用条件：命中主意图且意图绑定了至少一个知识库。 */
    @Override
    public boolean isEnabled(RetrievalRequest request) {
        return request.primaryIntent().isPresent() && !request.targetKnowledgeBaseIds().isEmpty();
    }

    @Override
    public ChannelSearchResult search(RetrievalRequest request) {
        return new ChannelSearchResult(
                name(),
                // 仅在意图绑定的知识库范围内做向量（语义）检索
                vectorStore.vectorSearch(request.query(), request.targetKnowledgeBaseIds(), request.topK())
        );
    }
}
