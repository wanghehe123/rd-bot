package com.wish.rd.rag.retrieval.model;

import com.wish.rd.framework.convention.model.RetrievedChunk;

import java.util.List;

public record ChannelSearchResult(
        String channelName,
        List<RetrievedChunk> chunks
) {

    public ChannelSearchResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
