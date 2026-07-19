package com.wish.rd.engine.requirement.job;

import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRequirementDeliveryJobStoreTest {

    @Test
    void shouldReuseClaimLeaseRecoverAndDeadLetterAtAttemptLimit() {
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        RequirementDeliveryJob pending = RequirementDeliveryJob.pending("job-1", "task-1", 2, 100L);

        assertEquals(pending, store.enqueue(pending));
        assertEquals(pending.jobId(), store.enqueue(
                RequirementDeliveryJob.pending("job-2", "task-1", 2, 101L)).jobId());

        RequirementDeliveryJob firstClaim = store.claim("task-1", "worker-a", 110L, 50L).orElseThrow();
        assertEquals(1, firstClaim.attemptNo());
        assertFalse(store.claim("task-1", "worker-b", 120L, 50L).isPresent());
        assertEquals(180L, store.heartbeat(firstClaim.jobId(), "worker-a", 130L, 50L).leaseUntilEpochMillis());

        RequirementDeliveryJob retryable = store.fail(firstClaim.jobId(), "worker-a", "boom", 140L);
        assertEquals(RequirementDeliveryJobStatus.FAILED_RETRYABLE, retryable.status());
        RequirementDeliveryJob secondClaim = store.claim("task-1", "worker-b", 141L, 50L).orElseThrow();
        assertEquals(2, secondClaim.attemptNo());
        RequirementDeliveryJob dead = store.fail(secondClaim.jobId(), "worker-b", "boom again", 150L);
        assertEquals(RequirementDeliveryJobStatus.DEAD_LETTERED, dead.status());
        assertTrue(store.recoverable(1_000L).isEmpty());
    }

    @Test
    void shouldAllowExpiredRunningLeaseToBeClaimedByAnotherWorker() {
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        store.enqueue(RequirementDeliveryJob.pending("job-1", "task-1", 3, 100L));
        store.claim("task-1", "worker-a", 110L, 10L).orElseThrow();

        RequirementDeliveryJob reclaimed = store.claim("task-1", "worker-b", 121L, 20L).orElseThrow();

        assertEquals("worker-b", reclaimed.leaseOwner());
        assertEquals(2, reclaimed.attemptNo());
    }

    @Test
    void shouldRequeueSucceededDispatchForManualTaskRecovery() {
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        store.enqueue(RequirementDeliveryJob.pending("job-1", "task-1", 3, 100L));
        RequirementDeliveryJob running = store.claim("task-1", "worker-a", 110L, 10L).orElseThrow();
        store.complete(running.jobId(), "worker-a", 120L);

        RequirementDeliveryJob requeued = store.enqueue(
                RequirementDeliveryJob.pending("job-2", "task-1", 3, 130L));

        assertEquals("job-1", requeued.jobId());
        assertEquals(RequirementDeliveryJobStatus.PENDING, requeued.status());
        assertEquals(0, requeued.attemptNo());
    }

    @Test
    void shouldDeadLetterExpiredRunningJobWhenAttemptsAreExhausted() {
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        store.enqueue(RequirementDeliveryJob.pending("job-1", "task-1", 2, 100L));
        store.claim("task-1", "worker-a", 110L, 10L).orElseThrow();
        store.fail(store.findByTask("task-1").orElseThrow().jobId(), "worker-a", "first", 120L);
        store.claim("task-1", "worker-b", 130L, 10L).orElseThrow();

        assertTrue(store.recoverable(141L).stream().anyMatch(job -> job.taskId().equals("task-1")));
        assertFalse(store.claim("task-1", "worker-c", 141L, 10L).isPresent());

        RequirementDeliveryJob dead = store.deadLetterExpiredExhausted("task-1", 141L, "lease expired at max attempts")
                .orElseThrow();
        assertEquals(RequirementDeliveryJobStatus.DEAD_LETTERED, dead.status());
        assertTrue(store.recoverable(1_000L).isEmpty());
    }

    @Test
    void shouldCancelRunningJobForOperatorStop() {
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        store.enqueue(RequirementDeliveryJob.pending("job-1", "task-1", 3, 100L));
        store.claim("task-1", "worker-a", 110L, 50L).orElseThrow();

        RequirementDeliveryJob cancelled = store.cancelByTask("task-1", "operator stop", 120L).orElseThrow();

        assertEquals(RequirementDeliveryJobStatus.CANCELLED, cancelled.status());
        assertFalse(store.claim("task-1", "worker-b", 130L, 50L).isPresent());
    }

    @Test
    void shouldRequeueDeadLetteredDispatchForManualReplay() {
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        store.enqueue(RequirementDeliveryJob.pending("job-1", "task-1", 1, 100L));
        RequirementDeliveryJob running = store.claim("task-1", "worker-a", 110L, 10L).orElseThrow();
        store.fail(running.jobId(), "worker-a", "exhausted", 120L);

        RequirementDeliveryJob requeued = store.enqueue(
                RequirementDeliveryJob.pending("job-2", "task-1", 3, 130L));

        assertEquals("job-1", requeued.jobId());
        assertEquals(RequirementDeliveryJobStatus.PENDING, requeued.status());
        assertEquals(0, requeued.attemptNo());
    }
}
