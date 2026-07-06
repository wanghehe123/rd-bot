package com.wish.rd.rag.ingestion.model;

import com.wish.rd.rag.core.chunk.model.ChunkingMode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public record ManagedIngestionTaskCommand(
        String pipelineId,
        String knowledgeBaseId,
        String knowledgeType,
        String mimeType,
        String sourceFileName,
        String sourceType,
        String sourceLocation,
        byte[] content,
        ChunkingMode chunkingMode,
        int chunkSize,
        int overlapSize,
        Map<String, Object> metadata
) {

    public ManagedIngestionTaskCommand {
        if (pipelineId == null || pipelineId.isBlank()) {
            throw new IllegalArgumentException("pipelineId must not be blank");
        }
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new IllegalArgumentException("knowledgeBaseId must not be blank");
        }
        knowledgeType = knowledgeType == null || knowledgeType.isBlank() ? "document" : knowledgeType.strip();
        mimeType = mimeType == null || mimeType.isBlank() ? "text/plain" : mimeType.strip();
        sourceFileName = sourceFileName == null || sourceFileName.isBlank() ? "inline.txt" : sourceFileName.strip();
        sourceType = sourceType == null || sourceType.isBlank() ? "inline" : sourceType.strip();
        sourceLocation = sourceLocation == null ? "" : sourceLocation.strip();
        content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
        chunkingMode = chunkingMode == null ? ChunkingMode.STRUCTURE_AWARE : chunkingMode;
        chunkSize = chunkSize <= 0 ? 512 : chunkSize;
        overlapSize = Math.max(0, overlapSize);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }
}
