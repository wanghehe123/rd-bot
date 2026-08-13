package com.wish.rd.rag.knowledge.model;

import java.util.LinkedHashMap;
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

    public static final String PROJECTION_MODE_KEY = "rd.projection_mode";
    public static final String LOCAL_ONLY_OVERRIDE = "LOCAL_ONLY_OVERRIDE";

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

    public KnowledgeChunk withLocalOnlyOverride() {
        LinkedHashMap<String, String> copied = new LinkedHashMap<>(metadata);
        copied.put(PROJECTION_MODE_KEY, LOCAL_ONLY_OVERRIDE);
        return new KnowledgeChunk(
                id,
                documentId,
                knowledgeBaseId,
                index,
                content,
                knowledgeType,
                sourceName,
                enabled,
                copied
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
