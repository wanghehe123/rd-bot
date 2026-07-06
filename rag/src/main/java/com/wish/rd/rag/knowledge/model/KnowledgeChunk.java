package com.wish.rd.rag.knowledge.model;

import java.util.Map;
import java.util.Objects;

public record KnowledgeChunk(
        String id,
        String documentId,
        String knowledgeBaseId,
        int index,
        String content,
        String knowledgeType,
        String sourceName,
        boolean enabled,
        Map<String, String> metadata
) {

    public KnowledgeChunk {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(knowledgeType, "knowledgeType must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public KnowledgeChunk withEnabled(boolean newEnabled) {
        return new KnowledgeChunk(
                id,
                documentId,
                knowledgeBaseId,
                index,
                content,
                knowledgeType,
                sourceName,
                newEnabled,
                metadata
        );
    }

    public KnowledgeChunk withContent(String newContent) {
        return new KnowledgeChunk(
                id,
                documentId,
                knowledgeBaseId,
                index,
                newContent,
                knowledgeType,
                sourceName,
                enabled,
                metadata
        );
    }

    public KnowledgeChunk withDocumentFields(String newKnowledgeType, String newSourceName) {
        return new KnowledgeChunk(
                id,
                documentId,
                knowledgeBaseId,
                index,
                content,
                newKnowledgeType,
                newSourceName,
                enabled,
                metadata
        );
    }
}
