package com.wish.rd.rag.core.chunk;

public enum ChunkingMode {
    FIXED_SIZE,
    STRUCTURE_AWARE;

    public ChunkingOptions createDefaultOptions(int chunkSize, int overlapSize) {
        int resolvedChunkSize = chunkSize <= 0 ? 512 : chunkSize;
        int resolvedOverlap = Math.max(0, overlapSize);
        if (this == STRUCTURE_AWARE) {
            return new TextBoundaryOptions(40, resolvedChunkSize, resolvedChunkSize * 2, resolvedOverlap);
        }
        return new FixedSizeOptions(resolvedChunkSize, resolvedOverlap);
    }
}
