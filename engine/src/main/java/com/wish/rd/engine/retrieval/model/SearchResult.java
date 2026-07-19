package com.wish.rd.engine.retrieval.model;

import com.wish.rd.rag.context.model.RoleContextEvidence;

import java.util.List;

/** Evidence candidates and channel audit rows returned inside one already-resolved scope. */
public record SearchResult(
        List<RoleContextEvidence> candidates,
        List<ChannelAudit> channels
) {
    public SearchResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        channels = channels == null ? List.of() : List.copyOf(channels);
    }

    public static SearchResult empty() {
        return new SearchResult(List.of(), List.of());
    }
}
