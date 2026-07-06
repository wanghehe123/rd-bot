package com.wish.rd.rag.ingestion.model;

import com.wish.rd.rag.core.chunk.model.ChunkingMode;

import java.util.Arrays;
import java.util.Objects;

public record IngestionTaskCommand(
        String taskId,
        String sourceName,
        String knowledgeBaseId,
        String knowledgeType,
        String mimeType,
        byte[] content,
        ChunkingMode chunkingMode,
        int chunkSize,
        int overlapSize
) {

    public IngestionTaskCommand {
        taskId = taskId == null || taskId.isBlank() ? "task-inline" : taskId;
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        knowledgeType = knowledgeType == null || knowledgeType.isBlank() ? "document" : knowledgeType;
        mimeType = mimeType == null || mimeType.isBlank() ? "text/plain" : mimeType;
        content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
        chunkingMode = chunkingMode == null ? ChunkingMode.STRUCTURE_AWARE : chunkingMode;
        chunkSize = chunkSize <= 0 ? 512 : chunkSize;
        overlapSize = Math.max(0, overlapSize);
    }
}
