package com.wish.rd.rag.ingestion;

import com.wish.rd.rag.core.chunk.ChunkingMode;

import java.util.Arrays;
import java.util.Objects;

public record DocumentIngestionCommand(
        String sourceName,
        String knowledgeBaseId,
        String knowledgeType,
        String mimeType,
        byte[] content,
        ChunkingMode chunkingMode,
        int chunkSize,
        int overlapSize
) {

    public DocumentIngestionCommand {
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(knowledgeType, "knowledgeType must not be null");
        mimeType = mimeType == null || mimeType.isBlank() ? "text/plain" : mimeType;
        content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
        chunkingMode = chunkingMode == null ? ChunkingMode.FIXED_SIZE : chunkingMode;
        chunkSize = chunkSize <= 0 ? 512 : chunkSize;
        overlapSize = Math.max(0, overlapSize);
    }
}
