package com.wish.rd.rag.intent.impl;

import com.wish.rd.rag.intent.IntentNodeStore;
import com.wish.rd.rag.intent.model.ManagedIntentNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * memory 模式下的意图节点存储。
 *
 * <p>仅用于本地零配置、单元测试和显式 {@code rd.knowledge.store=memory}；
 * 生产可见的管理端数据必须使用 PostgreSQL 适配器。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryIntentNodeStore implements IntentNodeStore {

    private final LinkedHashMap<String, ManagedIntentNode> nodes = new LinkedHashMap<>();

    @Override
    public synchronized ManagedIntentNode save(ManagedIntentNode node) {
        ManagedIntentNode snapshot = node.withoutChildren();
        nodes.put(snapshot.id(), snapshot);
        return snapshot;
    }

    @Override
    public synchronized Optional<ManagedIntentNode> findById(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    @Override
    public synchronized Optional<ManagedIntentNode> findByIntentCode(String intentCode) {
        return nodes.values().stream()
                .filter(node -> node.intentCode().equals(intentCode))
                .findFirst();
    }

    @Override
    public synchronized List<ManagedIntentNode> list() {
        return nodes.values().stream().map(ManagedIntentNode::withoutChildren).toList();
    }

    @Override
    public synchronized void delete(String id) {
        nodes.remove(id);
    }
}
