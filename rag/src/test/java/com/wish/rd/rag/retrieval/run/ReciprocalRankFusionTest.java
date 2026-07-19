package com.wish.rd.rag.retrieval.run;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.retrieval.model.ChannelSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReciprocalRankFusionTest {

    @Test
    void rewardsEvidenceThatIsReturnedByMultipleChannelsWithoutComparingRawScores() {
        RetrievedChunk shared = chunk("shared", 0.1d);
        RetrievedChunk vectorOnly = chunk("vector-only", 1000d);
        RetrievedChunk keywordOnly = chunk("keyword-only", 1000d);

        List<RetrievedChunk> result = ReciprocalRankFusion.fuse(List.of(
                new ChannelSearchResult("vector", List.of(shared, vectorOnly)),
                new ChannelSearchResult("keyword", List.of(shared, keywordOnly))
        ), 60, 10);

        assertEquals("shared", result.getFirst().chunkId());
        assertTrue(result.stream().anyMatch(chunk -> "vector-only".equals(chunk.chunkId())));
    }

    @Test
    void preservesAtLeastOneTopCandidateFromEveryChannelWhenTheBudgetAllows() {
        RetrievedChunk shared = chunk("shared", 0.1d);
        RetrievedChunk shared2 = chunk("shared-2", 0.2d);
        RetrievedChunk shared3 = chunk("shared-3", 0.3d);
        RetrievedChunk shared4 = chunk("shared-4", 0.4d);
        RetrievedChunk code = chunk("code", 9.0d);
        RetrievedChunk log = chunk("log", 8.0d);

        List<RetrievedChunk> result = ReciprocalRankFusion.fuse(List.of(
                new ChannelSearchResult("vector", List.of(shared, shared2, shared3, shared4)),
                new ChannelSearchResult("keyword", List.of(shared, shared2, shared3, shared4)),
                new ChannelSearchResult("code", List.of(code)),
                new ChannelSearchResult("log", List.of(log))
        ), 60, 4);

        assertEquals("shared", result.getFirst().chunkId());
        assertTrue(result.stream().anyMatch(chunk -> "code".equals(chunk.chunkId())));
        assertTrue(result.stream().anyMatch(chunk -> "log".equals(chunk.chunkId())));
    }

    private static RetrievedChunk chunk(String id, double score) {
        return new RetrievedChunk(id, id + " content", "kb-1", "document", id + ".md", score, Map.of());
    }
}
