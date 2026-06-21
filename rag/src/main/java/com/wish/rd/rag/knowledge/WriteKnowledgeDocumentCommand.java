package com.wish.rd.rag.knowledge;

import com.wish.rd.rag.core.chunk.ChunkingMode;

import java.util.Arrays;
import java.util.Objects;

public record WriteKnowledgeDocumentCommand(
        String knowledgeBaseId,
        String sourceName,
        String knowledgeType,
        String mimeType,
        byte[] content,
        ChunkingMode chunkingMode,
        int chunkSize,
        int overlapSize
) {

    public WriteKnowledgeDocumentCommand {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        if (sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName must not be blank");
        }
        knowledgeType = knowledgeType == null || knowledgeType.isBlank() ? "document" : knowledgeType;
        mimeType = mimeType == null || mimeType.isBlank() ? "text/plain" : mimeType;
        content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
        chunkingMode = chunkingMode == null ? ChunkingMode.STRUCTURE_AWARE : chunkingMode;
        chunkSize = chunkSize <= 0 ? 512 : chunkSize;
        overlapSize = Math.max(0, overlapSize);
    }
}
