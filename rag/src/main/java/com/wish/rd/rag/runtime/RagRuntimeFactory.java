package com.wish.rd.rag.runtime;

import com.wish.rd.rag.ingestion.IngestionPipeline;
import com.wish.rd.rag.ingestion.impl.SimpleIngestionPipeline;
import com.wish.rd.rag.vector.VectorStore;

/**
 * RAG 运行时工厂：集中装配摄取管线。
 *
 * <p>把散落在多个包里的组件（解析器、分块器）按典型组合装配好，避免调用方逐个 new。
 */
public final class RagRuntimeFactory {

    private RagRuntimeFactory() {
    }

    /**
     * 创建简易摄取管线（基于 {@link SimpleIngestionPipeline}）。
     *
     * @param vectorStore 目标向量库
     */
    public static IngestionPipeline ingestionPipeline(VectorStore vectorStore) {
        return new SimpleIngestionPipeline(vectorStore);
    }
}
