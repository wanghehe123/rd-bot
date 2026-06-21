package com.wish.rd.rag.knowledge.store;

import com.wish.rd.rag.knowledge.KnowledgeDocument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 内存知识文档 Store：持有文档元数据与原文内容。
 */
public final class InMemoryKnowledgeDocumentStore implements KnowledgeDocumentStore {

    private final LinkedHashMap<String, KnowledgeDocument> documents = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> rawContents = new LinkedHashMap<>();

    @Override
    public synchronized KnowledgeDocument save(KnowledgeDocument document, String rawContent) {
        documents.put(document.id(), document);
        if (rawContent != null) {
            rawContents.put(document.id(), rawContent);
        }
        return document;
    }

    @Override
    public synchronized Optional<KnowledgeDocument> findById(String id) {
        return Optional.ofNullable(documents.get(id));
    }

    @Override
    public synchronized Optional<KnowledgeDocument> findBySource(
            String knowledgeBaseId,
            String sourceType,
            String sourceToken,
            String sourceUrl
    ) {
        String normalizedType = sourceType == null ? "" : sourceType.toUpperCase(Locale.ROOT);
        String normalizedToken = sourceToken == null ? "" : sourceToken;
        String normalizedUrl = sourceUrl == null ? "" : sourceUrl;
        return documents.values().stream()
                .filter(document -> document.knowledgeBaseId().equals(knowledgeBaseId))
                .filter(document -> document.sourceType().equals(normalizedType))
                .filter(document -> (!normalizedToken.isBlank() && document.sourceToken().equals(normalizedToken))
                        || (!normalizedUrl.isBlank() && document.sourceUrl().equals(normalizedUrl)))
                .findFirst();
    }

    @Override
    public synchronized List<KnowledgeDocument> listByKnowledgeBaseId(String knowledgeBaseId) {
        return documents.values().stream()
                .filter(document -> document.knowledgeBaseId().equals(knowledgeBaseId))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeDocument> listAll() {
        return List.copyOf(documents.values());
    }

    @Override
    public synchronized String rawContent(String documentId) {
        return rawContents.getOrDefault(documentId, "");
    }

    @Override
    public synchronized void delete(String documentId) {
        documents.remove(documentId);
        rawContents.remove(documentId);
    }
}
