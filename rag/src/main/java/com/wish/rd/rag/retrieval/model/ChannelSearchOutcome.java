package com.wish.rd.rag.retrieval.model;

/** Outcome of a single enabled channel; failures are data so healthy channels can still contribute. */
public record ChannelSearchOutcome(
        String channelName,
        ChannelSearchResult result,
        boolean failed,
        String errorCategory,
        String errorMessage,
        long durationMillis
) {

    public ChannelSearchOutcome {
        channelName = channelName == null ? "" : channelName.strip();
        result = result == null ? new ChannelSearchResult(channelName, java.util.List.of()) : result;
        errorCategory = errorCategory == null ? "" : errorCategory.strip();
        errorMessage = errorMessage == null ? "" : errorMessage.strip();
        durationMillis = Math.max(0L, durationMillis);
    }

    public int chunkCount() {
        return result.chunks().size();
    }
}
