package com.wish.rd.rag.retrieval;

import com.wish.rd.framework.convention.RetrievedChunk;

import java.util.List;

public record ChannelSearchResult(
        String channelName,
        List<RetrievedChunk> chunks
) {

    public ChannelSearchResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
