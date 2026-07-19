package com.wish.rd.rag.retrieval.run;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.retrieval.model.ChannelSearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic RRF fusion keeps incomparable channel scores out of the ranking decision and,
 * when the evidence budget allows, preserves the top candidate from every healthy channel.
 */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {
    }

    public static List<RetrievedChunk> fuse(List<ChannelSearchResult> channelResults, int rankConstant, int limit) {
        int safeRankConstant = Math.max(1, rankConstant);
        int safeLimit = Math.max(1, limit);
        List<ChannelSearchResult> safeResults = channelResults == null ? List.of() : channelResults;
        Map<String, ScoredChunk> scores = new LinkedHashMap<>();
        for (ChannelSearchResult result : safeResults) {
            if (result == null) {
                continue;
            }
            List<RetrievedChunk> chunks = result.chunks();
            for (int index = 0; index < chunks.size(); index++) {
                RetrievedChunk chunk = chunks.get(index);
                if (chunk == null || chunk.chunkId().isBlank()) {
                    continue;
                }
                double contribution = 1.0d / (safeRankConstant + index + 1.0d);
                scores.merge(chunk.chunkId(), new ScoredChunk(chunk, contribution),
                        (left, right) -> left.add(right.chunk(), right.score()));
            }
        }
        List<ScoredChunk> ranked = new ArrayList<>(scores.values());
        ranked.sort(Comparator.comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(value -> value.chunk().chunkId()));
        Set<String> channelLeaders = new LinkedHashSet<>();
        for (ChannelSearchResult result : safeResults) {
            if (result == null) {
                continue;
            }
            result.chunks().stream()
                    .filter(chunk -> chunk != null && !chunk.chunkId().isBlank())
                    .findFirst()
                    .ifPresent(chunk -> channelLeaders.add(chunk.chunkId()));
        }
        Set<String> selectedIds = new LinkedHashSet<>();
        ranked.stream()
                .map(value -> value.chunk().chunkId())
                .filter(channelLeaders::contains)
                .limit(safeLimit)
                .forEach(selectedIds::add);
        ranked.stream()
                .map(value -> value.chunk().chunkId())
                .filter(chunkId -> selectedIds.size() < safeLimit)
                .forEach(selectedIds::add);
        return ranked.stream()
                .filter(value -> selectedIds.contains(value.chunk().chunkId()))
                .map(ScoredChunk::chunk)
                .toList();
    }

    private record ScoredChunk(RetrievedChunk chunk, double score) {
        private ScoredChunk add(RetrievedChunk candidate, double contribution) {
            RetrievedChunk selected = chunk.score() >= candidate.score() ? chunk : candidate;
            return new ScoredChunk(selected, score + contribution);
        }
    }
}
