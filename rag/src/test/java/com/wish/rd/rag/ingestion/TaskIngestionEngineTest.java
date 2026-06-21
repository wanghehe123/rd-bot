package com.wish.rd.rag.ingestion;

import com.wish.rd.rag.core.chunk.ChunkingMode;
import com.wish.rd.rag.vector.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskIngestionEngineTest {

    @Test
    void executesDefaultFetcherParserChunkerIndexerPipelineAndIndexesChunks() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        TaskIngestionEngine engine = TaskIngestionEngine.inMemory(vectorStore);

        IngestionTaskResult result = engine.execute(
                PipelineDefinition.defaultDocumentPipeline(),
                new IngestionTaskCommand(
                        "task-1",
                        "payment-api.md",
                        "payment-system",
                        "api",
                        "text/markdown",
                        """
                        # 支付系统 API

                        POST /api/orders 是下单接口。
                        OrderService.create 负责校验 orders.amount。
                        """.getBytes(StandardCharsets.UTF_8),
                        ChunkingMode.STRUCTURE_AWARE,
                        72,
                        8
                )
        );

        assertEquals(IngestionStatus.COMPLETED, result.status());
        assertEquals("default-document-pipeline", result.pipelineId());
        assertEquals(List.of(
                IngestionNodeType.FETCHER,
                IngestionNodeType.PARSER,
                IngestionNodeType.CHUNKER,
                IngestionNodeType.INDEXER
        ), result.nodeLogs().stream().map(IngestionNodeLog::nodeType).toList());
        assertFalse(result.chunks().isEmpty());
        assertTrue(result.rawText().contains("OrderService.create"));
        assertFalse(vectorStore.vectorSearch("orders.amount", List.of("payment-system"), 3).isEmpty());
    }

    @Test
    void rejectsPipelineCyclesBeforeRunningNodes() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        TaskIngestionEngine engine = TaskIngestionEngine.inMemory(vectorStore);

        PipelineDefinition cyclicPipeline = new PipelineDefinition(
                "cycle",
                "cycle",
                "invalid",
                List.of(
                        new NodeConfig("parser", IngestionNodeType.PARSER, "chunker"),
                        new NodeConfig("chunker", IngestionNodeType.CHUNKER, "parser")
                )
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> engine.execute(
                cyclicPipeline,
                new IngestionTaskCommand(
                        "task-2",
                        "broken.md",
                        "payment-system",
                        "api",
                        "text/markdown",
                        "broken".getBytes(StandardCharsets.UTF_8),
                        ChunkingMode.STRUCTURE_AWARE,
                        72,
                        8
                )
        ));
        assertTrue(exception.getMessage().contains("cycle"));
    }
}
