package com.wish.rd.rag.core.chunk;

import java.util.Map;

public record TextBoundaryOptions(
        int minChars,
        int targetChars,
        int maxChars,
        int overlapChars
) implements ChunkingOptions {

    public TextBoundaryOptions {
        targetChars = targetChars <= 0 ? 512 : targetChars;
        minChars = minChars <= 0 ? Math.min(80, targetChars) : minChars;
        maxChars = maxChars < targetChars ? targetChars : maxChars;
        overlapChars = Math.max(0, overlapChars);
    }

    @Override
    public Map<String, Integer> toConfigMap() {
        return Map.of(
                "minChars", minChars,
                "targetChars", targetChars,
                "maxChars", maxChars,
                "overlapChars", overlapChars
        );
    }
}
