package com.wish.rd.rag.knowledge.store;

import com.wish.rd.rag.knowledge.KnowledgeChunk;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 知识分块存储端口，维护文档到分块的从属关系。
 */
public interface KnowledgeChunkStore {

    KnowledgeChunk save(KnowledgeChunk chunk);

    void saveAll(Collection<KnowledgeChunk> chunks);

    Optional<KnowledgeChunk> findById(String id);

    List<KnowledgeChunk> listByDocumentId(String documentId);

    List<KnowledgeChunk> listAll();

    void delete(String chunkId);

    void deleteByDocumentId(String documentId);
}
