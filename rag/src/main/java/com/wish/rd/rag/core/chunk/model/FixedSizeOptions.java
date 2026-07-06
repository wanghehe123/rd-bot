package com.wish.rd.rag.core.chunk.model;

import java.util.Map;

public record FixedSizeOptions(int chunkSize, int overlapSize) implements ChunkingOptions {

    public FixedSizeOptions {
        chunkSize = chunkSize <= 0 ? 512 : chunkSize;
        overlapSize = Math.max(0, Math.min(overlapSize, Math.max(0, chunkSize - 1)));
    }

    @Override
    public Map<String, Integer> toConfigMap() {
        return Map.of("chunkSize", chunkSize, "overlapSize", overlapSize);
    }
}
