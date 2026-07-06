package com.wish.rd.engine;

import com.wish.rd.engine.admin.knowledge.KnowledgeAdminEngine;
import com.wish.rd.engine.admin.knowledge.model.KnowledgeAdminOverview;
import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.knowledge.model.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.knowledge.model.WriteKnowledgeDocumentCommand;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeAdminFlowTest {

    @Test
    void managesKnowledgeDocumentsChunksAndAdminOverviewInsideSingleServiceRuntime() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase knowledgeBase = workspace.createBase(new CreateKnowledgeBaseCommand(
                "支付系统",
                "支付接口、订单异常和修复经验"
        ));

        KnowledgeDocument document = workspace.writeDocument(new WriteKnowledgeDocumentCommand(
                knowledgeBase.id(),
                "payment-api.md",
                "api",
                "text/markdown",
                """
                # 支付系统 API

                POST /api/orders 是下单接口。
                当 orders.amount 为空时，下单接口返回 500。

                ## 修复经验

                优先检查 OrderService.create 的入参校验和订单表写入逻辑。
                """.getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                72,
                8
        ));

        List<KnowledgeChunk> chunks = workspace.listChunks(document.id());

        assertTrue(Long.parseLong(knowledgeBase.id()) > 0);
        assertTrue(Long.parseLong(document.id()) > 0);
        assertFalse("kb-1".equals(knowledgeBase.id()));
        assertFalse("doc-1".equals(document.id()));
        assertEquals(KnowledgeDocumentStatus.INDEXED, document.status());
        assertEquals(List.of(document), workspace.listDocuments(knowledgeBase.id()));
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.getFirst().enabled());
        assertTrue(workspace.previewDocument(document.id()).contains("OrderService.create"));
        assertTrue(workspace.searchDocuments(knowledgeBase.id(), "OrderService").stream()
                .anyMatch(found -> found.id().equals(document.id())));

        workspace.setChunkEnabled(chunks.getFirst().id(), false);
        assertFalse(workspace.listChunks(document.id()).getFirst().enabled());

        KnowledgeChunk manualChunk = workspace.createChunk(
                document.id(),
                "manual-check",
                99,
                "手工补充 Chunk：OrderService.create 必须先校验金额"
        );
        assertEquals("manual-check", manualChunk.id());
        assertTrue(workspace.getChunk("manual-check").content().contains("手工补充"));

        KnowledgeChunk updatedManualChunk = workspace.updateChunk(
                document.id(),
                "manual-check",
                "手工更新 Chunk：orders.amount 为空时返回参数错误"
        );
        assertTrue(updatedManualChunk.content().contains("参数错误"));

        workspace.batchSetChunksEnabled(document.id(), List.of("manual-check"), false);
        assertFalse(workspace.getChunk("manual-check").enabled());

        workspace.batchSetChunksEnabled(document.id(), List.of(), true);
        assertTrue(workspace.listChunks(document.id()).stream().allMatch(KnowledgeChunk::enabled));

        workspace.updateDocument(document.id(), "payment-api-v2.md", "api-v2");
        assertEquals("payment-api-v2.md", workspace.getDocument(document.id()).sourceName());
        assertEquals("api-v2", workspace.getDocument(document.id()).knowledgeType());

        workspace.deleteChunk(document.id(), "manual-check");
        assertTrue(workspace.listChunks(document.id()).stream().noneMatch(chunk -> chunk.id().equals("manual-check")));

        workspace.setDocumentEnabled(document.id(), false);
        assertFalse(workspace.getDocument(document.id()).enabled());

        KnowledgeAdminOverview overview = new KnowledgeAdminEngine(workspace).overview();
        assertEquals(1, overview.knowledgeBaseCount());
        assertEquals(1, overview.documentCount());
        assertEquals(1, overview.indexedDocumentCount());
        assertEquals(chunks.size(), overview.chunkCount());
        assertEquals(chunks.size(), overview.enabledChunkCount());
        assertEquals(chunks.size(), overview.vectorChunkCount());

        workspace.deleteDocument(document.id());
        assertTrue(workspace.listDocuments(knowledgeBase.id()).isEmpty());
    }
}
