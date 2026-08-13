package com.wish.rd.rag.knowledge;

import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.ingestion.model.IngestionTaskCommand;
import com.wish.rd.rag.ingestion.model.PipelineDefinition;
import com.wish.rd.rag.knowledge.model.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentSource;
import com.wish.rd.rag.knowledge.model.WriteKnowledgeDocumentCommand;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionWakePort;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeMutationTransactionAdapter;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeDocumentMutationEngineTest {

    @Test
    void shouldEnqueuePendingUpsertWhenWritingASourceDocument() {
        Fixture fixture = Fixture.create(KnowledgeProjectionWakePort.noop());
        KnowledgeDocument document = write(fixture, "body v1");

        var operations = fixture.outbox.listByDocumentId(document.id());
        assertEquals(1, operations.size());
        assertEquals(ExternalKnowledgeOperationStatus.PENDING, operations.getFirst().status());
        assertEquals(ExternalKnowledgeOperationType.UPSERT_DOCUMENT, operations.getFirst().operationType());
        assertEquals(1L, document.syncVersion());
    }

    @Test
    void shouldNotEnqueueWhenChecksumIsUnchanged() {
        Fixture fixture = Fixture.create(KnowledgeProjectionWakePort.noop());
        KnowledgeDocument first = write(fixture, "same body");
        KnowledgeDocument second = write(fixture, "same body");

        assertEquals(first.id(), second.id());
        assertEquals(1L, second.syncVersion());
        assertEquals(1, fixture.outbox.listByDocumentId(first.id()).size());
    }

    @Test
    void shouldKeepPendingWhenWakeExecutorRejectsAfterCommit() {
        AtomicInteger wakeCalls = new AtomicInteger();
        Fixture fixture = Fixture.create(() -> {
            wakeCalls.incrementAndGet();
            throw new RejectedExecutionException("worker saturated");
        });
        KnowledgeDocument document = write(fixture, "body after reject");

        assertEquals(1, wakeCalls.get());
        assertEquals(ExternalKnowledgeOperationStatus.PENDING,
                fixture.outbox.listByDocumentId(document.id()).getFirst().status());
        assertEquals(document.id(), fixture.workspace.getDocument(document.id()).id());
    }

    @Test
    void shouldNotEnqueueWhenRechunking() {
        Fixture fixture = Fixture.create(KnowledgeProjectionWakePort.noop());
        KnowledgeDocument document = write(fixture, "# Rechunk\nOrderService.create validates amount.");
        KnowledgeDocument rechunked = fixture.engine.rechunkDocument(document.id(), ChunkingMode.STRUCTURE_AWARE, 32, 4);

        assertEquals(document.id(), rechunked.id());
        assertEquals(document.syncVersion(), rechunked.syncVersion());
        assertEquals(1, fixture.outbox.listByDocumentId(document.id()).size());
        assertEquals(ExternalKnowledgeOperationType.UPSERT_DOCUMENT,
                fixture.outbox.listByDocumentId(document.id()).getFirst().operationType());
    }

    @Test
    void shouldEnqueueDeleteWhenSoftDeletingADocument() {
        Fixture fixture = Fixture.create(KnowledgeProjectionWakePort.noop());
        KnowledgeDocument document = write(fixture, "to delete");
        fixture.engine.deleteDocument(document.id());

        var operations = fixture.outbox.listByDocumentId(document.id());
        assertEquals(2, operations.size());
        assertTrue(operations.stream().anyMatch(operation ->
                operation.operationType() == ExternalKnowledgeOperationType.DELETE_DOCUMENT
                        && operation.status() == ExternalKnowledgeOperationStatus.PENDING
                        && operation.syncVersion() == document.syncVersion() + 1L));
        assertTrue(fixture.workspace.listDocuments(document.knowledgeBaseId()).isEmpty());
    }

    @Test
    void shouldEnqueueAbsentDeleteWhenDisablingADocument() {
        Fixture fixture = Fixture.create(KnowledgeProjectionWakePort.noop());
        KnowledgeDocument document = write(fixture, "to disable");

        KnowledgeDocument disabled = fixture.engine.setDocumentEnabled(document.id(), false);

        assertTrue(!disabled.enabled());
        assertEquals(document.syncVersion() + 1L, disabled.syncVersion());
        assertTrue(fixture.outbox.listByDocumentId(document.id()).stream().anyMatch(operation ->
                operation.operationType() == ExternalKnowledgeOperationType.DELETE_DOCUMENT
                        && operation.syncVersion() == disabled.syncVersion()
                        && operation.status() == ExternalKnowledgeOperationStatus.PENDING));
        assertEquals(disabled.syncVersion(), fixture.engine.setDocumentEnabled(document.id(), false).syncVersion());
    }

    @Test
    void shouldEnqueuePresentUpsertWhenReenablingADocument() {
        Fixture fixture = Fixture.create(KnowledgeProjectionWakePort.noop());
        KnowledgeDocument document = write(fixture, "to reenable");
        fixture.engine.setDocumentEnabled(document.id(), false);

        KnowledgeDocument enabled = fixture.engine.setDocumentEnabled(document.id(), true);

        assertTrue(enabled.enabled());
        assertEquals(document.syncVersion() + 2L, enabled.syncVersion());
        assertTrue(fixture.outbox.listByDocumentId(document.id()).stream().anyMatch(operation ->
                operation.operationType() == ExternalKnowledgeOperationType.UPSERT_DOCUMENT
                        && operation.syncVersion() == enabled.syncVersion()
                        && operation.status() == ExternalKnowledgeOperationStatus.PENDING));
    }

    @Test
    void shouldNotEnqueueWhenRenamingOrMutatingChunks() {
        Fixture fixture = Fixture.create(KnowledgeProjectionWakePort.noop());
        KnowledgeDocument document = write(fixture, "metadata and chunks");
        KnowledgeChunk manual = fixture.engine.createChunk(document.id(), "manual-1", 9, "manual note");

        KnowledgeDocument renamed = fixture.engine.updateDocument(document.id(), "renamed.md", "api-v2");
        fixture.engine.setChunkEnabled(manual.id(), false);
        fixture.engine.batchSetChunksEnabled(document.id(), List.of(), true);
        assertTrue(fixture.engine.deleteChunk(document.id(), manual.id()));

        assertEquals(document.id(), renamed.id());
        assertEquals(document.syncVersion(), renamed.syncVersion());
        assertEquals("renamed.md", renamed.sourceName());
        assertEquals("api-v2", renamed.knowledgeType());
        assertEquals(1, fixture.outbox.listByDocumentId(document.id()).size());
        assertTrue(fixture.workspace.listChunks(document.id()).stream()
                .noneMatch(chunk -> chunk.id().equals(manual.id())));
        assertTrue(fixture.workspace.getDocument(document.id()).localOnlyOverride());
    }

    @Test
    void shouldReplaceExistingVectorWhenUpdatingAChunk() {
        Fixture fixture = Fixture.create(KnowledgeProjectionWakePort.noop());
        KnowledgeDocument document = write(fixture, "chunk body for replace");
        KnowledgeChunk chunk = fixture.workspace.listChunks(document.id()).getFirst();

        fixture.engine.updateChunk(document.id(), chunk.id(), "updated chunk body for replace");

        var vectors = fixture.workspace.vectorStore().allChunks().stream()
                .filter(retrieved -> retrieved.chunkId().equals(chunk.id()))
                .toList();
        assertEquals(1, vectors.size());
        assertTrue(vectors.getFirst().content().contains("updated chunk body"));
        assertEquals(1, fixture.outbox.listByDocumentId(document.id()).size());
    }

    @Test
    void shouldEnqueueKnowledgeBaseDeleteAtThePersistedBaseVersion() {
        Fixture fixture = Fixture.create(KnowledgeProjectionWakePort.noop());
        KnowledgeDocument document = write(fixture, "kb child");
        String baseId = document.knowledgeBaseId();
        fixture.engine.deleteBase(baseId);

        var kbDeletes = fixture.outbox.listAll().stream()
                .filter(operation -> operation.operationType() == ExternalKnowledgeOperationType.DELETE_KNOWLEDGE_BASE)
                .toList();
        assertEquals(1, kbDeletes.size());
        assertEquals(fixture.workspace.inspectBase(baseId).syncVersion(), kbDeletes.getFirst().syncVersion());
        assertEquals(ExternalKnowledgeOperationStatus.PENDING, kbDeletes.getFirst().status());
    }

    @Test
    void shouldEnqueueKnowledgeBaseDeleteWhenTheBaseHasNoDocuments() {
        Fixture fixture = Fixture.create(KnowledgeProjectionWakePort.noop());
        fixture.engine.deleteBase(fixture.baseId);

        var kbDeletes = fixture.outbox.listAll().stream()
                .filter(operation -> operation.operationType() == ExternalKnowledgeOperationType.DELETE_KNOWLEDGE_BASE)
                .toList();
        assertEquals(1, kbDeletes.size());
        assertEquals(fixture.workspace.inspectBase(fixture.baseId).syncVersion(), kbDeletes.getFirst().syncVersion());
        assertEquals(ExternalKnowledgeOperationStatus.PENDING, kbDeletes.getFirst().status());
    }

    private static KnowledgeDocument write(Fixture fixture, String body) {
        KnowledgeDocumentSource source = new KnowledgeDocumentSource(
                "FEISHU",
                "token-engine",
                "https://example.feishu.cn/wiki/token-engine",
                "rev-1",
                System.currentTimeMillis(),
                0L
        );
        return fixture.engine.writeDocumentIfChanged(
                new WriteKnowledgeDocumentCommand(
                        fixture.baseId,
                        "source.md",
                        "api",
                        "text/markdown",
                        body.getBytes(StandardCharsets.UTF_8),
                        ChunkingMode.STRUCTURE_AWARE,
                        64,
                        8
                ),
                source
        );
    }

    private static final class Fixture {
        final KnowledgeWorkspace workspace;
        final KnowledgeDocumentMutationEngine engine;
        final KnowledgeExternalIndexOutboxStore outbox;
        final String baseId;

        private Fixture(
                KnowledgeWorkspace workspace,
                KnowledgeDocumentMutationEngine engine,
                KnowledgeExternalIndexOutboxStore outbox,
                String baseId
        ) {
            this.workspace = workspace;
            this.engine = engine;
            this.outbox = outbox;
            this.baseId = baseId;
        }

        static Fixture create(KnowledgeProjectionWakePort wakePort) {
            InMemoryKnowledgeBaseStore bases = new InMemoryKnowledgeBaseStore();
            InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
            InMemoryKnowledgeChunkStore chunks = new InMemoryKnowledgeChunkStore();
            InMemoryKnowledgeDocumentRevisionStore revisions = new InMemoryKnowledgeDocumentRevisionStore();
            InMemoryVectorStore vectors = new InMemoryVectorStore();
            InMemoryKnowledgeExternalIndexBindingStore bindings = new InMemoryKnowledgeExternalIndexBindingStore();
            InMemoryKnowledgeExternalIndexOutboxStore outbox = new InMemoryKnowledgeExternalIndexOutboxStore();
            var tx = new InMemoryKnowledgeMutationTransactionAdapter(
                    documents, revisions, chunks, vectors, bindings, outbox, bases);
            var idGenerator = com.wish.rd.framework.id.SnowflakeIdGenerator.defaultGenerator();
            KnowledgeDocumentMutationEngine engine = new KnowledgeDocumentMutationEngine(
                    idGenerator, bases, documents, revisions, chunks, tx, wakePort);
            KnowledgeWorkspace workspace = KnowledgeWorkspace.withStores(
                    vectors, idGenerator, bases, documents, chunks, revisions, engine);
            String baseId = workspace.createBase(new CreateKnowledgeBaseCommand("engine", "WP-2")).id();
            return new Fixture(workspace, engine, outbox, baseId);
        }
    }
}
