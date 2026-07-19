package com.wish.rd.rag.retrieval.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combined evidence plus every enabled channel outcome for durable retrieval-state reporting. */
public record RetrievalExecutionResult(
        RetrievalBundle bundle,
        Map<String, ChannelSearchOutcome> channelOutcomes
) {

    public RetrievalExecutionResult {
        bundle = bundle == null ? new RetrievalBundle(List.of(), List.of()) : bundle;
        channelOutcomes = channelOutcomes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(channelOutcomes));
    }

    public boolean hasSuccessfulChannel() {
        return channelOutcomes.values().stream().anyMatch(outcome -> !outcome.failed());
    }
}
