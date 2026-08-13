package com.wish.rd.rag.knowledge.store;

import com.wish.rd.rag.knowledge.model.KnowledgeDocument;

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

    /**
     * 回填窄 CAS：仅在身份仍为空且行版本匹配时写入身份与当前修订。
     *
     * @return 命中并更新时为 true
     */
    boolean updateIdentityIfUnchanged(
            String documentId,
            long expectedRowVersion,
            String sourceIdentityKey,
            String currentRevisionId,
            long nowEpochMillis
    );

    /**
     * 去重窄 CAS：仅在行版本匹配且尚未被取代、未删除时标记 superseded。
     *
     * @return 命中并更新时为 true
     */
    boolean markSupersededIfVersionMatches(
            String documentId,
            long expectedRowVersion,
            String survivorDocumentId,
            long nowEpochMillis
    );
}
