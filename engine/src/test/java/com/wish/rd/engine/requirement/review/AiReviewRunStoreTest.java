package com.wish.rd.engine.requirement.review;

import com.wish.rd.engine.requirement.review.impl.InMemoryAiReviewRunStore;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiReviewRunStoreTest {

    @Test
    void keepsTerminalAttemptImmutableAndCreatesParentLinkedRetry() {
        InMemoryAiReviewRunStore store = new InMemoryAiReviewRunStore();
        AiReviewRun run = store.create(AiReviewRun.created("review-1", "task-1", 1, "", "model-a", 100L));

        store.transition(run.runId(), AiReviewRunStatus.CREATED, AiReviewRunStatus.PACKAGING,
                "SYSTEM", "package evidence", "", "", 110L);
        store.transition(run.runId(), AiReviewRunStatus.PACKAGING, AiReviewRunStatus.REVIEWING,
                "SYSTEM", "call model", "", "", 120L);
        store.transition(run.runId(), AiReviewRunStatus.REVIEWING, AiReviewRunStatus.FAILED_RETRYABLE,
                "SYSTEM", "provider timeout", "PROVIDER_TIMEOUT", "timeout", 130L);

        assertThrows(IllegalStateException.class, () -> store.transition(
                run.runId(), AiReviewRunStatus.FAILED_RETRYABLE, AiReviewRunStatus.REVIEWING,
                "SYSTEM", "illegal reopen", "", "", 140L));

        AiReviewRun retry = store.retry(run.runId(), "review-2", 150L);
        assertEquals(2, retry.attemptNo());
        assertEquals(run.runId(), retry.parentRunId());
        assertEquals(AiReviewRunStatus.CREATED, retry.status());
        assertEquals(AiReviewRunStatus.FAILED_RETRYABLE, store.find(run.runId()).orElseThrow().status());
        assertEquals(3, store.listEvents(run.runId()).size());
    }

    @Test
    void rejectsConcurrentTransitionFromStaleExpectedStatus() {
        InMemoryAiReviewRunStore store = new InMemoryAiReviewRunStore();
        AiReviewRun run = store.create(AiReviewRun.created("review-1", "task-1", 1, "", "model-a", 100L));
        store.transition(run.runId(), AiReviewRunStatus.CREATED, AiReviewRunStatus.PACKAGING,
                "SYSTEM", "package", "", "", 110L);

        assertThrows(IllegalStateException.class, () -> store.transition(
                run.runId(), AiReviewRunStatus.CREATED, AiReviewRunStatus.CANCELLED,
                "USER", "cancel stale snapshot", "", "", 120L));
    }
}
