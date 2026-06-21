package com.wish.rd.rag.knowledge.store;

import com.wish.rd.rag.knowledge.KnowledgeBase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * 内存知识库 Store：用于单测和无数据库本地启动。
 */
public final class InMemoryKnowledgeBaseStore implements KnowledgeBaseStore {

    private final LinkedHashMap<String, KnowledgeBase> bases = new LinkedHashMap<>();

    @Override
    public synchronized KnowledgeBase save(KnowledgeBase base) {
        bases.put(base.id(), base);
        return base;
    }

    @Override
    public synchronized Optional<KnowledgeBase> findById(String id) {
        return Optional.ofNullable(bases.get(id));
    }

    @Override
    public synchronized List<KnowledgeBase> list() {
        return List.copyOf(bases.values());
    }

    @Override
    public synchronized void delete(String id) {
        bases.remove(id);
    }
}
