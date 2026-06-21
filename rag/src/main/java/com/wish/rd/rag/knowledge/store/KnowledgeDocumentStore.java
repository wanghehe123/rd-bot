package com.wish.rd.rag.knowledge.store;

import com.wish.rd.rag.knowledge.KnowledgeDocument;

import java.util.List;
import java.util.Optional;

/**
 * 知识文档存储端口，负责文档元数据、原文预览和来源去重查询。
 */
public interface KnowledgeDocumentStore {

    KnowledgeDocument save(KnowledgeDocument document, String rawContent);

    Optional<KnowledgeDocument> findById(String id);

    Optional<KnowledgeDocument> findBySource(
            String knowledgeBaseId,
            String sourceType,
            String sourceToken,
            String sourceUrl
    );

    List<KnowledgeDocument> listByKnowledgeBaseId(String knowledgeBaseId);

    List<KnowledgeDocument> listAll();

    String rawContent(String documentId);

    void delete(String documentId);
}
