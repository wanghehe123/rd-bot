package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexSettleCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outbox 的 claim/settle 契约。Worker 与 Poller 的所有权交接完全建立在这些断言上，
 * PostgreSQL 实现必须表达同一套谓词（见 {@code OpenVikingProjectionSqlPolicyTest}）。
 */
class KnowledgeExternalIndexOutboxStoreContractTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long LEASE = 120_000L;

    @Test
    void shouldReleaseTheLeaseWhenParkingARowForLaterWork() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeExternalIndexOperation claimed = claimOne(store, "worker-a");

        KnowledgeExternalIndexOperation waiting = store.settle(
                claimed.eventId(),
                ExternalKnowledgeOperationStatus.CLAIMED,
                "worker-a",
                claimed.rowVersion(),
                ExternalIndexSettleCommand.waitingRemote("task-1", NOW + 3_000L),
                NOW
        ).orElseThrow();

        assertEquals(ExternalKnowledgeOperationStatus.WAITING_REMOTE, waiting.status());
        assertEquals("task-1", waiting.remoteTaskId());
        assertTrue(waiting.leaseOwner().isBlank(), "a row waiting on the remote must not pin a worker lease");
        assertEquals(0L, waiting.leaseUntilEpochMillis());
    }

    @Test
    void shouldNotLetTheBackoffBeSwallowedByALingeringLease() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeExternalIndexOperation claimed = claimOne(store, "worker-a");

        KnowledgeExternalIndexOperation retrying = store.settle(
                claimed.eventId(),
                ExternalKnowledgeOperationStatus.CLAIMED,
                "worker-a",
                claimed.rowVersion(),
                ExternalIndexSettleCommand.retryWait(NOW + 2_000L, "CONFLICT", "path_busy"),
                NOW
        ).orElseThrow();

        assertTrue(retrying.leaseOwner().isBlank());
        assertEquals(0L, retrying.leaseUntilEpochMillis());
        assertTrue(store.claimBatch("worker-b", NOW + 1_000L, NOW + 1_000L + LEASE, 10).isEmpty(),
                "backoff must still be honoured");
        assertEquals(1, store.claimBatch("worker-b", NOW + 2_000L, NOW + 2_000L + LEASE, 10).size(),
                "the row must become claimable exactly when the backoff elapses, not when a lease expires");
    }

    @Test
    void shouldParkNeedsHumanWithoutAPhantomOwner() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeExternalIndexOperation claimed = claimOne(store, "worker-a");

        KnowledgeExternalIndexOperation parked = store.settle(
                claimed.eventId(),
                ExternalKnowledgeOperationStatus.CLAIMED,
                "worker-a",
                claimed.rowVersion(),
                ExternalIndexSettleCommand.needsHuman("UNKNOWN_REMOTE_RESULT", "deadline exceeded"),
                NOW
        ).orElseThrow();

        assertEquals(ExternalKnowledgeOperationStatus.NEEDS_HUMAN, parked.status());
        assertTrue(parked.leaseOwner().isBlank());
        assertEquals(0L, parked.leaseUntilEpochMillis());
    }

    @Test
    void shouldNeverResubmitARowThatAlreadyCrossedTheSendBoundary() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeExternalIndexOperation claimed = claimOne(store, "worker-a");
        store.settle(
                claimed.eventId(),
                ExternalKnowledgeOperationStatus.CLAIMED,
                "worker-a",
                claimed.rowVersion(),
                ExternalIndexSettleCommand.aboutToSend("op-1", NOW),
                NOW
        ).orElseThrow();

        long afterLeaseExpiry = NOW + LEASE + 1L;
        assertTrue(store.claimBatch("worker-b", afterLeaseExpiry, afterLeaseExpiry + LEASE, 10).isEmpty(),
                "a crashed worker's sent row must not go back to the submit path");

        List<KnowledgeExternalIndexOperation> polled =
                store.claimPollBatch("poller-1", afterLeaseExpiry, afterLeaseExpiry + 5_000L, 10);
        assertEquals(1, polled.size(), "it must instead be resolvable by query");
        assertEquals("op-1", polled.getFirst().remoteOperationId());
    }

    @Test
    void shouldStillReclaimACrashedWorkerThatNeverSent() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeExternalIndexOperation claimed = claimOne(store, "worker-a");
        assertTrue(claimed.remoteOperationId().isBlank());

        long afterLeaseExpiry = NOW + LEASE + 1L;
        List<KnowledgeExternalIndexOperation> reclaimed =
                store.claimBatch("worker-b", afterLeaseExpiry, afterLeaseExpiry + LEASE, 10);
        assertEquals(1, reclaimed.size());
        assertEquals(2, reclaimed.getFirst().attemptCount());
    }

    @Test
    void shouldLeaseAPollIterationWithoutBurningTheSubmitBudget() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeExternalIndexOperation claimed = claimOne(store, "worker-a");
        store.settle(claimed.eventId(), ExternalKnowledgeOperationStatus.CLAIMED, "worker-a",
                claimed.rowVersion(), ExternalIndexSettleCommand.waitingRemote("task-1", NOW), NOW).orElseThrow();

        KnowledgeExternalIndexOperation polled =
                store.claimPollBatch("poller-1", NOW, NOW + 5_000L, 10).getFirst();
        assertEquals(1, polled.attemptCount(), "polling must not consume a remote submission attempt");
        assertEquals(ExternalKnowledgeOperationStatus.WAITING_REMOTE, polled.status(),
                "the poll claim must not change status, so settle can still assert the real expectation");
        assertEquals("poller-1", polled.leaseOwner());

        assertTrue(store.claimPollBatch("poller-2", NOW + 1_000L, NOW + 6_000L, 10).isEmpty(),
                "a live poll lease must not be stolen");
        assertEquals(1, store.claimPollBatch("poller-2", NOW + 5_001L, NOW + 10_000L, 10).size(),
                "an expired poll lease must be recoverable");
    }

    @Test
    void shouldKeepPollingARowWhoseSubmitBudgetIsExhausted() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        store.enqueue(pending("9001", 1));
        KnowledgeExternalIndexOperation claimed = store.claimBatch("worker-a", NOW, NOW + LEASE, 10).getFirst();
        KnowledgeExternalIndexOperation exhausted = store.settle(
                claimed.eventId(),
                ExternalKnowledgeOperationStatus.CLAIMED,
                "worker-a",
                claimed.rowVersion(),
                ExternalIndexSettleCommand.waitingRemote("task-1", NOW),
                NOW
        ).orElseThrow();

        assertEquals(exhausted.maxAttempts(), exhausted.attemptCount());
        assertEquals(1, store.claimPollBatch("poller-1", NOW, NOW + 5_000L, 10).size(),
                "a legitimately running remote task must stay pollable after the submit budget is gone");
    }

    @Test
    void shouldRejectSettleFromAnExpiredOrForeignOwner() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeExternalIndexOperation claimed = claimOne(store, "worker-a");

        assertTrue(store.settle(claimed.eventId(), ExternalKnowledgeOperationStatus.CLAIMED, "worker-b",
                claimed.rowVersion(), ExternalIndexSettleCommand.succeeded(), NOW).isEmpty());
        assertTrue(store.settle(claimed.eventId(), ExternalKnowledgeOperationStatus.CLAIMED, "worker-a",
                claimed.rowVersion() + 5L, ExternalIndexSettleCommand.succeeded(), NOW).isEmpty());
        assertTrue(store.settle(claimed.eventId(), ExternalKnowledgeOperationStatus.CLAIMED, "worker-a",
                claimed.rowVersion(), ExternalIndexSettleCommand.succeeded(), NOW + LEASE + 1L).isEmpty());

        Optional<KnowledgeExternalIndexOperation> settled = store.settle(
                claimed.eventId(), ExternalKnowledgeOperationStatus.CLAIMED, "worker-a",
                claimed.rowVersion(), ExternalIndexSettleCommand.succeeded(), NOW);
        assertTrue(settled.isPresent());
        assertTrue(settled.orElseThrow().leaseOwner().isBlank());
    }

    @Test
    void shouldRequeueAnUnsentDeadLetterOntoTheSubmitPath() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeExternalIndexOperation dead = enqueueDeadLetter(store, "", 3);

        KnowledgeExternalIndexOperation requeued = store.requeueDeadLetter(dead.eventId(), dead.rowVersion(), NOW + 10L)
                .orElseThrow();

        assertEquals(ExternalKnowledgeOperationStatus.PENDING, requeued.status());
        assertEquals(0, requeued.attemptCount());
        assertEquals(NOW + 10L, requeued.nextVisibleAtEpochMillis());
        assertTrue(requeued.leaseOwner().isBlank());
        assertEquals(0L, requeued.leaseUntilEpochMillis());
        assertEquals(dead.rowVersion() + 1L, requeued.rowVersion());
        assertEquals(1, store.claimBatch("worker-a", NOW + 10L, NOW + 10L + LEASE, 10).size());
    }

    @Test
    void shouldRequeueASentDeadLetterOntoQueryConvergenceNeverTheSubmitPath() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeExternalIndexOperation dead = enqueueDeadLetter(store, "op-sent", 5);

        KnowledgeExternalIndexOperation requeued = store.requeueDeadLetter(dead.eventId(), dead.rowVersion(), NOW)
                .orElseThrow();

        assertEquals(ExternalKnowledgeOperationStatus.UNKNOWN_REMOTE_RESULT, requeued.status(),
                "a row that crossed the send boundary must not return to the submit path");
        assertEquals(5, requeued.attemptCount(), "query convergence must not reset the attempt budget");
        assertEquals("op-sent", requeued.remoteOperationId());
        assertTrue(requeued.leaseOwner().isBlank());
        assertTrue(store.claimBatch("worker-a", NOW, NOW + LEASE, 10).isEmpty(),
                "requeue of a sent row must stay off the submit path");
        assertEquals(1, store.claimPollBatch("poller-1", NOW, NOW + 5_000L, 10).size());
    }

    @Test
    void shouldRejectDeadLetterRequeueWhenTheRowVersionDoesNotMatch() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeExternalIndexOperation dead = enqueueDeadLetter(store, "", 1);

        assertTrue(store.requeueDeadLetter(dead.eventId(), dead.rowVersion() + 1L, NOW).isEmpty());
        assertEquals(ExternalKnowledgeOperationStatus.DEAD_LETTER,
                store.findById(dead.eventId()).orElseThrow().status());
        assertEquals(dead.rowVersion(), store.findById(dead.eventId()).orElseThrow().rowVersion());
    }

    @Test
    void requeueSqlMustCasDeadLettersAndOnlyResetAttemptsOnTheUnsentBranch() throws Exception {
        Path mapper = Path.of(System.getProperty("user.dir")).getParent().resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/KnowledgeExternalIndexOutboxMapper.java");
        String sql = Files.readString(mapper);
        int method = sql.indexOf("requeueDeadLetter(");
        assertTrue(method > 0, "postgres must implement requeueDeadLetter");
        int select = sql.lastIndexOf("@Select", method);
        String requeueSql = sql.substring(select, method);
        assertTrue(requeueSql.contains("status = 'DEAD_LETTER'"));
        assertTrue(requeueSql.contains("row_version = #{expectedRowVersion}"));
        assertTrue(requeueSql.contains("UNKNOWN_REMOTE_RESULT"));
        assertTrue(requeueSql.contains("remote_operation_id = ''")
                        || requeueSql.contains("remote_operation_id=''"),
                "only the unsent branch may return to PENDING");
        assertFalse(requeueSql.contains("SET status = 'PENDING'"),
                "a sent row must never be assigned PENDING by an unconditional SET");
        assertTrue(requeueSql.contains("THEN 0") || requeueSql.contains("THEN 0,"),
                "the unsent branch must clear attempt_count");
    }

    @Test
    void shouldPreserveRemoteCorrelationAcrossLaterSettles() {
        InMemoryKnowledgeExternalIndexOutboxStore store = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeExternalIndexOperation claimed = claimOne(store, "worker-a");
        KnowledgeExternalIndexOperation sent = store.settle(
                claimed.eventId(), ExternalKnowledgeOperationStatus.CLAIMED, "worker-a",
                claimed.rowVersion(), ExternalIndexSettleCommand.aboutToSend("op-1", NOW), NOW).orElseThrow();
        KnowledgeExternalIndexOperation waiting = store.settle(
                sent.eventId(), ExternalKnowledgeOperationStatus.SUBMITTED, "worker-a",
                sent.rowVersion(), ExternalIndexSettleCommand.waitingRemote("task-1", NOW), NOW).orElseThrow();

        assertEquals("op-1", waiting.remoteOperationId());
        assertEquals(NOW, waiting.publishedAtEpochMillis(), "the send instant anchors the unknown-outcome deadline");

        KnowledgeExternalIndexOperation polled =
                store.claimPollBatch("poller-1", NOW, NOW + 5_000L, 10).getFirst();
        KnowledgeExternalIndexOperation verifying = store.settle(
                polled.eventId(), ExternalKnowledgeOperationStatus.WAITING_REMOTE, "poller-1",
                polled.rowVersion(), ExternalIndexSettleCommand.verifying(), NOW).orElseThrow();

        assertEquals("task-1", verifying.remoteTaskId());
        assertEquals("op-1", verifying.remoteOperationId());
        assertEquals(NOW, verifying.publishedAtEpochMillis());
        assertFalse(verifying.leaseOwner().isBlank(), "verification continues inside the same poll iteration");
    }

    private static KnowledgeExternalIndexOperation claimOne(
            InMemoryKnowledgeExternalIndexOutboxStore store,
            String owner
    ) {
        store.enqueue(pending("9001", 8));
        return store.claimBatch(owner, NOW, NOW + LEASE, 10).getFirst();
    }

    private static KnowledgeExternalIndexOperation enqueueDeadLetter(
            InMemoryKnowledgeExternalIndexOutboxStore store,
            String remoteOperationId,
            int attemptCount
    ) {
        KnowledgeExternalIndexOperation dead = new KnowledgeExternalIndexOperation(
                "7001",
                "OPENVIKING:doc:9001:1:UPSERT_DOCUMENT",
                KnowledgeExternalIndexBinding.OPENVIKING,
                ExternalKnowledgeOperationType.UPSERT_DOCUMENT,
                "1001",
                "9001",
                1L,
                "a".repeat(64),
                "viking://resources/rd-bot/kb/1001/documents/9001",
                "5001",
                "",
                ExternalKnowledgeOperationStatus.DEAD_LETTER,
                remoteOperationId.isBlank() ? "" : "task-1",
                remoteOperationId,
                "",
                0L,
                attemptCount,
                8,
                NOW,
                remoteOperationId.isBlank() ? 0L : NOW - 1_000L,
                "DEAD",
                "exhausted",
                4L,
                NOW,
                NOW
        );
        store.enqueue(dead);
        return dead;
    }

    private static KnowledgeExternalIndexOperation pending(String documentId, int maxAttempts) {
        return new KnowledgeExternalIndexOperation(
                "7001",
                "OPENVIKING:doc:" + documentId + ":1:UPSERT_DOCUMENT",
                KnowledgeExternalIndexBinding.OPENVIKING,
                ExternalKnowledgeOperationType.UPSERT_DOCUMENT,
                "1001",
                documentId,
                1L,
                "a".repeat(64),
                "viking://resources/rd-bot/kb/1001/documents/" + documentId,
                "5001",
                "",
                ExternalKnowledgeOperationStatus.PENDING,
                "",
                "",
                "",
                0L,
                0,
                maxAttempts,
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
