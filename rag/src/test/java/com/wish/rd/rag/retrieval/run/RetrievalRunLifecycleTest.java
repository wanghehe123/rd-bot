package com.wish.rd.rag.retrieval.run;

import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalRunLifecycleTest {

    @Test
    void recordsACompleteSuccessfulEvidenceLifecycle() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(
                store, () -> "run-" + ids.incrementAndGet(), () -> 100L
        );

        RetrievalRun started = lifecycle.start("task-1", RetrievalConsumerType.BUG_FIX, "", "",
                "payment service timeout", List.of("kb-1"));
        RetrievalRun completed = lifecycle.complete(started.runId(), 12, 4, false, "");

        assertEquals(RetrievalRunStatus.SUCCEEDED, completed.status());
        assertEquals(12, completed.candidateCount());
        assertEquals(4, completed.selectedEvidenceCount());
        assertEquals(12, store.find(completed.runId()).orElseThrow().candidateCount());
        assertEquals(5, store.listEvents(completed.runId()).size());
    }

    @Test
    void turnsAnEvidenceGapIntoAnActionableWaitingInputTerminalState() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(store, () -> "run-1", () -> 100L);

        RetrievalRun started = lifecycle.start("task-1", RetrievalConsumerType.REQUIREMENT_BASE, "", "",
                "build a payment export", List.of());
        RetrievalRun waiting = lifecycle.waitForInput(started.runId(), "需要验收标准或仓库范围");

        assertEquals(RetrievalRunStatus.WAITING_INPUT, waiting.status());
        assertTrue(waiting.stopReason().contains("验收标准"));
    }

    @Test
    void failRetryableAndFailNeedsHumanReachTerminalFailureStates() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(
                store, () -> "run-" + ids.incrementAndGet(), () -> 100L
        );

        RetrievalRun retryableSource = lifecycle.start("task-1", RetrievalConsumerType.BUG_FIX, "", "",
                "payment timeout", List.of("kb-1"));
        RetrievalRun retryable = lifecycle.failRetryable(
                retryableSource.runId(), "PROVIDER", "all retrieval channels failed"
        );
        RetrievalRun needsHumanSource = lifecycle.start("task-2", RetrievalConsumerType.BUG_FIX, "", "",
                "unsafe scope", List.of("kb-1"));
        RetrievalRun needsHuman = lifecycle.failNeedsHuman(
                needsHumanSource.runId(), "POLICY", "out of approved repository scope"
        );

        assertEquals(RetrievalRunStatus.FAILED_RETRYABLE, retryable.status());
        assertEquals("PROVIDER", retryable.errorCategory());
        assertEquals(RetrievalRunStatus.FAILED_NEEDS_HUMAN, needsHuman.status());
        assertEquals("POLICY", needsHuman.errorCategory());
    }

    @Test
    void reusesNonTerminalRunAndRetriesTerminalRunForSameConsumerRole() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(
                store, () -> "run-" + ids.incrementAndGet(), () -> 100L
        );

        RetrievalRun first = lifecycle.start("task-1", RetrievalConsumerType.AGENT_ROLE, "CODING_AGENT", "",
                "export payment", List.of());
        RetrievalRun reused = lifecycle.start("task-1", RetrievalConsumerType.AGENT_ROLE, "CODING_AGENT", "",
                "export payment", List.of());
        assertEquals(first.runId(), reused.runId());
        assertEquals(1, store.listByTask("task-1").size());

        lifecycle.waitForInput(first.runId(), "need more logs");
        RetrievalRun retried = lifecycle.start("task-1", RetrievalConsumerType.AGENT_ROLE, "CODING_AGENT", "",
                "export payment", List.of());

        assertEquals(2, retried.attemptNo());
        assertEquals(first.runId(), retried.parentRunId());
        assertEquals(RetrievalRunStatus.RETRIEVING, retried.status());
        assertEquals(2, store.listByTask("task-1").size());
    }

    @Test
    void completeWithZeroSelectedEvidenceDoesNotSucceedAsSufficient() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(store, () -> "run-1", () -> 100L);

        RetrievalRun started = lifecycle.start("task-1", RetrievalConsumerType.BUG_FIX, "", "", "q", List.of());
        RetrievalRun terminal = lifecycle.complete(started.runId(), 0, 0, false, "无可用证据");

        assertEquals(RetrievalRunStatus.WAITING_INPUT, terminal.status());
        assertEquals(EvidenceQualityDecision.NEED_INPUT, terminal.qualityDecision());
    }
}
