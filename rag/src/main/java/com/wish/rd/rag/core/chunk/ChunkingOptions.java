package com.wish.rd.rag.core.chunk;

import java.util.Map;

public sealed interface ChunkingOptions permits FixedSizeOptions, TextBoundaryOptions {

    Map<String, Integer> toConfigMap();
}
