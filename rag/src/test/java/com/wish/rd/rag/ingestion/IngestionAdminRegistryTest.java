package com.wish.rd.rag.ingestion;

import com.wish.rd.rag.core.chunk.ChunkingMode;
import com.wish.rd.rag.knowledge.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.KnowledgeBase;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionAdminRegistryTest {

    @Test
    void createsPipelineExecutesTaskAndStoresKnowledgeDocumentWithNodeLogs() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("支付系统", "支付 API 文档"));
        IngestionAdminRegistry registry = IngestionAdminRegistry.inMemory(workspace);

        ManagedIngestionPipeline pipeline = registry.createPipeline(new IngestionPipelineCommand(
                "支付文档摄取",
                "Fetcher -> Parser -> Chunker -> Indexer",
                List.of(
                        new IngestionPipelineNodeCommand("fetcher", "FETCHER", "parser"),
                        new IngestionPipelineNodeCommand("parser", "PARSER", "chunker"),
                        new IngestionPipelineNodeCommand("chunker", "CHUNKER", "indexer"),
                        new IngestionPipelineNodeCommand("indexer", "INDEXER", null)
                )
        ));

        ManagedIngestionTask task = registry.executeTask(new ManagedIngestionTaskCommand(
                pipeline.id(),
                base.id(),
                "api",
                "text/markdown",
                "payment-api.md",
                "inline",
                "inline://payment-api.md",
                """
                # 支付 API

                OrderService.create 必须校验 orders.amount。
                """.getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                72,
                8,
                Map.of("operator", "test")
        ));

        assertEquals(IngestionStatus.COMPLETED, task.status());
        assertEquals(pipeline.id(), task.pipelineId());
        assertEquals("payment-api.md", task.sourceFileName());
        assertTrue(task.chunkCount() > 0);
        assertEquals(1, workspace.listDocuments(base.id()).size());
        assertEquals(workspace.listDocuments(base.id()).getFirst().id(), task.documentId());
        assertFalse(workspace.listChunks(task.documentId()).isEmpty());

        List<ManagedIngestionTaskNode> nodes = registry.listTaskNodes(task.id());
        assertEquals(List.of("FETCHER", "PARSER", "CHUNKER", "INDEXER"),
                nodes.stream().map(ManagedIngestionTaskNode::nodeType).toList());
        assertTrue(nodes.stream().allMatch(node -> "SUCCESS".equals(node.status())));
        assertNotNull(registry.getTask(task.id()).completedAtEpochMillis());
        assertEquals(1, registry.pageTasks(null, 1, 10).total());
    }
}
