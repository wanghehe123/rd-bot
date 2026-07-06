package com.wish.rd.rag.knowledge.store;

import com.wish.rd.rag.knowledge.model.KnowledgeBase;

import java.util.List;
import java.util.Optional;

/**
 * 知识库主表存储端口。
 */
public interface KnowledgeBaseStore {

    KnowledgeBase save(KnowledgeBase base);

    Optional<KnowledgeBase> findById(String id);

    List<KnowledgeBase> list();

    void delete(String id);
}
