package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.knowledge.KnowledgeDocumentMutationEngine;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.knowledge.model.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeInventoryAuditStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeMutationTransactionAdapter;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillBatchReport;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillSettings;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeDocumentMutationBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillCommitResult;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionSupersedeBundle;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeProjectionBackfillEngineTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    void boundedBatchAppliesAtMostTheRequestedLimit() {
        Fixture fixture = Fixture.withPendingDocuments(3);
        InventoryBackfillBatchReport report = fixture.engine.backfillBatch(
                fixture.baseId, 2, NOW);

        assertEquals(2, report.attempted());
        assertEquals(2, report.applied());
        assertEquals("", report.stopReason());
        assertEquals(1, fixture.audit.nextBackfillCandidates(fixture.baseId, "", 10).size());
    }

    @Test
    void stopsTheBatchWhenInFlightCapIsAlreadyReached() {
        Fixture fixture = Fixture.withPendingDocuments(2);
        fixture.outbox.enqueue(pendingOutbox("evt-1", fixture.baseId, "99"));
        KnowledgeProjectionBackfillEngine capped = new KnowledgeProjectionBackfillEngine(
                fixture.audit, fixture.mutations, new InventoryBackfillSettings(20, 1));

        InventoryBackfillBatchReport report = capped.backfillBatch(fixture.baseId, 20, NOW);

        assertEquals(0, report.attempted());
        assertEquals(0, report.applied());
        assertEquals(KnowledgeProjectionBackfillEngine.IN_FLIGHT_CAP_REASON, report.stopReason());
        assertEquals(2, fixture.audit.nextBackfillCandidates(fixture.baseId, "", 10).size());
    }

    @Test
    void concurrentModificationAppearsInTheBatchReport() {
        Fixture fixture = Fixture.withPendingDocuments(1);
        KnowledgeDocument pending = fixture.audit.nextBackfillCandidates(fixture.baseId, "", 1).getFirst();
        KnowledgeMutationTransactionPort racing = new RacingTransactionPort(fixture.tx, fixture.documents);
        KnowledgeDocumentMutationEngine racingEngine = new KnowledgeDocumentMutationEngine(
                SnowflakeIdGenerator.defaultGenerator(),
                fixture.bases,
                fixture.documents,
                fixture.revisions,
                fixture.chunks,
                racing,
                KnowledgeProjectionWakePort.noop(),
                fixture.bindings
        );
        KnowledgeProjectionBackfillEngine engine = new KnowledgeProjectionBackfillEngine(
                fixture.audit, racingEngine, new InventoryBackfillSettings(20, 200));

        InventoryBackfillBatchReport report = engine.backfillBatch(fixture.baseId, 1, NOW);

        assertEquals(1, report.skippedConcurrentModification());
        assertEquals(InventoryBackfillStatus.SKIPPED_CONCURRENT_MODIFICATION, report.outcomes().getFirst().status());
        assertEquals(pending.id(), report.outcomes().getFirst().documentId());
    }

    private static KnowledgeExternalIndexOperation pendingOutbox(String eventId, String kb, String documentId) {
        return new KnowledgeExternalIndexOperation(
                eventId, "idem-" + eventId, KnowledgeExternalIndexBinding.OPENVIKING,
                ExternalKnowledgeOperationType.UPSERT_DOCUMENT, kb, documentId, 1L, "ck",
                "uri", "", "", ExternalKnowledgeOperationStatus.PENDING, "", "", "", 0L, 0, 8,
                NOW, 0L, "", "", 0L, NOW, NOW);
    }

    private static final class RacingTransactionPort implements KnowledgeMutationTransactionPort {
        private final KnowledgeMutationTransactionPort delegate;
        private final InMemoryKnowledgeDocumentStore documents;

        private RacingTransactionPort(
                KnowledgeMutationTransactionPort delegate,
                InMemoryKnowledgeDocumentStore documents
        ) {
            this.delegate = delegate;
            this.documents = documents;
        }

        @Override
        public KnowledgeDocument commit(KnowledgeDocumentMutationBundle bundle) {
            return delegate.commit(bundle);
        }

        @Override
        public KnowledgeProjectionBackfillCommitResult commitBackfill(KnowledgeProjectionBackfillBundle bundle) {
            KnowledgeDocument current = documents.findById(bundle.documentId()).orElseThrow();
            documents.save(current.withRowVersion(current.rowVersion() + 1L), documents.rawContent(current.id()));
            return delegate.commitBackfill(bundle);
        }

        @Override
        public void commitSupersede(KnowledgeProjectionSupersedeBundle bundle) {
            delegate.commitSupersede(bundle);
        }
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
        final KnowledgeDocumentMutationEngine mutations;
        final KnowledgeInventoryAuditStore audit;
        final KnowledgeProjectionBackfillEngine engine;
        final String baseId;

        private Fixture() {
            this.tx = new InMemoryKnowledgeMutationTransactionAdapter(
                    documents, revisions, chunks, vectors, bindings, outbox, bases);
            this.mutations = new KnowledgeDocumentMutationEngine(
                    SnowflakeIdGenerator.defaultGenerator(),
                    bases, documents, revisions, chunks, tx, KnowledgeProjectionWakePort.noop(), bindings);
            this.audit = new InMemoryKnowledgeInventoryAuditStore(documents, bases, bindings, outbox);
            this.engine = new KnowledgeProjectionBackfillEngine(
                    audit, mutations, new InventoryBackfillSettings(20, 200));
            this.baseId = new KnowledgeWorkspace(
                    vectors, SnowflakeIdGenerator.defaultGenerator(), bases, documents, chunks, revisions, mutations)
                    .createBase(new CreateKnowledgeBaseCommand("backfill", "WP-6")).id();
        }

        static Fixture withPendingDocuments(int count) {
            Fixture fixture = new Fixture();
            for (int index = 0; index < count; index++) {
                String documentId = String.valueOf(1000 + index);
                fixture.documents.save(new KnowledgeDocument(
                        documentId, fixture.baseId, "p" + index + ".md", "api", "text/markdown",
                        KnowledgeDocumentStatus.INDEXED, true, 1, List.of(), NOW, "LOCAL", "", "", "",
                        "checksum-" + index, "preview", NOW, 0L, 2L, "", "", 0L, 0L, "", 0L, false), "body " + index);
                fixture.chunks.save(new KnowledgeChunk(
                        "c-" + documentId, documentId, fixture.baseId, 0, "body " + index, "api",
                        "p" + index + ".md", true, Map.of("documentId", documentId)));
                fixture.vectors.index(List.of(new RetrievedChunk(
                        "c-" + documentId, "body " + index, fixture.baseId, "api", "p" + index + ".md",
                        1.0d, Map.of("documentId", documentId))));
            }
            return fixture;
        }
    }
}
