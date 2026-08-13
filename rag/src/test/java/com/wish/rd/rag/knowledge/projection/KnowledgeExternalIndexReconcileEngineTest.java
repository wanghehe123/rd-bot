package com.wish.rd.rag.knowledge.projection;

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
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFinding;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFindingStatus;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFindingType;
import com.wish.rd.rag.knowledge.projection.model.ReconcileReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconciler 只记账、只入队重建，永不直接删除远端。孤儿隔离、外来资源永不修复。
 */
class KnowledgeExternalIndexReconcileEngineTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String ROOT = "viking://resources/rd-bot/kb/1001/documents/2001";
    private static final String OWNED = "viking://resources/rd-bot/kb/1001/";
    private static final String CHECKSUM = "a".repeat(64);

    @Test
    void shouldQuarantineOrphansWithoutDeletingThem() {
        Fixture fixture = new Fixture();
        fixture.port.tree = ExternalTreeListing.of(List.of(
                new ExternalTreeListing.Entry(
                        "viking://resources/rd-bot/kb/1001/documents/9999", "9999", true, "rd-bot")));

        ReconcileReport report = fixture.engine.runOnce("1001", OWNED, NOW);

        assertEquals(1, count(report, ReconcileFindingType.ORPHAN_REMOTE));
        assertEquals(ReconcileFindingStatus.QUARANTINED, finding(fixture, ReconcileFindingType.ORPHAN_REMOTE).status());
        assertTrue(fixture.port.removed.isEmpty(), "orphans are findings, not deletes");
        assertTrue(fixture.outbox.listAll().isEmpty());
    }

    @Test
    void shouldNeverEnqueueOutboxForForeignOwnedResources() {
        Fixture fixture = new Fixture();
        fixture.port.tree = ExternalTreeListing.of(List.of(
                new ExternalTreeListing.Entry(
                        "viking://resources/rd-bot/kb/1001/documents/8888", "8888", true, "other-tenant")));

        fixture.engine.runOnce("1001", OWNED, NOW);

        assertEquals(1, fixture.findings.listByKnowledgeBase(
                KnowledgeExternalIndexBinding.OPENVIKING, "1001").stream()
                .filter(finding -> finding.findingType() == ReconcileFindingType.FOREIGN_OWNER)
                .count());
        assertTrue(fixture.outbox.listAll().isEmpty(), "foreign ownership must never be repaired");
        assertTrue(fixture.port.removed.isEmpty());
        assertTrue(fixture.port.submitted.isEmpty());
    }

    @Test
    void shouldMarkDriftedAndEnqueueRebuildWhenInSyncBindingIsMissingRemotely() {
        Fixture fixture = new Fixture();
        fixture.bindings.save(inSyncBinding());
        fixture.port.onProbe = uri -> ExternalResourceProbe.absent();

        ReconcileReport report = fixture.engine.runOnce("1001", OWNED, NOW);

        KnowledgeExternalIndexBinding binding = fixture.bindings
                .findByProviderAndDocumentId(KnowledgeExternalIndexBinding.OPENVIKING, "2001")
                .orElseThrow();
        assertEquals(ExternalKnowledgeProjectionStatus.DRIFTED, binding.projectionStatus());
        assertEquals(ExternalKnowledgeDesiredState.PRESENT, binding.desiredState(),
                "reconcile must not touch desired state");
        assertEquals(1, fixture.outbox.listAll().size());
        assertEquals(ExternalKnowledgeOperationType.REBUILD_DOCUMENT,
                fixture.outbox.listAll().getFirst().operationType());
        assertEquals(1L, fixture.outbox.listAll().getFirst().syncVersion());
        assertTrue(count(report, ReconcileFindingType.MISSING_REMOTE) >= 1);
    }

    @Test
    void shouldBeIdempotentAcrossRepeatedRunOnce() {
        Fixture fixture = new Fixture();
        fixture.bindings.save(inSyncBinding());
        fixture.port.onProbe = uri -> ExternalResourceProbe.absent();

        fixture.engine.runOnce("1001", OWNED, NOW);
        fixture.engine.runOnce("1001", OWNED, NOW + 5_000L);

        assertEquals(1, fixture.outbox.listAll().size(), "the outbox unique key must absorb a second REBUILD");
        List<ReconcileFinding> missing = fixture.findings.listByKnowledgeBase(
                KnowledgeExternalIndexBinding.OPENVIKING, "1001").stream()
                .filter(finding -> finding.findingType() == ReconcileFindingType.MISSING_REMOTE)
                .toList();
        assertEquals(1, missing.size());
        assertEquals(NOW + 5_000L, missing.getFirst().lastSeenAtEpochMillis());
        assertEquals(NOW, missing.getFirst().firstSeenAtEpochMillis());
    }

    private static long count(ReconcileReport report, ReconcileFindingType type) {
        return report.counts().getOrDefault(type, 0);
    }

    private static ReconcileFinding finding(Fixture fixture, ReconcileFindingType type) {
        return fixture.findings.listByKnowledgeBase(KnowledgeExternalIndexBinding.OPENVIKING, "1001").stream()
                .filter(candidate -> candidate.findingType() == type)
                .findFirst()
                .orElseThrow();
    }

    private static KnowledgeExternalIndexBinding inSyncBinding() {
        return new KnowledgeExternalIndexBinding(
                KnowledgeExternalIndexBinding.OPENVIKING, "2001", "1001", ROOT, "rd-bot:1001:2001",
                ExternalKnowledgeDesiredState.PRESENT, 1L, CHECKSUM,
                ExternalKnowledgeObservedState.READY, 1L, CHECKSUM,
                ExternalKnowledgeProjectionStatus.IN_SYNC, "", "task-1", "",
                NOW - 60_000L, NOW - 60_000L, "", "", 0L, NOW - 60_000L, NOW - 60_000L);
    }

    private static final class RecordingIndexPort implements ExternalKnowledgeIndexPort {

        private ExternalTreeListing tree = ExternalTreeListing.of(List.of());
        private java.util.function.Function<String, ExternalResourceProbe> onProbe =
                uri -> ExternalResourceProbe.absent();
        private final List<String> removed = new ArrayList<>();
        private final List<ExternalKnowledgeUpsertCommand> submitted = new ArrayList<>();

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public ExternalKnowledgeSubmission submitUpsert(ExternalKnowledgeUpsertCommand command) {
            submitted.add(command);
            throw new AssertionError("the reconciler must never submit an upsert");
        }

        @Override
        public ExternalKnowledgeTaskSnapshot inspectTask(String remoteTaskId) {
            throw new AssertionError("the reconciler must not poll tasks");
        }

        @Override
        public ExternalKnowledgeVerification verifyResource(ExternalKnowledgeVersionMarker marker) {
            throw new AssertionError("the reconciler must not verify versions");
        }

        @Override
        public ExternalKnowledgeRemoval removeResource(String remoteUri, boolean recursive, String expectedOwnedRoot) {
            removed.add(remoteUri);
            throw new AssertionError("the reconciler must never delete");
        }

        @Override
        public ExternalResourceProbe inspectResource(String remoteUri) {
            return onProbe.apply(remoteUri);
        }

        @Override
        public ExternalTreeListing listTree(String ownedRootUri) {
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
        private final RecordingIndexPort port = new RecordingIndexPort();
        private final KnowledgeExternalIndexReconcileEngine engine;
        private final AtomicLong ids = new AtomicLong(1L);

        private Fixture() {
            this.engine = new KnowledgeExternalIndexReconcileEngine(
                    port,
                    bindings,
                    outbox,
                    findings,
                    ProjectionWorkerSettings.defaults(),
                    0L,
                    () -> Long.toString(ids.getAndIncrement())
            );
        }
    }
}
