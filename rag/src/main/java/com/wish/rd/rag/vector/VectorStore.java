package com.wish.rd.rag.vector;

import com.wish.rd.framework.convention.model.RetrievedChunk;

import java.util.Collection;
import java.util.List;

/**
 * 向量库端口：封装知识分块的索引、删除和检索能力。
 *
 * <p>RAG 核心只依赖此端口。内存实现用于单测与本地无依赖启动，PostgreSQL/pgvector
 * 等生产实现放在 bootstrap 适配层。
 */
public interface VectorStore {

    void index(Collection<RetrievedChunk> newChunks);

    void replace(RetrievedChunk chunk);

    void removeChunks(Collection<String> chunkIds);

    List<RetrievedChunk> vectorSearch(String query, Collection<String> knowledgeBaseIds, int topK);

    List<RetrievedChunk> keywordSearch(String query, Collection<String> knowledgeBaseIds, int topK);

    List<RetrievedChunk> allChunks();
}
