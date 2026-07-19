package com.wish.rd.rag.retrieval;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.retrieval.model.ChannelSearchResult;
import com.wish.rd.rag.retrieval.model.RetrievalExecutionResult;
import com.wish.rd.rag.retrieval.model.RetrievalRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiChannelRetrievalEngineResilienceTest {

    @Test
    void preservesSuccessfulEvidenceWhenAnotherEnabledChannelFails() {
        SearchChannel healthy = new SearchChannel() {
            @Override
            public String name() {
                return "healthy";
            }

            @Override
            public boolean isEnabled(RetrievalRequest request) {
                return true;
            }

            @Override
            public ChannelSearchResult search(RetrievalRequest request) {
                return new ChannelSearchResult(name(), List.of(new RetrievedChunk(
                        "chunk-1", "evidence", "kb-1", "document", "source.md", 1.0d, Map.of()
                )));
            }
        };
        SearchChannel unavailable = new SearchChannel() {
            @Override
            public String name() {
                return "unavailable";
            }

            @Override
            public boolean isEnabled(RetrievalRequest request) {
                return true;
            }

            @Override
            public ChannelSearchResult search(RetrievalRequest request) {
                throw new IllegalStateException("provider unavailable");
            }
        };

        RetrievalExecutionResult result = new MultiChannelRetrievalEngine(List.of(healthy, unavailable)).retrieveDetailed(
                new RetrievalRequest("query", Optional.empty(), 8)
        );

        assertEquals(List.of("healthy"), result.bundle().searchChannels());
        assertEquals(1, result.bundle().chunks().size());
        assertFalse(result.channelOutcomes().get("healthy").failed());
        assertTrue(result.channelOutcomes().get("unavailable").failed());
    }
}
