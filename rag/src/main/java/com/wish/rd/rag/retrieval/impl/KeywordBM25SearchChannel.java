package com.wish.rd.rag.retrieval.impl;

import com.wish.rd.rag.retrieval.SearchChannel;

import com.wish.rd.rag.vector.VectorStore;
import com.wish.rd.rag.retrieval.model.ChannelSearchResult;
import com.wish.rd.rag.retrieval.model.RetrievalRequest;

/**
 * 关键词（BM25 风格）检索通道。
 *
 * <p>始终启用，作为向量检索的补充：向量偏语义相似，关键词偏精确命中，
 * 二者融合能兼顾"同义表达"与"专有名词/接口名"两类检索需求。
 */
public final class KeywordBM25SearchChannel implements SearchChannel {

    private final VectorStore vectorStore;

    public KeywordBM25SearchChannel(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public String name() {
        return "KeywordBM25Search";
    }

    /** 关键词检索始终启用。 */
    @Override
    public boolean isEnabled(RetrievalRequest request) {
        return true;
    }

    @Override
    public ChannelSearchResult search(RetrievalRequest request) {
        // 命中意图时限定在其知识库范围，否则全库检索
        return new ChannelSearchResult(
                name(),
                vectorStore.keywordSearch(request.query(), request.targetKnowledgeBaseIds(), request.topK())
        );
    }
}
