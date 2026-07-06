package com.wish.rd.engine;

import com.wish.rd.engine.testflow.RagFullFlowTestEngine;
import com.wish.rd.engine.testflow.model.RagFullFlowTestResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagFullFlowEngineTest {

    @Test
    void runsDocumentWriteIngestionRetrievalAndContextPackagingInOneServiceFlow() {
        RagFullFlowTestEngine engine = new RagFullFlowTestEngine();

        RagFullFlowTestResult result = engine.run();

        assertEquals("INDEXED", result.documentStatus());
        assertEquals("NONE", result.guidanceAction());
        assertTrue(result.nodeTypes().contains("PARSER"));
        assertTrue(result.nodeTypes().contains("CHUNKER"));
        assertTrue(result.nodeTypes().contains("INDEXER"));
        assertTrue(result.searchChannels().contains("IntentDirectedVectorSearch"));
        assertTrue(result.searchChannels().contains("KeywordBM25Search"));
        assertTrue(result.searchChannels().contains("LogCenterSearch"));
        assertTrue(result.searchChannels().contains("CodeRepositorySearch"));
        assertTrue(result.retrievedKnowledgeTypes().contains("api"));
        assertTrue(result.retrievedKnowledgeTypes().contains("runtime-log"));
        assertTrue(result.retrievedKnowledgeTypes().contains("code-snippet"));
        assertTrue(result.summary().contains("OrderService.create"));
    }
}
