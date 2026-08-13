package com.wish.rd.rag.knowledge;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.knowledge.model.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.projection.KnowledgeMutationTransactionPort;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionWakePort;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeMutationTransactionAdapter;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillOutcome;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillStatus;
import com.wish.rd.rag.knowledge.projection.model.InventorySupersedeOutcome;
import com.wish.rd.rag.knowledge.projection.model.InventorySupersedeStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeDocumentMutationBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillCommitResult;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionSupersedeBundle;
import com.wish.rd.rag.knowledge.projection.model.SupersedeTarget;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeDocumentBackfillMutationTest {

    private static final long NOW = 1_900_000_000_000L;
    private static final String IDENTITY = SourceIdentityKeys.from("FEISHU", "tok-1", "https://example.feishu.cn/wiki/tok-1");

    @Test
    void backfillLeavesChunksAndSyncVersionUnchangedAndReusesChecksumRevision() {
        Fixture fixture = Fixture.empty();
        KnowledgeDocument document = fixture.seedUnbound("201", 4L, "checksum-keep", "chunk-a", "chunk-b");
        KnowledgeDocumentRevision existing = new KnowledgeDocumentRevision(
                "rev-keep", document.id(), 4L, "src-rev", "checksum-keep", "text/markdown",
                "body", "rd-bot-default", "1", NOW - 10);
        fixture.revisions.save(existing);

        InventoryBackfillOutcome outcome = fixture.engine.backfillProjection(document.id(), NOW);

        assertEquals(InventoryBackfillStatus.APPLIED, outcome.status());
        KnowledgeDocument after = fixture.documents.findById(document.id()).orElseThrow();
        assertEquals(4L, after.syncVersion());
        assertEquals(2, after.chunkCount());
        assertEquals(Set.of("chunk-a", "chunk-b"), chunkIds(fixture, document.id()));
        assertEquals(2, fixture.vectors.allChunks().size());
        assertEquals("rev-keep", after.currentRevisionId());
        assertEquals(1, fixture.revisions.listByDocumentId(document.id()).size());
        assertEquals(IDENTITY, after.sourceIdentityKey());
        assertEquals(ExternalKnowledgeDesiredState.PRESENT,
                fixture.bindings.findByProviderAndDocumentId("OPENVIKING", document.id()).orElseThrow().desiredState());
        assertEquals(1, fixture.outbox.listByDocumentId(document.id()).size());
        assertEquals(ExternalKnowledgeOperationType.UPSERT_DOCUMENT,
                fixture.outbox.listByDocumentId(document.id()).getFirst().operationType());
        assertEquals(4L, fixture.outbox.listByDocumentId(document.id()).getFirst().syncVersion());
    }

    @Test
    void backfillDoesNotTouchObservedStateWhenBindingIsAlreadyInSync() {
        Fixture fixture = Fixture.empty();
        KnowledgeDocument document = fixture.seedUnbound("202", 3L, "ck-sync", "chunk-s");
        KnowledgeExternalIndexBinding inSync = new KnowledgeExternalIndexBinding(
                KnowledgeExternalIndexBinding.OPENVIKING, document.id(), fixture.baseId,
                "uri", "owner", ExternalKnowledgeDesiredState.PRESENT, 3L, "ck-sync",
                ExternalKnowledgeObservedState.READY, 9L, "ck-sync",
                ExternalKnowledgeProjectionStatus.IN_SYNC, "op-1", "task-1", "fp",
                NOW - 5, NOW - 1, "", "", 7L, NOW - 20, NOW - 1);
        fixture.bindings.save(inSync);
        fixture.outbox.enqueue(new KnowledgeExternalIndexOperation(
                "evt-sync", "idem-sync", KnowledgeExternalIndexBinding.OPENVIKING,
                ExternalKnowledgeOperationType.UPSERT_DOCUMENT, fixture.baseId, document.id(), 3L, "ck-sync",
                "uri", "", "", ExternalKnowledgeOperationStatus.SUCCEEDED, "", "op-1", "", 0L, 1, 8,
                NOW, NOW, "", "", 2L, NOW, NOW));
        int outboxSize = fixture.outbox.listByDocumentId(document.id()).size();

        InventoryBackfillOutcome outcome = fixture.engine.backfillProjection(document.id(), NOW);

        assertEquals(InventoryBackfillStatus.SKIPPED_ALREADY_BOUND, outcome.status());
        KnowledgeExternalIndexBinding after = fixture.bindings
                .findByProviderAndDocumentId("OPENVIKING", document.id()).orElseThrow();
        assertEquals(ExternalKnowledgeObservedState.READY, after.observedState());
        assertEquals(9L, after.observedVersion());
        assertEquals(7L, after.rowVersion());
        assertEquals(outboxSize, fixture.outbox.listByDocumentId(document.id()).size());
        assertEquals(3L, fixture.documents.findById(document.id()).orElseThrow().syncVersion());
        assertEquals(Set.of("chunk-s"), chunkIds(fixture, document.id()));
    }

    @Test
    void concurrentModificationIsReportedNotSwallowed() {
        Fixture fixture = Fixture.empty();
        KnowledgeDocument document = fixture.seedUnbound("203", 1L, "ck-race", "chunk-r");
        KnowledgeMutationTransactionPort racing = new KnowledgeMutationTransactionPort() {
            @Override
            public KnowledgeDocument commit(KnowledgeDocumentMutationBundle bundle) {
                return fixture.tx.commit(bundle);
            }

            @Override
            public KnowledgeProjectionBackfillCommitResult commitBackfill(KnowledgeProjectionBackfillBundle bundle) {
                KnowledgeDocument current = fixture.documents.findById(bundle.documentId()).orElseThrow();
                fixture.documents.save(
                        current.withRowVersion(current.rowVersion() + 1L),
                        fixture.documents.rawContent(current.id()));
                return fixture.tx.commitBackfill(bundle);
            }

            @Override
            public void commitSupersede(KnowledgeProjectionSupersedeBundle bundle) {
                fixture.tx.commitSupersede(bundle);
            }
        };
        KnowledgeDocumentMutationEngine engine = new KnowledgeDocumentMutationEngine(
                SnowflakeIdGenerator.defaultGenerator(),
                fixture.bases, fixture.documents, fixture.revisions, fixture.chunks,
                racing, KnowledgeProjectionWakePort.noop(), fixture.bindings);

        InventoryBackfillOutcome outcome = engine.backfillProjection(document.id(), NOW);

        assertEquals(InventoryBackfillStatus.SKIPPED_CONCURRENT_MODIFICATION, outcome.status());
        assertTrue(fixture.bindings.findByProviderAndDocumentId("OPENVIKING", document.id()).isEmpty());
        assertTrue(fixture.outbox.listByDocumentId(document.id()).isEmpty());
    }

    @Test
    void supersedeMarksLosersAndDeletesBoundCopyWhilePreservingLocalRows() {
        Fixture fixture = Fixture.empty();
        KnowledgeDocument oldest = fixture.seedUnbound("301", 1L, "ck-a", "c-301");
        KnowledgeDocument middle = fixture.seedUnbound("302", 1L, "ck-b", "c-302");
        KnowledgeDocument newest = fixture.seedUnbound("303", 1L, "ck-c", "c-303");
        fixture.documents.save(oldest.withIdentityRevision(IDENTITY, "r1").withRowVersion(0L), "a");
        fixture.documents.save(middle.withIdentityRevision(IDENTITY, "r2").withRowVersion(2L), "b");
        fixture.documents.save(newest.withIdentityRevision(IDENTITY, "r3").withRowVersion(1L), "c");
        fixture.bindings.save(new KnowledgeExternalIndexBinding(
                KnowledgeExternalIndexBinding.OPENVIKING, middle.id(), fixture.baseId,
                "uri-302", "owner", ExternalKnowledgeDesiredState.PRESENT, 1L, "ck-b",
                ExternalKnowledgeObservedState.READY, 1L, "ck-b",
                ExternalKnowledgeProjectionStatus.IN_SYNC, "", "", "", 0L, 0L, "", "", 4L, NOW, NOW));

        InventorySupersedeOutcome outcome = fixture.engine.supersedeDuplicate(
                newest.id(),
                List.of(new SupersedeTarget(oldest.id(), 0L), new SupersedeTarget(middle.id(), 2L)),
                NOW);

        assertEquals(InventorySupersedeStatus.APPLIED, outcome.status());
        assertEquals(newest.id(), fixture.documents.findById(oldest.id()).orElseThrow().supersededByDocumentId());
        assertEquals(newest.id(), fixture.documents.findById(middle.id()).orElseThrow().supersededByDocumentId());
        assertTrue(fixture.documents.findById(oldest.id()).isPresent());
        assertEquals(1, fixture.chunks.listByDocumentId(oldest.id()).size());
        assertEquals(ExternalKnowledgeDesiredState.ABSENT,
                fixture.bindings.findByProviderAndDocumentId("OPENVIKING", middle.id()).orElseThrow().desiredState());
        assertTrue(fixture.outbox.listByDocumentId(middle.id()).stream().anyMatch(operation ->
                operation.operationType() == ExternalKnowledgeOperationType.DELETE_DOCUMENT));
        assertTrue(fixture.outbox.listByDocumentId(oldest.id()).isEmpty());
        assertTrue(fixture.documents.findById(newest.id()).orElseThrow().visible());
    }

    @Test
    void mismatchedLoserRowVersionAbortsTheEntireSupersede() {
        Fixture fixture = Fixture.empty();
        KnowledgeDocument survivor = fixture.seedUnbound("401", 1L, "ck-s", "c-401");
        KnowledgeDocument loserA = fixture.seedUnbound("402", 1L, "ck-l1", "c-402");
        KnowledgeDocument loserB = fixture.seedUnbound("403", 1L, "ck-l2", "c-403");
        fixture.documents.save(survivor.withIdentityRevision(IDENTITY, "rs").withRowVersion(0L), "s");
        fixture.documents.save(loserA.withIdentityRevision(IDENTITY, "ra").withRowVersion(5L), "a");
        fixture.documents.save(loserB.withIdentityRevision(IDENTITY, "rb").withRowVersion(1L), "b");

        InventorySupersedeOutcome outcome = fixture.engine.supersedeDuplicate(
                survivor.id(),
                List.of(new SupersedeTarget(loserA.id(), 5L), new SupersedeTarget(loserB.id(), 99L)),
                NOW);

        assertEquals(InventorySupersedeStatus.CONFLICT, outcome.status());
        assertTrue(fixture.documents.findById(loserA.id()).orElseThrow().visible());
        assertTrue(fixture.documents.findById(loserB.id()).orElseThrow().visible());
        assertEquals("", fixture.documents.findById(loserA.id()).orElseThrow().supersededByDocumentId());
        assertEquals("", fixture.documents.findById(loserB.id()).orElseThrow().supersededByDocumentId());
    }

    private static Set<String> chunkIds(Fixture fixture, String documentId) {
        return fixture.chunks.listByDocumentId(documentId).stream().map(KnowledgeChunk::id).collect(Collectors.toSet());
    }

    private static final class Fixture {
        final InMemoryKnowledgeBaseStore bases = new InMemoryKnowledgeBaseStore();
        final InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        final InMemoryKnowledgeDocumentRevisionStore revisions = new InMemoryKnowledgeDocumentRevisionStore();
        final InMemoryKnowledgeChunkStore chunks = new InMemoryKnowledgeChunkStore();
        final InMemoryVectorStore vectors = new InMemoryVectorStore();
        final InMemoryKnowledgeExternalIndexBindingStore bindings = new InMemoryKnowledgeExternalIndexBindingStore();
        final InMemoryKnowledgeExternalIndexOutboxStore outbox = new InMemoryKnowledgeExternalIndexOutboxStore();
        final InMemoryKnowledgeMutationTransactionAdapter tx;
        final KnowledgeDocumentMutationEngine engine;
        final String baseId;

        private Fixture() {
            this.tx = new InMemoryKnowledgeMutationTransactionAdapter(
                    documents, revisions, chunks, vectors, bindings, outbox, bases);
            this.engine = new KnowledgeDocumentMutationEngine(
                    SnowflakeIdGenerator.defaultGenerator(),
                    bases, documents, revisions, chunks, tx, KnowledgeProjectionWakePort.noop(), bindings);
            this.baseId = new KnowledgeWorkspace(
                    vectors, SnowflakeIdGenerator.defaultGenerator(), bases, documents, chunks, revisions, engine)
                    .createBase(new CreateKnowledgeBaseCommand("backfill-mut", "WP-6")).id();
        }

        static Fixture empty() {
            return new Fixture();
        }

        KnowledgeDocument seedUnbound(
                String documentId,
                long syncVersion,
                String checksum,
                String... chunkIds
        ) {
            KnowledgeDocument document = new KnowledgeDocument(
                    documentId, baseId, documentId + ".md", "api", "text/markdown",
                    KnowledgeDocumentStatus.INDEXED, true, chunkIds.length, List.of(), NOW,
                    "FEISHU", "tok-1", "https://example.feishu.cn/wiki/tok-1", "src-rev",
                    checksum, "preview", NOW, 0L, syncVersion, "", "", 0L, 0L, "", 0L, false);
            documents.save(document, "body");
            for (int index = 0; index < chunkIds.length; index++) {
                chunks.save(new KnowledgeChunk(
                        chunkIds[index], documentId, baseId, index, "body-" + index, "api",
                        documentId + ".md", true, Map.of("documentId", documentId)));
                vectors.index(List.of(new RetrievedChunk(
                        chunkIds[index], "body-" + index, baseId, "api", documentId + ".md",
                        1.0d, Map.of("documentId", documentId))));
            }
            return document;
        }
    }
}
