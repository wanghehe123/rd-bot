package com.wish.rd.engine.project.memory;

import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryOperationStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperation;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationClaim;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryOperationWorkerTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void successfulClaimSettlesWithMatchingFence() throws Exception {
        InMemoryProjectMemoryOperationStore store = new InMemoryProjectMemoryOperationStore();
        store.register(pending("op-1"));
        AtomicLong clock = new AtomicLong(1_000L);
        List<ProjectMemoryOperationClaim> handled = new ArrayList<>();
        ProjectMemoryOperationWorker worker = worker(store, claim -> {
            handled.add(claim);
            return claim.checkpointJson();
        }, clock);

        assertTrue(worker.runOnce("worker-a"));
        assertEquals(1, handled.size());
        assertFalse(store.claimNext("worker-b", clock.get(), 30_000L).isPresent());
    }

    @Test
    void retryableFailureSchedulesBoundedBackoffBeforeTerminalNeedsHuman() throws Exception {
        InMemoryProjectMemoryOperationStore store = new InMemoryProjectMemoryOperationStore();
        store.register(pending("op-retry"));
        AtomicLong clock = new AtomicLong(5_000L);
        ProjectMemoryOperationWorker worker = worker(store, claim -> {
            throw new ProjectMemoryOperationRetryableException("transient");
        }, clock);

        assertTrue(worker.runOnce("worker-a"));
        Optional<ProjectMemoryOperationClaim> retryable = store.claimNext("worker-a", 5_000L, 30_000L);
        assertTrue(retryable.isEmpty());

        clock.set(6_000L);
        assertTrue(worker.runOnce("worker-a"));
        clock.set(8_000L);
        assertTrue(worker.runOnce("worker-a"));
        clock.set(20_000L);
        assertFalse(store.claimNext("worker-a", clock.get(), 30_000L).isPresent());
    }

    @Test
    void staleOwnerCannotSettleAfterLeaseExpiresAndAnotherWorkerClaims() {
        InMemoryProjectMemoryOperationStore store = new InMemoryProjectMemoryOperationStore();
        store.register(pending("op-lease"));
        Optional<ProjectMemoryOperationClaim> first = store.claimNext("worker-a", 1_000L, 500L);
        assertTrue(first.isPresent());
        ProjectMemoryOperationClaim claim = first.get();

        Optional<ProjectMemoryOperationClaim> reclaimed = store.claimNext("worker-b", 2_000L, 500L);
        assertTrue(reclaimed.isPresent());
        assertTrue(reclaimed.get().fencingToken() > claim.fencingToken());

        assertFalse(store.settle(
                claim.operationId(),
                "worker-a",
                claim.fencingToken(),
                claim.rowVersion(),
                ProjectMemoryOperationStatus.SUCCEEDED));
    }

    @Test
    void staleFenceIsRejectedOnSettle() {
        InMemoryProjectMemoryOperationStore store = new InMemoryProjectMemoryOperationStore();
        store.register(pending("op-fence"));
        Optional<ProjectMemoryOperationClaim> claim = store.claimNext("worker-a", 1_000L, 30_000L);
        assertTrue(claim.isPresent());

        assertFalse(store.settle(
                claim.get().operationId(),
                "worker-a",
                claim.get().fencingToken() - 1L,
                claim.get().rowVersion(),
                ProjectMemoryOperationStatus.SUCCEEDED));
    }

    @Test
    void checkpointSurvivesRetrySchedulingForNextClaim() {
        InMemoryProjectMemoryOperationStore store = new InMemoryProjectMemoryOperationStore();
        store.register(pending("op-checkpoint"));
        Optional<ProjectMemoryOperationClaim> claim = store.claimNext("worker-a", 1_000L, 30_000L);
        assertTrue(claim.isPresent());
        assertTrue(store.updateCheckpoint(
                claim.get().operationId(),
                "worker-a",
                claim.get().fencingToken(),
                claim.get().rowVersion(),
                "{\"step\":\"extract\"}"));
        assertTrue(store.scheduleRetry(
                claim.get().operationId(),
                "worker-a",
                claim.get().fencingToken(),
                claim.get().rowVersion() + 1L,
                2_000L,
                "resume later"));

        Optional<ProjectMemoryOperationClaim> retry = store.claimNext("worker-a", 2_000L, 30_000L);
        assertTrue(retry.isPresent());
        assertEquals("{\"step\":\"extract\"}", retry.get().checkpointJson());
    }

    private static ProjectMemoryOperationWorker worker(
            InMemoryProjectMemoryOperationStore store,
            ProjectMemoryOperationHandler handler,
            AtomicLong clock
    ) {
        return new ProjectMemoryOperationWorker(store, handler, 30_000L, 1_000L, clock::get);
    }

    private static ProjectMemoryOperation pending(String operationId) {
        return ProjectMemoryOperation.pending(operationId, "101", "STAGE_CAPTURE", "stage-run-1", HASH, "extractor-v1", "schema-v1");
    }
}
