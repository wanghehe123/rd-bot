package com.wish.rd.rag.ingestion;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public record DocumentSource(
        String sourceName,
        String knowledgeBaseId,
        String knowledgeType,
        byte[] content,
        Map<String, String> metadata
) {

    public DocumentSource {
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(knowledgeType, "knowledgeType must not be null");
        content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static DocumentSource systemDocument(
            String sourceName,
            String knowledgeBaseId,
            String knowledgeType,
            byte[] content
    ) {
        return new DocumentSource(sourceName, knowledgeBaseId, knowledgeType, content, Map.of());
    }
}
