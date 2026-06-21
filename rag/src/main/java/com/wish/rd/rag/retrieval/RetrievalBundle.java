package com.wish.rd.rag.retrieval;

import com.wish.rd.framework.convention.RetrievedChunk;

import java.util.List;

public record RetrievalBundle(
        List<String> searchChannels,
        List<RetrievedChunk> chunks
) {

    public RetrievalBundle {
        searchChannels = searchChannels == null ? List.of() : List.copyOf(searchChannels);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
