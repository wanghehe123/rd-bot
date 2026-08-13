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
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskSnapshot;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeUpsertCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVerification;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVersionMarker;
import com.wish.rd.rag.knowledge.projection.model.ExternalResourceProbe;
import com.wish.rd.rag.knowledge.projection.model.ExternalTreeListing;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询侧 Poller。核心不变量：HTTP 2xx 与 task completed 都不等于检索就绪，
 * 只有版本核验通过才允许把 observed 推到 desired。
 */
class KnowledgeExternalIndexPollEngineTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String ROOT = "viking://resources/rd-bot/kb/1001/documents/2001";
    private static final String CHECKSUM = "a".repeat(64);

    @Test
    void shouldReachInSyncOnlyAfterVersionVerificationPasses() {
        Fixture fixture = Fixture.awaitingRemote();

        assertEquals(1, fixture.engine.runOnce(NOW));

        KnowledgeExternalIndexOperation settled = fixture.operation();
        assertEquals(ExternalKnowledgeOperationStatus.SUCCEEDED, settled.status());
        assertTrue(settled.leaseOwner().isBlank());
        KnowledgeExternalIndexBinding binding = fixture.binding();
        assertEquals(ExternalKnowledgeObservedState.READY, binding.observedState());
        assertEquals(1L, binding.observedVersion());
        assertEquals(CHECKSUM, binding.observedChecksum());
        assertEquals(ExternalKnowledgeProjectionStatus.IN_SYNC, binding.projectionStatus());
        assertEquals(NOW, binding.lastVerifiedAtEpochMillis());
        assertEquals(1L, binding.desiredVersion(), "the poller must never touch desired state");
    }

    @Test
    void shouldRefuseToCallACompletedTaskInSyncWhenVerificationFails() {
        Fixture fixture = Fixture.awaitingRemote();
        fixture.port.onVerify = marker -> ExternalKnowledgeVerification.mismatched(
                List.of(ExternalKnowledgeVerification.CHECK_SYNC_VERSION));

        fixture.engine.runOnce(NOW);

        assertEquals(ExternalKnowledgeOperationStatus.NEEDS_HUMAN, fixture.operation().status());
        KnowledgeExternalIndexBinding binding = fixture.binding();
        assertEquals(0L, binding.observedVersion(),
                "a task that completed but does not carry our version has not been observed");
        assertNotEquals(ExternalKnowledgeProjectionStatus.IN_SYNC, binding.projectionStatus());
        assertEquals("VERSION_MARKER_MISMATCH", binding.lastErrorCode());
    }

    @Test
    void shouldTreatQueueErrorsOnACompletedTaskAsFailureWithoutVerifying() {
        Fixture fixture = Fixture.awaitingRemote();
        fixture.port.onInspect = taskId -> new ExternalKnowledgeTaskSnapshot(
                taskId, ExternalKnowledgeTaskState.COMPLETED, "completed", 3L, 0L,
                ExternalIndexFailureClass.NONE, "", "");

        fixture.engine.runOnce(NOW);

        assertEquals(ExternalKnowledgeOperationStatus.NEEDS_HUMAN, fixture.operation().status());
        assertTrue(fixture.port.verified.isEmpty(),
                "a task with queue errors must not even be offered to verification");
    }

    @Test
    void shouldKeepWaitingWhileTheRemoteTaskIsStillRunning() {
        Fixture fixture = Fixture.awaitingRemote();
        fixture.port.onInspect = taskId -> new ExternalKnowledgeTaskSnapshot(
                taskId, ExternalKnowledgeTaskState.RUNNING, "chunking", 0L, 0L,
                ExternalIndexFailureClass.NONE, "", "");

        assertEquals(0, fixture.engine.runOnce(NOW));

        KnowledgeExternalIndexOperation waiting = fixture.operation();
        assertEquals(ExternalKnowledgeOperationStatus.WAITING_REMOTE, waiting.status());
        assertTrue(waiting.nextVisibleAtEpochMillis() > NOW, "an unfinished task must be re-scheduled");
        assertTrue(waiting.leaseOwner().isBlank(), "a poll lease must not outlive its iteration");
        assertTrue(fixture.port.verified.isEmpty());
    }

    @Test
    void shouldStretchThePollIntervalAsTheTaskAges() {
        Fixture fixture = Fixture.awaitingRemote();
        fixture.port.onInspect = taskId -> new ExternalKnowledgeTaskSnapshot(
                taskId, ExternalKnowledgeTaskState.RUNNING, "chunking", 0L, 0L,
                ExternalIndexFailureClass.NONE, "", "");

        fixture.engine.runOnce(NOW);
        long earlyGap = fixture.operation().nextVisibleAtEpochMillis() - NOW;
        long later = NOW + 600_000L;
        fixture.engine.runOnce(later);
        long lateGap = fixture.operation().nextVisibleAtEpochMillis() - later;

        assertTrue(lateGap > earlyGap, "an aging task must be polled less often, not hammered");
        assertTrue(lateGap <= ProjectionWorkerSettings.defaults().maxPollIntervalMillis());
    }

    @Test
    void shouldResolveAnUnknownOutcomeByQueryingRatherThanResending() {
        Fixture fixture = Fixture.unknownOutcome(NOW - 10_000L);

        assertEquals(1, fixture.engine.runOnce(NOW));

        assertEquals(ExternalKnowledgeOperationStatus.SUCCEEDED, fixture.operation().status());
        assertEquals(ExternalKnowledgeProjectionStatus.IN_SYNC, fixture.binding().projectionStatus());
        assertTrue(fixture.port.submitted.isEmpty(), "the poller must never resend");
    }

    @Test
    void shouldParkAnUnknownOutcomeThatNeverConverges() {
        Fixture fixture = Fixture.unknownOutcome(NOW - 10_000L);
        fixture.port.onVerify = marker -> ExternalKnowledgeVerification.mismatched(
                List.of(ExternalKnowledgeVerification.CHECK_ROOT_URI));

        fixture.engine.runOnce(NOW);
        assertEquals(ExternalKnowledgeOperationStatus.UNKNOWN_REMOTE_RESULT, fixture.operation().status(),
                "inside the deadline an unresolved unknown keeps waiting");

        long pastDeadline = NOW + ProjectionWorkerSettings.defaults().unknownOutcomeTimeoutMillis();
        fixture.engine.runOnce(pastDeadline);

        assertEquals(ExternalKnowledgeOperationStatus.NEEDS_HUMAN, fixture.operation().status(),
                "an unknown outcome must have a wall-clock end, not spin forever");
    }

    @Test
    void shouldNotSupersedeAnOperationThatAlreadyReachedTheRemote() {
        Fixture fixture = Fixture.awaitingRemote();
        fixture.setDesiredVersion(2L);

        fixture.engine.runOnce(NOW);

        assertEquals(ExternalKnowledgeOperationStatus.SUPERSEDED, fixture.operation().status(),
                "a late upsert must not count as the current desired version");
        KnowledgeExternalIndexBinding binding = fixture.binding();
        assertEquals(1L, binding.observedVersion());
        assertEquals(ExternalKnowledgeProjectionStatus.DRIFTED, binding.projectionStatus(),
                "observed v1 under desired v2 is drift, not in sync");
        assertNotEquals(ExternalKnowledgeProjectionStatus.IN_SYNC, binding.projectionStatus());
    }

    @Test
    void shouldMarkDeletedWhenTheRemoteResourceIsAbsent() {
        Fixture fixture = Fixture.awaitingDelete();
        fixture.port.onProbe = uri -> ExternalResourceProbe.absent();

        assertEquals(1, fixture.engine.runOnce(NOW));

        assertEquals(ExternalKnowledgeOperationStatus.SUCCEEDED, fixture.operation().status());
        KnowledgeExternalIndexBinding binding = fixture.binding();
        assertEquals(ExternalKnowledgeObservedState.ABSENT, binding.observedState());
        assertEquals(ExternalKnowledgeProjectionStatus.DELETED, binding.projectionStatus());
        assertEquals(2L, binding.desiredVersion(), "the poller must never touch desired state");
        assertEquals(NOW, binding.lastVerifiedAtEpochMillis());
    }

    @Test
    void shouldKeepWaitingWhenTheDeletedResourceIsStillPresent() {
        Fixture fixture = Fixture.awaitingDelete();
        fixture.port.onProbe = uri -> ExternalResourceProbe.present(java.util.Map.of("rd.owner", "rd-bot"));

        assertEquals(0, fixture.engine.runOnce(NOW));

        KnowledgeExternalIndexOperation waiting = fixture.operation();
        assertEquals(ExternalKnowledgeOperationStatus.VERIFYING, waiting.status());
        assertTrue(waiting.nextVisibleAtEpochMillis() > NOW, "an unfinished delete must be re-scheduled");
        assertTrue(waiting.leaseOwner().isBlank());
        assertEquals(ExternalKnowledgeProjectionStatus.DELETING, fixture.binding().projectionStatus());
        assertEquals(ExternalKnowledgeObservedState.READY, fixture.binding().observedState(),
                "a still-present resource must not be recorded as absent");
    }

    @Test
    void shouldNotResurrectADeletedDocumentWhenALateUpsertVerifies() {
        Fixture fixture = Fixture.awaitingRemote();
        fixture.setDesired(ExternalKnowledgeDesiredState.ABSENT, 2L);

        fixture.engine.runOnce(NOW);

        assertEquals(ExternalKnowledgeOperationStatus.SUPERSEDED, fixture.operation().status(),
                "v1 arriving late must not revive a document whose desired state is already ABSENT");
        KnowledgeExternalIndexBinding binding = fixture.binding();
        assertNotEquals(ExternalKnowledgeProjectionStatus.IN_SYNC, binding.projectionStatus());
        assertEquals(ExternalKnowledgeProjectionStatus.DRIFTED, binding.projectionStatus());
        assertEquals(ExternalKnowledgeDesiredState.ABSENT, binding.desiredState());
        assertEquals(2L, binding.desiredVersion());
    }

    @Test
    void shouldDeferWithoutWritingObservationWhenInspectResourceThrows() {
        Fixture fixture = Fixture.awaitingDelete();
        fixture.port.onProbe = uri -> {
            throw new IllegalStateException("attrs unavailable");
        };
        long bindingVersion = fixture.binding().rowVersion();

        assertEquals(0, fixture.engine.runOnce(NOW));

        KnowledgeExternalIndexOperation waiting = fixture.operation();
        assertEquals(ExternalKnowledgeOperationStatus.VERIFYING, waiting.status());
        assertTrue(waiting.nextVisibleAtEpochMillis() > NOW);
        assertEquals(bindingVersion, fixture.binding().rowVersion(),
                "a failed probe must not write any observation");
        assertEquals(ExternalKnowledgeProjectionStatus.DELETING, fixture.binding().projectionStatus());
    }

    @Test
    void shouldRetryAFailedRemoteTaskWithinBudget() {
        Fixture fixture = Fixture.awaitingRemote();
        fixture.port.onInspect = taskId -> new ExternalKnowledgeTaskSnapshot(
                taskId, ExternalKnowledgeTaskState.FAILED, "failed", 0L, 0L,
                ExternalIndexFailureClass.NONE, "TASK_FAILED", "indexing failed");

        fixture.engine.runOnce(NOW);

        KnowledgeExternalIndexOperation retrying = fixture.operation();
        assertEquals(ExternalKnowledgeOperationStatus.RETRY_WAIT, retrying.status());
        assertFalse(retrying.crossedSendBoundary(),
                "a terminally failed task leaves nothing in flight, so the submit path may own it again");
        assertTrue(retrying.nextVisibleAtEpochMillis() > NOW);
    }

    @Test
    void shouldNotStealARowWhosePollLeaseIsStillAlive() {
        Fixture fixture = Fixture.awaitingRemote();
        fixture.outbox.claimPollBatch("poller-b", NOW, NOW + 15_000L, 10);

        assertEquals(0, fixture.engine.runOnce(NOW + 1_000L));
        assertTrue(fixture.port.inspected.isEmpty(), "a live lease belongs to its owner");
        assertEquals("poller-b", fixture.operation().leaseOwner());
    }

    @Test
    void shouldLeaveTheRowUntouchedWhenTheQueryItselfFails() {
        Fixture fixture = Fixture.awaitingRemote();
        fixture.port.onInspect = taskId -> new ExternalKnowledgeTaskSnapshot(
                taskId, ExternalKnowledgeTaskState.UNKNOWN, "", 0L, 0L,
                ExternalIndexFailureClass.RETRYABLE, "GATEWAY", "502");

        assertEquals(0, fixture.engine.runOnce(NOW));

        KnowledgeExternalIndexOperation waiting = fixture.operation();
        assertEquals(ExternalKnowledgeOperationStatus.WAITING_REMOTE, waiting.status(),
                "a failed query says nothing about the remote task");
        assertEquals("task-1", waiting.remoteTaskId());
        assertTrue(waiting.nextVisibleAtEpochMillis() > NOW);
    }

    private static final class RecordingIndexPort implements ExternalKnowledgeIndexPort {

        private final List<ExternalKnowledgeUpsertCommand> submitted = new ArrayList<>();
        private final List<String> inspected = new ArrayList<>();
        private final List<ExternalKnowledgeVersionMarker> verified = new ArrayList<>();
        private final List<String> probed = new ArrayList<>();
        private Function<String, ExternalKnowledgeTaskSnapshot> onInspect = taskId ->
                new ExternalKnowledgeTaskSnapshot(taskId, ExternalKnowledgeTaskState.COMPLETED, "completed",
                        0L, 0L, ExternalIndexFailureClass.NONE, "", "");
        private Function<ExternalKnowledgeVersionMarker, ExternalKnowledgeVerification> onVerify =
                marker -> ExternalKnowledgeVerification.passed();
        private Function<String, ExternalResourceProbe> onProbe = uri -> ExternalResourceProbe.absent();

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public ExternalKnowledgeSubmission submitUpsert(ExternalKnowledgeUpsertCommand command) {
            submitted.add(command);
            throw new AssertionError("the poll engine must never submit a write");
        }

        @Override
        public ExternalKnowledgeTaskSnapshot inspectTask(String remoteTaskId) {
            inspected.add(remoteTaskId);
            return onInspect.apply(remoteTaskId);
        }

        @Override
        public ExternalKnowledgeVerification verifyResource(ExternalKnowledgeVersionMarker marker) {
            verified.add(marker);
            return onVerify.apply(marker);
        }

        @Override
        public ExternalKnowledgeRemoval removeResource(
                String remoteUri, boolean recursive, String expectedOwnedRoot
        ) {
            throw new AssertionError("the poll engine must never delete");
        }

        @Override
        public ExternalResourceProbe inspectResource(String remoteUri) {
            probed.add(remoteUri);
            return onProbe.apply(remoteUri);
        }

        @Override
        public ExternalTreeListing listTree(String ownedRootUri) {
            throw new AssertionError("the poll engine must never list the remote tree");
        }
    }

    private static final class Fixture {

        private final InMemoryKnowledgeExternalIndexOutboxStore outbox =
                new InMemoryKnowledgeExternalIndexOutboxStore();
        private final InMemoryKnowledgeExternalIndexBindingStore bindings =
                new InMemoryKnowledgeExternalIndexBindingStore();
        private final RecordingIndexPort port = new RecordingIndexPort();
        private final KnowledgeExternalIndexPollEngine engine;

        private Fixture() {
            this.engine = new KnowledgeExternalIndexPollEngine(
                    port,
                    outbox,
                    bindings,
                    new InMemoryKnowledgeProjectionSettleAdapter(outbox, bindings),
                    ProjectionWorkerSettings.defaults(),
                    () -> "poller-a"
            );
        }

        static Fixture awaitingRemote() {
            return withOperation(ExternalKnowledgeOperationStatus.WAITING_REMOTE, "task-1", NOW - 5_000L);
        }

        static Fixture unknownOutcome(long publishedAt) {
            return withOperation(ExternalKnowledgeOperationStatus.UNKNOWN_REMOTE_RESULT, "", publishedAt);
        }

        static Fixture awaitingDelete() {
            Fixture fixture = new Fixture();
            fixture.bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING, "2001", "1001", ROOT, "rd-bot:1001:2001",
                    ExternalKnowledgeDesiredState.ABSENT, 2L, CHECKSUM,
                    ExternalKnowledgeObservedState.READY, 1L, CHECKSUM,
                    ExternalKnowledgeProjectionStatus.DELETING, "8001", "", "",
                    NOW - 5_000L, NOW - 5_000L, "", "", 0L, NOW, NOW));
            fixture.outbox.enqueue(new KnowledgeExternalIndexOperation(
                    "7001",
                    "OPENVIKING:doc:2001:2:DELETE_DOCUMENT",
                    KnowledgeExternalIndexBinding.OPENVIKING,
                    ExternalKnowledgeOperationType.DELETE_DOCUMENT,
                    "1001", "2001", 2L, CHECKSUM, ROOT, "5001", "",
                    ExternalKnowledgeOperationStatus.VERIFYING,
                    "", "OPENVIKING:doc:2001:2:DELETE_DOCUMENT#1", "", 0L, 1, 8,
                    NOW - 5_000L, NOW - 5_000L, "", "", 0L, NOW, NOW));
            return fixture;
        }

        private static Fixture withOperation(
                ExternalKnowledgeOperationStatus status,
                String remoteTaskId,
                long publishedAt
        ) {
            Fixture fixture = new Fixture();
            fixture.bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING, "2001", "1001", ROOT, "rd-bot:1001:2001",
                    ExternalKnowledgeDesiredState.PRESENT, 1L, CHECKSUM,
                    ExternalKnowledgeObservedState.UNKNOWN, 0L, "",
                    ExternalKnowledgeProjectionStatus.PROCESSING, "7001", remoteTaskId, "",
                    publishedAt, 0L, "", "", 0L, NOW, NOW));
            fixture.outbox.enqueue(new KnowledgeExternalIndexOperation(
                    "7001",
                    "OPENVIKING:doc:2001:1:UPSERT_DOCUMENT",
                    KnowledgeExternalIndexBinding.OPENVIKING,
                    ExternalKnowledgeOperationType.UPSERT_DOCUMENT,
                    "1001", "2001", 1L, CHECKSUM, ROOT, "5001", "",
                    status,
                    remoteTaskId, "OPENVIKING:doc:2001:1:UPSERT_DOCUMENT#1", "", 0L, 1, 8,
                    publishedAt, publishedAt, "", "", 0L, NOW, NOW));
            return fixture;
        }

        void setDesiredVersion(long desiredVersion) {
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

        KnowledgeExternalIndexOperation operation() {
            return outbox.findById("7001").orElseThrow();
        }

        KnowledgeExternalIndexBinding binding() {
            return bindings.findByProviderAndDocumentId(KnowledgeExternalIndexBinding.OPENVIKING, "2001")
                    .orElseThrow();
        }
    }
}
