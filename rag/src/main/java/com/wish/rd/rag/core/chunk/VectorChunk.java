package com.wish.rd.rag.core.chunk;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

public record VectorChunk(
        String chunkId,
        int index,
        String content,
        Map<String, String> metadata,
        float[] embedding
) {

    public VectorChunk {
        chunkId = chunkId == null || chunkId.isBlank() ? UUID.randomUUID().toString() : chunkId;
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        embedding = embedding == null ? new float[0] : Arrays.copyOf(embedding, embedding.length);
    }

    public static VectorChunk of(int index, String content) {
        return new VectorChunk(UUID.randomUUID().toString(), index, content, Map.of(), new float[0]);
    }

    public VectorChunk withMetadata(Map<String, String> newMetadata) {
        return new VectorChunk(chunkId, index, content, newMetadata, embedding);
    }
}
