package com.wish.rd.rag.knowledge;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.core.chunk.ChunkingMode;
import com.wish.rd.rag.knowledge.store.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.vector.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeWorkspacePersistenceTest {

    @Test
    void shouldReloadKnowledgeStateFromStoresAndVectorStore() {
        InMemoryKnowledgeBaseStore baseStore = new InMemoryKnowledgeBaseStore();
        InMemoryKnowledgeDocumentStore documentStore = new InMemoryKnowledgeDocumentStore();
        InMemoryKnowledgeChunkStore chunkStore = new InMemoryKnowledgeChunkStore();
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        SnowflakeIdGenerator idGenerator = generatorWithMovingClock();

        KnowledgeWorkspace firstRuntime = KnowledgeWorkspace.withStores(
                vectorStore,
                idGenerator,
                baseStore,
                documentStore,
                chunkStore
        );
        KnowledgeBase base = firstRuntime.createBase(new CreateKnowledgeBaseCommand("支付系统", "生产知识库"));
        KnowledgeDocument document = firstRuntime.writeDocument(new WriteKnowledgeDocumentCommand(
                base.id(),
                "payment-api.md",
                "api",
                "text/markdown",
                "# API\nOrderService.create 校验 orders.amount".getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                64,
                8
        ));

        KnowledgeWorkspace reloadedRuntime = KnowledgeWorkspace.withStores(
                vectorStore,
                idGenerator,
                baseStore,
                documentStore,
                chunkStore
        );

        assertEquals(base, reloadedRuntime.getBase(base.id()));
        assertEquals(document.id(), reloadedRuntime.getDocument(document.id()).id());
        assertFalse(reloadedRuntime.listChunks(document.id()).isEmpty());
        assertTrue(reloadedRuntime.previewDocument(document.id()).contains("orders.amount"));
        assertFalse(reloadedRuntime.vectorStore().vectorSearch("orders.amount", java.util.List.of(base.id()), 3).isEmpty());
        assertNotEquals("kb-1", base.id());
        assertTrue(Long.parseLong(base.id()) > 0);
    }

    private SnowflakeIdGenerator generatorWithMovingClock() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
