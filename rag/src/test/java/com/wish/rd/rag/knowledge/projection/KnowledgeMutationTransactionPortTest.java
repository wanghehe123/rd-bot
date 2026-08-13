package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexSettleCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeDocumentMutationBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeMutationTransactionAdapter;
import com.wish.rd.rag.vector.VectorStore;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeMutationTransactionPortTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String PROVIDER = "OPENVIKING";

    @Test
    void shouldCommitDocumentRevisionChunksBindingAndOutboxTogether() {
        Fixture fixture = Fixture.empty();
        KnowledgeDocumentMutationBundle bundle = fixture.sourceUpsertBundle("checksum-v1");

        fixture.port.commit(bundle);

        assertEquals(bundle.document().id(), fixture.documents.findById("201").orElseThrow().id());
        assertEquals(1, fixture.revisions.listByDocumentId("201").size());
        assertEquals(1, fixture.chunks.listByDocumentId("201").size());
        assertEquals(1, fixture.vectors.allChunks().size());
        assertEquals(ExternalKnowledgeDesiredState.PRESENT,
                fixture.bindings.findByProviderAndDocumentId(PROVIDER, "201").orElseThrow().desiredState());
        assertEquals(ExternalKnowledgeOperationStatus.PENDING,
                fixture.outbox.findById("301").orElseThrow().status());
    }

    @Test
    void shouldRollBackEveryWriteWhenALaterStepFails() {
        Fixture fixture = Fixture.empty();
        fixture.vectors = new ThrowingVectorStore();
        fixture.port = fixture.portWithCurrentStores();
        KnowledgeDocumentMutationBundle bundle = fixture.sourceUpsertBundle("checksum-v1");

        assertThrows(IllegalStateException.class, () -> fixture.port.commit(bundle));

        assertTrue(fixture.documents.listAll().isEmpty());
        assertTrue(fixture.revisions.listByDocumentId("201").isEmpty());
        assertTrue(fixture.chunks.listAll().isEmpty());
        assertTrue(fixture.vectors.allChunks().isEmpty());
        assertTrue(fixture.bindings.findByProviderAndDocumentId(PROVIDER, "201").isEmpty());
        assertTrue(fixture.outbox.findById("301").isEmpty());
    }

    @Test
    void shouldAbsorbDuplicateEnqueueOnIdempotencyKeyWithoutCreatingASecondPendingRow() {
        Fixture fixture = Fixture.empty();
        fixture.port.commit(fixture.sourceUpsertBundle("checksum-v1"));

        KnowledgeExternalIndexOperation duplicate = fixture.outboxOperation("302", "checksum-v1");
        KnowledgeExternalIndexOperation absorbed = fixture.outbox.enqueue(duplicate);

        assertEquals("301", absorbed.eventId());
        assertEquals(1, fixture.outbox.listByDocumentId("201").size());
        assertEquals(ExternalKnowledgeOperationStatus.PENDING, absorbed.status());
    }

    @Test
    void shouldLetOnlyOneWorkerClaimAPendingOperation() throws Exception {
        Fixture fixture = Fixture.empty();
        fixture.port.commit(fixture.sourceUpsertBundle("checksum-v1"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<KnowledgeExternalIndexOperation>> first = pool.submit(() -> {
                ready.countDown();
                go.await();
                return fixture.outbox.claimBatch("worker-a", NOW, NOW + 60_000L, 1);
            });
            Future<List<KnowledgeExternalIndexOperation>> second = pool.submit(() -> {
                ready.countDown();
                go.await();
                return fixture.outbox.claimBatch("worker-b", NOW, NOW + 60_000L, 1);
            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();
            List<KnowledgeExternalIndexOperation> a = first.get(2, TimeUnit.SECONDS);
            List<KnowledgeExternalIndexOperation> b = second.get(2, TimeUnit.SECONDS);
            assertEquals(1, a.size() + b.size());
            KnowledgeExternalIndexOperation claimed = a.isEmpty() ? b.getFirst() : a.getFirst();
            assertEquals(ExternalKnowledgeOperationStatus.CLAIMED, claimed.status());
            assertTrue(fixture.outbox.claimBatch("worker-c", NOW, NOW + 60_000L, 1).isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldRejectSettleFromAnExpiredLeaseOwner() {
        Fixture fixture = Fixture.empty();
        fixture.port.commit(fixture.sourceUpsertBundle("checksum-v1"));
        KnowledgeExternalIndexOperation claimed = fixture.outbox.claimBatch(
                "worker-old", NOW, NOW + 1_000L, 1).getFirst();

        assertTrue(fixture.outbox.settle(
                claimed.eventId(),
                ExternalKnowledgeOperationStatus.CLAIMED,
                "worker-old",
                claimed.rowVersion(),
                ExternalIndexSettleCommand.succeeded(),
                NOW + 2_000L
        ).isEmpty());
        assertEquals(ExternalKnowledgeOperationStatus.CLAIMED, fixture.outbox.findById("301").orElseThrow().status());
    }

    @Test
    void shouldReclaimExpiredLeaseAndPreventTheOldOwnerFromSettling() {
        Fixture fixture = Fixture.empty();
        fixture.port.commit(fixture.sourceUpsertBundle("checksum-v1"));
        KnowledgeExternalIndexOperation firstClaim = fixture.outbox.claimBatch(
                "worker-old", NOW, NOW + 1_000L, 1).getFirst();
        KnowledgeExternalIndexOperation secondClaim = fixture.outbox.claimBatch(
                "worker-new", NOW + 2_000L, NOW + 62_000L, 1).getFirst();

        assertEquals("worker-new", secondClaim.leaseOwner());
        assertTrue(fixture.outbox.settle(
                firstClaim.eventId(),
                ExternalKnowledgeOperationStatus.CLAIMED,
                "worker-old",
                firstClaim.rowVersion(),
                ExternalIndexSettleCommand.succeeded(),
                NOW + 3_000L
        ).isEmpty());
        KnowledgeExternalIndexOperation settled = fixture.outbox.settle(
                secondClaim.eventId(),
                ExternalKnowledgeOperationStatus.CLAIMED,
                "worker-new",
                secondClaim.rowVersion(),
                ExternalIndexSettleCommand.succeeded(),
                NOW + 3_000L
        ).orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.SUCCEEDED, settled.status());
    }

    private static final class Fixture {
        InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        InMemoryKnowledgeDocumentRevisionStore revisions = new InMemoryKnowledgeDocumentRevisionStore();
        InMemoryKnowledgeChunkStore chunks = new InMemoryKnowledgeChunkStore();
        VectorStore vectors = new InMemoryVectorStore();
        InMemoryKnowledgeExternalIndexBindingStore bindings = new InMemoryKnowledgeExternalIndexBindingStore();
        InMemoryKnowledgeExternalIndexOutboxStore outbox = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeMutationTransactionPort port;

        static Fixture empty() {
            Fixture fixture = new Fixture();
            fixture.port = fixture.portWithCurrentStores();
            return fixture;
        }

        KnowledgeMutationTransactionPort portWithCurrentStores() {
            return new InMemoryKnowledgeMutationTransactionAdapter(
                    documents, revisions, chunks, vectors, bindings, outbox);
        }

        KnowledgeDocumentMutationBundle sourceUpsertBundle(String checksum) {
            KnowledgeDocument document = new KnowledgeDocument(
                    "201",
                    "101",
                    "source.md",
                    "api",
                    "text/markdown",
                    KnowledgeDocumentStatus.INDEXED,
                    true,
                    1,
                    List.of(),
                    NOW,
                    "FEISHU",
                    "token-1",
                    "https://example.feishu.cn/wiki/token-1",
                    "rev-1",
                    checksum,
                    "preview",
                    NOW,
                    0L,
                    1L,
                    "401",
                    "identity-1",
                    0L,
                    0L,
                    "",
                    0L,
                    false
            );
            KnowledgeDocumentRevision revision = new KnowledgeDocumentRevision(
                    "401", "201", 1L, "rev-1", checksum, "text/markdown", "body", "rd-bot-default", "1", NOW);
            KnowledgeChunk chunk = new KnowledgeChunk(
                    "501", "201", "101", 0, "body", "api", "source.md", true, Map.of());
            RetrievedChunk vector = new RetrievedChunk(
                    "501", "body", "101", "api", "source.md", 0.0d, Map.of("documentId", "201"));
            KnowledgeExternalIndexBinding binding = new KnowledgeExternalIndexBinding(
                    PROVIDER,
                    "201",
                    "101",
                    OpenVikingProjectionUris.documentRootUri("101", "201"),
                    OpenVikingProjectionUris.ownershipMarker("101", "201"),
                    ExternalKnowledgeDesiredState.PRESENT,
                    1L,
                    checksum,
                    ExternalKnowledgeObservedState.UNKNOWN,
                    0L,
                    "",
                    ExternalKnowledgeProjectionStatus.PENDING,
                    "301",
                    "",
                    "",
                    0L,
                    0L,
                    "",
                    "",
                    0L,
                    NOW,
                    NOW
            );
            return new KnowledgeDocumentMutationBundle(
                    document,
                    "body",
                    revision,
                    List.of(chunk),
                    List.of(vector),
                    List.of(),
                    binding,
                    outboxOperation("301", checksum)
            );
        }

        KnowledgeExternalIndexOperation outboxOperation(String eventId, String checksum) {
            return new KnowledgeExternalIndexOperation(
                    eventId,
                    ExternalIndexIdempotencyKeys.document(
                            PROVIDER, "201", 1L, ExternalKnowledgeOperationType.UPSERT_DOCUMENT),
                    PROVIDER,
                    ExternalKnowledgeOperationType.UPSERT_DOCUMENT,
                    "101",
                    "201",
                    1L,
                    checksum,
                    OpenVikingProjectionUris.documentRootUri("101", "201"),
                    "401",
                    "",
                    ExternalKnowledgeOperationStatus.PENDING,
                    "",
                    "",
                    "",
                    0L,
                    0,
                    8,
                    NOW,
                    0L,
                    "",
                    "",
                    0L,
                    NOW,
                    NOW
            );
        }
    }

    private static final class ThrowingVectorStore implements VectorStore {
        @Override
        public void index(Collection<RetrievedChunk> newChunks) {
            throw new IllegalStateException("vector index failed");
        }

        @Override
        public void replace(RetrievedChunk chunk) {
        }

        @Override
        public void removeChunks(Collection<String> chunkIds) {
        }

        @Override
        public List<RetrievedChunk> vectorSearch(String query, Collection<String> knowledgeBaseIds, int topK) {
            return List.of();
        }

        @Override
        public List<RetrievedChunk> keywordSearch(String query, Collection<String> knowledgeBaseIds, int topK) {
            return List.of();
        }

        @Override
        public List<RetrievedChunk> allChunks() {
            return List.of();
        }
    }
}
