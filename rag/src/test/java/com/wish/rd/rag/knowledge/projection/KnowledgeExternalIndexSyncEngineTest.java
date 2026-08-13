package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeProjectionSettleAdapter;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeRemoval;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeSubmission;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeUpsertCommand;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentRevisionStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提交侧 Worker。核心不变量：越过发送边界之前必须先落持久意图，
 * 崩溃或超时之后绝不重新提交同一次写入。
 */
class KnowledgeExternalIndexSyncEngineTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String ROOT = "viking://resources/rd-bot/kb/1001/documents/2001";

    @Test
    void shouldPersistTheSendIntentBeforeTheRequestLeaves() {
        Fixture fixture = Fixture.withPendingUpsert(1L);
        fixture.port.onSubmit = command -> {
            KnowledgeExternalIndexOperation inFlight = fixture.outbox.findById("7001").orElseThrow();
            assertEquals(ExternalKnowledgeOperationStatus.SUBMITTED, inFlight.status(),
                    "the durable intent must be committed before the HTTP call");
            assertTrue(inFlight.crossedSendBoundary());
            assertEquals(NOW, inFlight.publishedAtEpochMillis());
            return ExternalKnowledgeSubmission.accepted("task-1", command.resourceRootUri());
        };

        assertEquals(1, fixture.engine.runOnce(NOW));

        KnowledgeExternalIndexOperation settled = fixture.outbox.findById("7001").orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.WAITING_REMOTE, settled.status());
        assertEquals("task-1", settled.remoteTaskId());
        assertTrue(settled.leaseOwner().isBlank());
        KnowledgeExternalIndexBinding binding = fixture.binding();
        assertEquals(ExternalKnowledgeProjectionStatus.PROCESSING, binding.projectionStatus());
        assertEquals("task-1", binding.remoteTaskId());
        assertEquals(0L, binding.observedVersion(), "submitting is not observing");
        assertEquals(1L, binding.desiredVersion(), "the worker must never touch desired state");
    }

    @Test
    void shouldSendTheFrozenRevisionBodyWithOwnershipTags() {
        Fixture fixture = Fixture.withPendingUpsert(1L);
        fixture.engine.runOnce(NOW);

        ExternalKnowledgeUpsertCommand sent = fixture.port.sent.getFirst();
        assertEquals(ROOT, sent.resourceRootUri());
        assertEquals("source.md", sent.fileName());
        assertEquals("# frozen v1\n", sent.canonicalContent());
        assertEquals("rd-bot:1001:2001", sent.ownershipMarker());
        assertTrue(sent.tags().contains("rd.sync_version=1"));
        assertTrue(sent.tags().contains("rd.doc_id=2001"));
        assertTrue(sent.tags().contains("rd.checksum=" + "a".repeat(64)));
    }

    @Test
    void shouldNeverResendAfterAnUnknownOutcome() {
        Fixture fixture = Fixture.withPendingUpsert(1L);
        fixture.port.onSubmit = command -> ExternalKnowledgeSubmission.failed(
                ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT, "TIMEOUT", "no response body");

        fixture.engine.runOnce(NOW);

        KnowledgeExternalIndexOperation unknown = fixture.outbox.findById("7001").orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.UNKNOWN_REMOTE_RESULT, unknown.status());
        assertTrue(unknown.crossedSendBoundary(), "the send marker must survive so nothing re-submits");
        assertEquals(0, fixture.engine.runOnce(NOW + 600_000L));
        assertEquals(1, fixture.port.sent.size(), "an unknown outcome must be resolved by query, never by replay");
    }

    @Test
    void shouldReturnADefinitivelyRejectedRequestToTheSubmitPath() {
        Fixture fixture = Fixture.withPendingUpsert(1L);
        fixture.port.onSubmit = command -> ExternalKnowledgeSubmission.failed(
                ExternalIndexFailureClass.RETRYABLE_BUSY, "CONFLICT", "path_busy");

        fixture.engine.runOnce(NOW);

        KnowledgeExternalIndexOperation retrying = fixture.outbox.findById("7001").orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.RETRY_WAIT, retrying.status());
        assertFalse(retrying.crossedSendBoundary(),
                "a request the remote rejected outright never applied, so it may be sent again");
        assertTrue(retrying.nextVisibleAtEpochMillis() > NOW, "the retry must be backed off");

        fixture.port.onSubmit = command -> ExternalKnowledgeSubmission.accepted("task-2", command.resourceRootUri());
        assertEquals(1, fixture.engine.runOnce(retrying.nextVisibleAtEpochMillis()));
        assertEquals(ExternalKnowledgeOperationStatus.WAITING_REMOTE,
                fixture.outbox.findById("7001").orElseThrow().status());
    }

    @Test
    void shouldRejectARootUriThatDoesNotMatchTheRequest() {
        Fixture fixture = Fixture.withPendingUpsert(1L);
        fixture.port.onSubmit = command -> ExternalKnowledgeSubmission.accepted(
                "task-1", command.resourceRootUri() + "/unexpected");

        fixture.engine.runOnce(NOW);

        KnowledgeExternalIndexOperation settled = fixture.outbox.findById("7001").orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.NEEDS_HUMAN, settled.status(),
                "a 2xx whose root_uri differs from the request is a contract break, not a success");
        assertTrue(settled.leaseOwner().isBlank());
        assertEquals(ExternalKnowledgeProjectionStatus.NEEDS_HUMAN, fixture.binding().projectionStatus());
    }

    @Test
    void shouldSupersedeAVersionThatDesiredStateAlreadyMovedPast() {
        Fixture fixture = Fixture.withPendingUpsert(1L);
        fixture.bumpDesiredVersionTo(2L);

        assertEquals(0, fixture.engine.runOnce(NOW), "a stale version must not be dispatched");
        assertEquals(ExternalKnowledgeOperationStatus.SUPERSEDED,
                fixture.outbox.findById("7001").orElseThrow().status());
        assertTrue(fixture.port.sent.isEmpty());
    }

    @Test
    void shouldDeadLetterInsteadOfSilentlyDroppingAnExhaustedRow() {
        Fixture fixture = Fixture.withPendingUpsert(1L, 1);
        fixture.port.onSubmit = command -> ExternalKnowledgeSubmission.failed(
                ExternalIndexFailureClass.RETRYABLE, "TOO_MANY_REQUESTS", "throttled");

        fixture.engine.runOnce(NOW);

        KnowledgeExternalIndexOperation settled = fixture.outbox.findById("7001").orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.DEAD_LETTER, settled.status(),
                "an exhausted budget must reach a terminal state, not become invisible");
        assertEquals(ExternalKnowledgeProjectionStatus.DEAD_LETTER, fixture.binding().projectionStatus());
    }

    @Test
    void shouldNotDispatchWhileTheRemoteIsNotReady() {
        Fixture fixture = Fixture.withPendingUpsert(1L);
        fixture.port.ready = false;

        assertEquals(0, fixture.engine.runOnce(NOW));
        assertEquals(ExternalKnowledgeOperationStatus.PENDING,
                fixture.outbox.findById("7001").orElseThrow().status(),
                "an unhealthy remote stops dispatch but must not consume or drop the operation");
        assertEquals(0, fixture.outbox.findById("7001").orElseThrow().attemptCount());
    }

    @Test
    void shouldRefuseToSendWhileAnotherVersionOfTheSameDocumentIsStillInFlight() {
        Fixture fixture = Fixture.withPendingUpsert(1L);
        fixture.port.onSubmit = command -> ExternalKnowledgeSubmission.accepted("task-1", command.resourceRootUri());
        fixture.engine.runOnce(NOW);
        fixture.enqueueUpsert("7002", 2L);
        fixture.bumpDesiredVersionTo(2L);

        assertEquals(0, fixture.engine.runOnce(NOW + 1_000L));
        KnowledgeExternalIndexOperation deferred = fixture.outbox.findById("7002").orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.RETRY_WAIT, deferred.status());
        assertTrue(deferred.nextVisibleAtEpochMillis() > NOW + 1_000L);
        assertEquals(1, fixture.port.sent.size(), "v2 must wait for the in-flight v1 to settle");
    }

    @Test
    void shouldSendADeleteAfterPersistingTheIntentAndMarkTheBindingDeleting() {
        Fixture fixture = Fixture.withPendingDelete(2L);
        fixture.port.onRemove = (uri, recursive, ownedRoot) -> {
            KnowledgeExternalIndexOperation inFlight = fixture.outbox.findById("8001").orElseThrow();
            assertEquals(ExternalKnowledgeOperationStatus.SUBMITTED, inFlight.status(),
                    "the durable delete intent must be committed before the HTTP call");
            assertTrue(inFlight.crossedSendBoundary());
            assertFalse(recursive, "a document delete is not a recursive knowledge-base wipe");
            assertEquals(ROOT, uri);
            return ExternalKnowledgeRemoval.accepted(1);
        };

        assertEquals(1, fixture.engine.runOnce(NOW));

        KnowledgeExternalIndexOperation settled = fixture.outbox.findById("8001").orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.VERIFYING, settled.status());
        assertTrue(settled.leaseOwner().isBlank(), "the poller must be able to confirm absence without waiting out the worker lease");
        assertEquals(ExternalKnowledgeProjectionStatus.DELETING, fixture.binding().projectionStatus());
        assertEquals(2L, fixture.binding().desiredVersion(), "the worker must never touch desired state");
    }

    @Test
    void shouldRetryABusyDeleteWithoutMarkingItSucceeded() {
        Fixture fixture = Fixture.withPendingDelete(2L);
        fixture.port.onRemove = (uri, recursive, ownedRoot) -> ExternalKnowledgeRemoval.failed(
                ExternalIndexFailureClass.RETRYABLE_BUSY, "CONFLICT", "path_busy", true);

        fixture.engine.runOnce(NOW);

        KnowledgeExternalIndexOperation retrying = fixture.outbox.findById("8001").orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.RETRY_WAIT, retrying.status());
        assertTrue(retrying.nextVisibleAtEpochMillis() > NOW);
        assertNotEquals(ExternalKnowledgeOperationStatus.SUCCEEDED, retrying.status());
        KnowledgeExternalIndexBinding binding = fixture.binding();
        assertNotEquals(ExternalKnowledgeProjectionStatus.IN_SYNC, binding.projectionStatus(),
                "a busy delete must never be marked successful");
        assertNotEquals(ExternalKnowledgeProjectionStatus.DELETED, binding.projectionStatus(),
                "a busy delete has not removed the remote resource");
        assertFalse(retrying.crossedSendBoundary(),
                "path_busy means the remote rejected the write, so the submit path may own it again");
    }

    @Test
    void shouldDeferANewerDeleteWhileAnOlderUpsertIsStillInFlight() {
        Fixture fixture = Fixture.withPendingUpsert(1L);
        fixture.port.onSubmit = command -> ExternalKnowledgeSubmission.accepted("task-1", command.resourceRootUri());
        fixture.engine.runOnce(NOW);
        fixture.setDesired(ExternalKnowledgeDesiredState.ABSENT, 2L);
        fixture.enqueueDelete("8001", 2L);

        assertEquals(0, fixture.engine.runOnce(NOW + 1_000L));
        KnowledgeExternalIndexOperation deferred = fixture.outbox.findById("8001").orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.RETRY_WAIT, deferred.status());
        assertEquals(0, deferred.attemptCount(), "a local deferral must refund the claim budget");
        assertTrue(fixture.port.removed.isEmpty(), "v2 delete must wait for the in-flight v1 upsert");
    }

    @Test
    void shouldRebuildThroughTheUpsertPathWithoutAdvancingDesiredVersion() {
        Fixture fixture = Fixture.withPendingRebuild(1L);

        assertEquals(1, fixture.engine.runOnce(NOW));

        assertEquals(1, fixture.port.sent.size());
        assertTrue(fixture.port.removed.isEmpty());
        assertEquals(1L, fixture.binding().desiredVersion());
        assertEquals(ExternalKnowledgeDesiredState.PRESENT, fixture.binding().desiredState());
        assertEquals(ExternalKnowledgeOperationStatus.WAITING_REMOTE,
                fixture.outbox.findById("9001").orElseThrow().status());
    }

    @Test
    void shouldRefuseToDeleteOutsideTheOwnedRootWithoutIssuingARequest() {
        Fixture fixture = Fixture.withPendingDelete(2L);
        fixture.setRemoteUri("viking://resources/other/kb/1001/documents/2001");

        fixture.engine.runOnce(NOW);

        KnowledgeExternalIndexOperation settled = fixture.outbox.findById("8001").orElseThrow();
        assertEquals(ExternalKnowledgeOperationStatus.NEEDS_HUMAN, settled.status());
        assertEquals("ROOT_NOT_OWNED", settled.lastErrorCode());
        assertTrue(fixture.port.removed.isEmpty(), "an out-of-root URI must never reach the wire");
        assertEquals(ExternalKnowledgeProjectionStatus.NEEDS_HUMAN, fixture.binding().projectionStatus());
    }

    private static final class RecordingIndexPort implements ExternalKnowledgeIndexPort {

        private final List<ExternalKnowledgeUpsertCommand> sent = new ArrayList<>();
        private final List<RemovedCall> removed = new ArrayList<>();
        private boolean ready = true;
        private java.util.function.Function<ExternalKnowledgeUpsertCommand, ExternalKnowledgeSubmission> onSubmit =
                command -> ExternalKnowledgeSubmission.accepted("task-1", command.resourceRootUri());
        private RemoveFn onRemove = (uri, recursive, ownedRoot) -> ExternalKnowledgeRemoval.accepted(1);

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public ExternalKnowledgeSubmission submitUpsert(ExternalKnowledgeUpsertCommand command) {
            sent.add(command);
            return onSubmit.apply(command);
        }

        @Override
        public com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskSnapshot inspectTask(String taskId) {
            throw new UnsupportedOperationException("the submit worker must not poll");
        }

        @Override
        public com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVerification verifyResource(
                com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVersionMarker marker
        ) {
            throw new UnsupportedOperationException("the submit worker must not verify");
        }

        @Override
        public ExternalKnowledgeRemoval removeResource(
                String remoteUri, boolean recursive, String expectedOwnedRoot
        ) {
            removed.add(new RemovedCall(remoteUri, recursive, expectedOwnedRoot));
            return onRemove.apply(remoteUri, recursive, expectedOwnedRoot);
        }

        @Override
        public com.wish.rd.rag.knowledge.projection.model.ExternalResourceProbe inspectResource(String remoteUri) {
            throw new UnsupportedOperationException("the submit worker must not inspect");
        }

        @Override
        public com.wish.rd.rag.knowledge.projection.model.ExternalTreeListing listTree(String ownedRootUri) {
            throw new UnsupportedOperationException("the submit worker must not list");
        }
    }

    @FunctionalInterface
    private interface RemoveFn {
        ExternalKnowledgeRemoval apply(String uri, boolean recursive, String expectedOwnedRoot);
    }

    private record RemovedCall(String uri, boolean recursive, String expectedOwnedRoot) {
    }

    private static final class Fixture {

        private final InMemoryKnowledgeExternalIndexOutboxStore outbox =
                new InMemoryKnowledgeExternalIndexOutboxStore();
        private final InMemoryKnowledgeExternalIndexBindingStore bindings =
                new InMemoryKnowledgeExternalIndexBindingStore();
        private final InMemoryKnowledgeDocumentRevisionStore revisions =
                new InMemoryKnowledgeDocumentRevisionStore();
        private final RecordingIndexPort port = new RecordingIndexPort();
        private final KnowledgeExternalIndexSyncEngine engine;

        private Fixture() {
            this.engine = new KnowledgeExternalIndexSyncEngine(
                    port,
                    outbox,
                    bindings,
                    revisions,
                    new InMemoryKnowledgeProjectionSettleAdapter(outbox, bindings),
                    ProjectionWorkerSettings.defaults(),
                    () -> "worker-a"
            );
        }

        static Fixture withPendingUpsert(long syncVersion) {
            return withPendingUpsert(syncVersion, 8);
        }

        static Fixture withPendingUpsert(long syncVersion, int maxAttempts) {
            Fixture fixture = new Fixture();
            fixture.revisions.save(new KnowledgeDocumentRevision(
                    "5001", "2001", syncVersion, "feishu-rev", "a".repeat(64),
                    "text/markdown", "# frozen v" + syncVersion + "\n",
                    "rd-bot-default", "1", NOW));
            fixture.bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING, "2001", "1001", ROOT, "rd-bot:1001:2001",
                    ExternalKnowledgeDesiredState.PRESENT, syncVersion, "a".repeat(64),
                    ExternalKnowledgeObservedState.UNKNOWN, 0L, "",
                    ExternalKnowledgeProjectionStatus.PENDING, "", "", "",
                    0L, 0L, "", "", 0L, NOW, NOW));
            fixture.enqueue("7001", syncVersion, maxAttempts, ExternalKnowledgeOperationType.UPSERT_DOCUMENT);
            return fixture;
        }

        static Fixture withPendingDelete(long syncVersion) {
            Fixture fixture = new Fixture();
            fixture.bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING, "2001", "1001", ROOT, "rd-bot:1001:2001",
                    ExternalKnowledgeDesiredState.ABSENT, syncVersion, "a".repeat(64),
                    ExternalKnowledgeObservedState.READY, 1L, "a".repeat(64),
                    ExternalKnowledgeProjectionStatus.DELETING, "", "task-1", "",
                    NOW, NOW, "", "", 0L, NOW, NOW));
            fixture.enqueue("8001", syncVersion, 8, ExternalKnowledgeOperationType.DELETE_DOCUMENT);
            return fixture;
        }

        static Fixture withPendingRebuild(long syncVersion) {
            Fixture fixture = withPendingUpsert(syncVersion);
            fixture.outbox.delete("7001");
            fixture.enqueue("9001", syncVersion, 8, ExternalKnowledgeOperationType.REBUILD_DOCUMENT);
            return fixture;
        }

        void enqueueUpsert(String eventId, long syncVersion) {
            revisions.save(new KnowledgeDocumentRevision(
                    "500" + syncVersion, "2001", syncVersion, "feishu-rev", "a".repeat(64),
                    "text/markdown", "# frozen v" + syncVersion + "\n", "rd-bot-default", "1", NOW));
            enqueue(eventId, syncVersion, 8, ExternalKnowledgeOperationType.UPSERT_DOCUMENT);
        }

        void enqueueDelete(String eventId, long syncVersion) {
            enqueue(eventId, syncVersion, 8, ExternalKnowledgeOperationType.DELETE_DOCUMENT);
        }

        void bumpDesiredVersionTo(long desiredVersion) {
            setDesired(binding().desiredState(), desiredVersion);
        }

        void setDesired(ExternalKnowledgeDesiredState desiredState, long desiredVersion) {
            KnowledgeExternalIndexBinding current = binding();
            bindings.save(new KnowledgeExternalIndexBinding(
                    current.provider(), current.documentId(), current.knowledgeBaseId(), current.remoteUri(),
                    current.ownershipMarker(), desiredState, desiredVersion, current.desiredChecksum(),
                    current.observedState(), current.observedVersion(), current.observedChecksum(),
                    current.projectionStatus(), current.activeOperationId(), current.remoteTaskId(),
                    current.semanticConfigFingerprint(), current.lastSubmittedAtEpochMillis(),
                    current.lastVerifiedAtEpochMillis(), current.lastErrorCode(), current.lastErrorMessage(),
                    current.rowVersion(), current.createdAtEpochMillis(), current.updatedAtEpochMillis()));
        }

        void setRemoteUri(String remoteUri) {
            KnowledgeExternalIndexBinding current = binding();
            bindings.save(new KnowledgeExternalIndexBinding(
                    current.provider(), current.documentId(), current.knowledgeBaseId(), remoteUri,
                    current.ownershipMarker(), current.desiredState(), current.desiredVersion(),
                    current.desiredChecksum(), current.observedState(), current.observedVersion(),
                    current.observedChecksum(), current.projectionStatus(), current.activeOperationId(),
                    current.remoteTaskId(), current.semanticConfigFingerprint(),
                    current.lastSubmittedAtEpochMillis(), current.lastVerifiedAtEpochMillis(),
                    current.lastErrorCode(), current.lastErrorMessage(), current.rowVersion(),
                    current.createdAtEpochMillis(), current.updatedAtEpochMillis()));
            KnowledgeExternalIndexOperation operation = outbox.findById("8001").orElseThrow();
            outbox.delete("8001");
            outbox.enqueue(new KnowledgeExternalIndexOperation(
                    operation.eventId(), operation.idempotencyKey(), operation.provider(), operation.operationType(),
                    operation.knowledgeBaseId(), operation.documentId(), operation.syncVersion(), operation.checksum(),
                    remoteUri, operation.revisionId(), operation.payloadRef(), operation.status(),
                    operation.remoteTaskId(), operation.remoteOperationId(), operation.leaseOwner(),
                    operation.leaseUntilEpochMillis(), operation.attemptCount(), operation.maxAttempts(),
                    operation.nextVisibleAtEpochMillis(), operation.publishedAtEpochMillis(),
                    operation.lastErrorCode(), operation.lastErrorMessage(), operation.rowVersion(),
                    operation.createdAtEpochMillis(), operation.updatedAtEpochMillis()));
        }

        KnowledgeExternalIndexBinding binding() {
            return bindings.findByProviderAndDocumentId(KnowledgeExternalIndexBinding.OPENVIKING, "2001")
                    .orElseThrow();
        }

        private void enqueue(
                String eventId,
                long syncVersion,
                int maxAttempts,
                ExternalKnowledgeOperationType operationType
        ) {
            outbox.enqueue(new KnowledgeExternalIndexOperation(
                    eventId,
                    "OPENVIKING:doc:2001:" + syncVersion + ":" + operationType.name(),
                    KnowledgeExternalIndexBinding.OPENVIKING,
                    operationType,
                    "1001", "2001", syncVersion, "a".repeat(64), ROOT,
                    syncVersion == 1L ? "5001" : "500" + syncVersion, "",
                    ExternalKnowledgeOperationStatus.PENDING,
                    "", "", "", 0L, 0, maxAttempts, NOW, 0L, "", "", 0L, NOW, NOW));
        }
    }
}
