package com.wish.rd.rag.knowledge.store;

import com.wish.rd.rag.knowledge.KnowledgeChunk;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * 内存知识分块 Store：保持插入顺序，适配现有管理接口。
 */
public final class InMemoryKnowledgeChunkStore implements KnowledgeChunkStore {

    private final LinkedHashMap<String, KnowledgeChunk> chunks = new LinkedHashMap<>();

    @Override
    public synchronized KnowledgeChunk save(KnowledgeChunk chunk) {
        chunks.put(chunk.id(), chunk);
        return chunk;
    }

    @Override
    public synchronized void saveAll(Collection<KnowledgeChunk> newChunks) {
        if (newChunks == null) {
            return;
        }
        newChunks.forEach(chunk -> chunks.put(chunk.id(), chunk));
    }

    @Override
    public synchronized Optional<KnowledgeChunk> findById(String id) {
        return Optional.ofNullable(chunks.get(id));
    }

    @Override
    public synchronized List<KnowledgeChunk> listByDocumentId(String documentId) {
        return chunks.values().stream()
                .filter(chunk -> chunk.documentId().equals(documentId))
                .sorted(java.util.Comparator.comparingInt(KnowledgeChunk::index))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeChunk> listAll() {
        return List.copyOf(chunks.values());
    }

    @Override
    public synchronized void delete(String chunkId) {
        chunks.remove(chunkId);
    }

    @Override
    public synchronized void deleteByDocumentId(String documentId) {
        chunks.values().removeIf(chunk -> chunk.documentId().equals(documentId));
    }
}
