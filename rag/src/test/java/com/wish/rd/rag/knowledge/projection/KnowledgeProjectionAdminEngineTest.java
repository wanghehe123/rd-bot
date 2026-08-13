package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeReconcileFindingStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeRemoval;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeSubmission;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskSnapshot;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeUpsertCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVerification;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVersionMarker;
import com.wish.rd.rag.knowledge.projection.model.ExternalResourceProbe;
import com.wish.rd.rag.knowledge.projection.model.ExternalTreeListing;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionAdminActionResult;
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理面只读账本、复用既有原语。越界拒绝、NEEDS_HUMAN 走发送边界分流、
 * rebuild 幂等、verify 失败只写 DRIFTED、tree 不得代理任意 URI。
 */
class KnowledgeProjectionAdminEngineTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String ROOT = "viking://resources/rd-bot/kb/1001/documents/2001";
    private static final String OWNED = "viking://resources/rd-bot/kb/1001/";
    private static final String CHECKSUM = "a".repeat(64);

    @Test
    void shouldRejectDocumentThatDoesNotBelongToTheKnowledgeBase() {
        Fixture fixture = new Fixture();
        fixture.saveDocument("2001", "1001");
        fixture.saveBinding("2001", "1001", ExternalKnowledgeProjectionStatus.IN_SYNC);
        fixture.saveDocument("3001", "1002");
        fixture.saveBinding("3001", "1002", ExternalKnowledgeProjectionStatus.PENDING);

        assertThrows(IllegalArgumentException.class, () -> fixture.engine.retry("1001", "3001", NOW));
        assertThrows(IllegalArgumentException.class, () -> fixture.engine.verify("1001", "3001", NOW));
        assertThrows(IllegalArgumentException.class, () -> fixture.engine.rebuild("1001", "3001", NOW));
        assertTrue(fixture.port.verified.isEmpty());
        assertTrue(fixture.outbox.listAll().isEmpty());
    }

    @Test
    void shouldRequeueCrossedNeedsHumanToUnknownRemoteResult() {
        Fixture fixture = new Fixture();
        fixture.saveDocument("2001", "1001");
        fixture.saveBinding("2001", "1001", ExternalKnowledgeProjectionStatus.NEEDS_HUMAN);
        fixture.enqueue(operation(
                "7001",
                ExternalKnowledgeOperationStatus.NEEDS_HUMAN,
                "op-sent",
                3,
                NOW - 60_000L));

        ProjectionAdminActionResult result = fixture.engine.retry("1001", "2001", NOW);

        KnowledgeExternalIndexOperation retrying = fixture.outbox.findById("7001").orElseThrow();
        assertTrue(result.applied());
        assertEquals(ProjectionAdminActionResult.RETRIED, result.outcome());
        assertEquals(ExternalKnowledgeOperationStatus.UNKNOWN_REMOTE_RESULT, retrying.status());
        assertEquals(3, retrying.attemptCount(), "a sent row must not reset the submit budget");
        assertEquals("op-sent", retrying.remoteOperationId());
        assertEquals(NOW, retrying.nextVisibleAtEpochMillis());
        assertTrue(retrying.leaseOwner().isBlank());
        assertEquals(1, fixture.outbox.listAll().size(), "retry must not create a new version or row");
        assertTrue(fixture.port.submitted.isEmpty());
        assertTrue(fixture.port.removed.isEmpty());
    }

    @Test
    void shouldRequeueUnsentNeedsHumanToPending() {
        Fixture fixture = new Fixture();
        fixture.saveDocument("2001", "1001");
        fixture.saveBinding("2001", "1001", ExternalKnowledgeProjectionStatus.NEEDS_HUMAN);
        fixture.enqueue(operation(
                "7002",
                ExternalKnowledgeOperationStatus.NEEDS_HUMAN,
                "",
                5,
                NOW - 10_000L));

        fixture.engine.retry("1001", "2001", NOW);

        KnowledgeExternalIndexOperation retrying = fixture.outbox.findById("7002").orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.PENDING, retrying.status());
        assertEquals(0, retrying.attemptCount());
        assertEquals("", retrying.remoteOperationId());
    }

    @Test
    void shouldMakeRetryWaitVisibleNowWithoutCreatingANewVersion() {
        Fixture fixture = new Fixture();
        fixture.saveDocument("2001", "1001");
        fixture.saveBinding("2001", "1001", ExternalKnowledgeProjectionStatus.PROCESSING);
        fixture.enqueue(operation(
                "7003",
                ExternalKnowledgeOperationStatus.RETRY_WAIT,
                "",
                2,
                NOW + 30_000L));

        ProjectionAdminActionResult result = fixture.engine.retry("1001", "2001", NOW);

        KnowledgeExternalIndexOperation retrying = fixture.outbox.findById("7003").orElseThrow();
        assertEquals(ProjectionAdminActionResult.RETRIED, result.outcome());
        assertEquals(ExternalKnowledgeOperationStatus.RETRY_WAIT, retrying.status());
        assertEquals(2, retrying.attemptCount());
        assertEquals(NOW, retrying.nextVisibleAtEpochMillis());
        assertEquals(1, fixture.outbox.listAll().size());
    }

    @Test
    void shouldReturnNothingToRetryWhenThereIsNoNonTerminalOperation() {
        Fixture fixture = new Fixture();
        fixture.saveDocument("2001", "1001");
        fixture.saveBinding("2001", "1001", ExternalKnowledgeProjectionStatus.IN_SYNC);

        ProjectionAdminActionResult result = fixture.engine.retry("1001", "2001", NOW);

        assertFalse(result.applied());
        assertEquals(ProjectionAdminActionResult.NOTHING_TO_RETRY, result.outcome());
    }

    @Test
    void shouldEnqueueRebuildForTheCurrentDesiredVersion() {
        Fixture fixture = new Fixture();
        fixture.saveDocument("2001", "1001");
        fixture.saveBinding("2001", "1001", ExternalKnowledgeProjectionStatus.DRIFTED);

        ProjectionAdminActionResult first = fixture.engine.rebuild("1001", "2001", NOW);
        ProjectionAdminActionResult second = fixture.engine.rebuild("1001", "2001", NOW);

        assertEquals(ProjectionAdminActionResult.REBUILT, first.outcome());
        assertEquals(ProjectionAdminActionResult.ALREADY_QUEUED, second.outcome());
        assertEquals(1, fixture.outbox.listAll().size());
        KnowledgeExternalIndexOperation rebuild = fixture.outbox.listAll().getFirst();
        assertEquals(ExternalKnowledgeOperationType.REBUILD_DOCUMENT, rebuild.operationType());
        assertEquals(1L, rebuild.syncVersion());
        assertEquals(ExternalKnowledgeOperationStatus.PENDING, rebuild.status());
        assertEquals(1L, fixture.bindings.findByProviderAndDocumentId(
                KnowledgeExternalIndexBinding.OPENVIKING, "2001").orElseThrow().desiredVersion(),
                "rebuild must not advance desired version");
        assertTrue(fixture.port.submitted.isEmpty());
    }

    @Test
    void shouldWriteDriftedObservationWhenVerifyFails() {
        Fixture fixture = new Fixture();
        fixture.saveDocument("2001", "1001");
        fixture.saveBinding("2001", "1001", ExternalKnowledgeProjectionStatus.IN_SYNC);
        fixture.port.verification = ExternalKnowledgeVerification.mismatched(
                List.of(ExternalKnowledgeVerification.CHECK_CHECKSUM));

        ProjectionAdminActionResult result = fixture.engine.verify("1001", "2001", NOW);

        KnowledgeExternalIndexBinding binding = fixture.bindings
                .findByProviderAndDocumentId(KnowledgeExternalIndexBinding.OPENVIKING, "2001")
                .orElseThrow();
        assertEquals(ProjectionAdminActionResult.DRIFTED, result.outcome());
        assertEquals(List.of(ExternalKnowledgeVerification.CHECK_CHECKSUM), result.failedChecks());
        assertEquals(ExternalKnowledgeProjectionStatus.DRIFTED, binding.projectionStatus());
        assertEquals(ExternalKnowledgeDesiredState.PRESENT, binding.desiredState());
        assertEquals(1L, binding.desiredVersion());
        assertEquals(NOW, binding.lastVerifiedAtEpochMillis());
        assertTrue(fixture.outbox.listAll().isEmpty(), "verify must not enqueue an outbox row");
        assertEquals(1, fixture.port.verified.size());
    }

    @Test
    void shouldRejectTreeUriOutsideTheKnowledgeBaseOwnedRoot() {
        Fixture fixture = new Fixture();
        fixture.port.tree = ExternalTreeListing.of(List.of(
                new ExternalTreeListing.Entry(ROOT, "2001", true, "rd-bot")));

        assertThrows(IllegalArgumentException.class,
                () -> fixture.engine.tree("1001", "viking://resources/rd-bot/kb/9999/"));
        assertTrue(fixture.port.listed.isEmpty(), "out-of-root URIs must never be proxied");
    }

    @Test
    void shouldListTreeWhenTheUriStaysUnderTheOwnedRoot() {
        Fixture fixture = new Fixture();
        fixture.port.tree = ExternalTreeListing.of(List.of(
                new ExternalTreeListing.Entry(ROOT, "2001", true, "rd-bot")));

        ExternalTreeListing listing = fixture.engine.tree("1001", OWNED);

        assertEquals(1, listing.entries().size());
        assertEquals(List.of(OWNED), fixture.port.listed);
    }

    private static KnowledgeExternalIndexOperation operation(
            String eventId,
            ExternalKnowledgeOperationStatus status,
            String remoteOperationId,
            int attemptCount,
            long nextVisibleAt
    ) {
        return new KnowledgeExternalIndexOperation(
                eventId,
                ExternalIndexIdempotencyKeys.document(
                        KnowledgeExternalIndexBinding.OPENVIKING,
                        "2001",
                        1L,
                        ExternalKnowledgeOperationType.UPSERT_DOCUMENT),
                KnowledgeExternalIndexBinding.OPENVIKING,
                ExternalKnowledgeOperationType.UPSERT_DOCUMENT,
                "1001",
                "2001",
                1L,
                CHECKSUM,
                ROOT,
                "5001",
                "",
                status,
                remoteOperationId.isBlank() ? "" : "task-1",
                remoteOperationId,
                "",
                0L,
                attemptCount,
                8,
                nextVisibleAt,
                remoteOperationId.isBlank() ? 0L : NOW - 90_000L,
                "STALLED",
                "needs attention",
                4L,
                NOW - 90_000L,
                NOW - 90_000L
        );
    }

    private static final class RecordingIndexPort implements ExternalKnowledgeIndexPort {

        private boolean ready = true;
        private ExternalKnowledgeVerification verification = ExternalKnowledgeVerification.passed();
        private ExternalTreeListing tree = ExternalTreeListing.of(List.of());
        private final List<ExternalKnowledgeVersionMarker> verified = new ArrayList<>();
        private final List<String> listed = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();
        private final List<ExternalKnowledgeUpsertCommand> submitted = new ArrayList<>();

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public ExternalKnowledgeSubmission submitUpsert(ExternalKnowledgeUpsertCommand command) {
            submitted.add(command);
            throw new AssertionError("admin actions must not submit writes");
        }

        @Override
        public ExternalKnowledgeTaskSnapshot inspectTask(String remoteTaskId) {
            throw new AssertionError("admin actions must not poll tasks");
        }

        @Override
        public ExternalKnowledgeVerification verifyResource(ExternalKnowledgeVersionMarker marker) {
            verified.add(marker);
            return verification;
        }

        @Override
        public ExternalKnowledgeRemoval removeResource(String remoteUri, boolean recursive, String expectedOwnedRoot) {
            removed.add(remoteUri);
            throw new AssertionError("admin actions must not delete remotely");
        }

        @Override
        public ExternalResourceProbe inspectResource(String remoteUri) {
            throw new AssertionError("admin verify uses verifyResource, not inspectResource");
        }

        @Override
        public ExternalTreeListing listTree(String ownedRootUri) {
            listed.add(ownedRootUri);
            return tree;
        }
    }

    private static final class Fixture {

        private final InMemoryKnowledgeExternalIndexOutboxStore outbox =
                new InMemoryKnowledgeExternalIndexOutboxStore();
        private final InMemoryKnowledgeExternalIndexBindingStore bindings =
                new InMemoryKnowledgeExternalIndexBindingStore();
        private final InMemoryKnowledgeReconcileFindingStore findings =
                new InMemoryKnowledgeReconcileFindingStore();
        private final InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        private final RecordingIndexPort port = new RecordingIndexPort();
        private final AtomicLong ids = new AtomicLong(8000L);
        private final KnowledgeProjectionAdminEngine engine;

        private Fixture() {
            KnowledgeExternalIndexReconcileEngine reconciler = new KnowledgeExternalIndexReconcileEngine(
                    port,
                    bindings,
                    outbox,
                    findings,
                    ProjectionWorkerSettings.defaults(),
                    0L,
                    () -> Long.toString(ids.getAndIncrement()));
            this.engine = new KnowledgeProjectionAdminEngine(
                    port,
                    bindings,
                    outbox,
                    findings,
                    documents,
                    reconciler,
                    ProjectionWorkerSettings.defaults(),
                    () -> Long.toString(ids.getAndIncrement()));
        }

        private void saveDocument(String documentId, String knowledgeBaseId) {
            documents.save(new KnowledgeDocument(
                    documentId,
                    knowledgeBaseId,
                    "doc-" + documentId,
                    "document",
                    "text/markdown",
                    KnowledgeDocumentStatus.INDEXED,
                    true,
                    0,
                    List.of(),
                    NOW
            ), "body");
        }

        private void saveBinding(String documentId, String knowledgeBaseId, ExternalKnowledgeProjectionStatus status) {
            String uri = OpenVikingProjectionUris.documentRootUri(knowledgeBaseId, documentId);
            bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING,
                    documentId,
                    knowledgeBaseId,
                    uri,
                    OpenVikingProjectionUris.ownershipMarker(knowledgeBaseId, documentId),
                    ExternalKnowledgeDesiredState.PRESENT,
                    1L,
                    CHECKSUM,
                    status == ExternalKnowledgeProjectionStatus.IN_SYNC
                            ? ExternalKnowledgeObservedState.READY
                            : ExternalKnowledgeObservedState.UNKNOWN,
                    status == ExternalKnowledgeProjectionStatus.IN_SYNC ? 1L : 0L,
                    status == ExternalKnowledgeProjectionStatus.IN_SYNC ? CHECKSUM : "",
                    status,
                    "",
                    "",
                    "fp-default",
                    NOW - 60_000L,
                    NOW - 60_000L,
                    "",
                    "",
                    0L,
                    NOW - 60_000L,
                    NOW - 60_000L));
        }

        private void enqueue(KnowledgeExternalIndexOperation operation) {
            outbox.enqueue(operation);
        }
    }
}
