package com.wish.rd.rag.retrieval.run;

import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryRetrievalRunStoreTest {

    @Test
    void transitionsWithAnAppendOnlyEventAndRejectsStaleVersion() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        RetrievalRun created = store.create(run("run-1", 1, ""));

        RetrievalRun planning = store.transition(
                created.runId(), created.version(), RetrievalRunStatus.PLANNING, 0,
                null, "", "", "", "system", 101L
        );

        assertEquals(RetrievalRunStatus.PLANNING, planning.status());
        assertEquals(1L, planning.version());
        assertEquals(1, store.listEvents(created.runId()).size());
        assertThrows(IllegalStateException.class, () -> store.transition(
                created.runId(), created.version(), RetrievalRunStatus.RETRIEVING, 0,
                null, "", "", "", "system", 102L
        ));
    }

    @Test
    void retriesOnlyFromATerminalRunAndKeepsTheParentImmutable() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        RetrievalRun created = store.create(run("run-1", 1, ""));
        RetrievalRun retryable = store.transition(
                created.runId(), created.version(), RetrievalRunStatus.PLANNING, 0,
                null, "", "", "", "system", 101L
        );
        retryable = store.transition(
                retryable.runId(), retryable.version(), RetrievalRunStatus.FAILED_RETRYABLE, 0,
                null, "provider unavailable", "PROVIDER", "timeout", "system", 102L
        );

        RetrievalRun retry = store.retry(retryable.runId(), "run-2", 103L);

        assertEquals(2, retry.attemptNo());
        assertEquals("run-1", retry.parentRunId());
        assertEquals(RetrievalRunStatus.CREATED, retry.status());
        assertEquals(RetrievalRunStatus.FAILED_RETRYABLE, store.find("run-1").orElseThrow().status());
        assertEquals(List.of("run-1", "run-2"), store.listByTask("task-1").stream().map(RetrievalRun::runId).toList());
    }

    @Test
    void rejectsDuplicateIdempotencyKeys() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        store.create(run("run-1", 1, ""));

        assertThrows(IllegalStateException.class, () -> store.create(run("run-2", 1, "")));
    }

    @Test
    void retryIsIdempotentWhenParentAlreadyHasAChild() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        RetrievalRun created = store.create(run("run-1", 1, ""));
        RetrievalRun terminal = store.transition(
                created.runId(), created.version(), RetrievalRunStatus.PLANNING, 0,
                null, "", "", "", "system", 101L
        );
        terminal = store.transition(
                terminal.runId(), terminal.version(), RetrievalRunStatus.FAILED_RETRYABLE, 0,
                null, "provider unavailable", "PROVIDER", "timeout", "system", 102L
        );

        RetrievalRun firstChild = store.retry(terminal.runId(), "run-2", 103L);
        RetrievalRun secondChild = store.retry(terminal.runId(), "run-3", 104L);

        assertEquals(firstChild.runId(), secondChild.runId());
        assertEquals(2, store.listByTask("task-1").size());
    }

    private static RetrievalRun run(String runId, int attemptNo, String parentRunId) {
        return new RetrievalRun(
                runId, "task-1", RetrievalConsumerType.BUG_FIX, "", "", attemptNo, parentRunId,
                "task-1:BUG_FIX:" + attemptNo, RetrievalRunStatus.CREATED, List.of("kb-1"),
                "sha256:query", "redacted query", 0, 3, 18_000, 0, 0,
                EvidenceQualityDecision.SUFFICIENT, "", "", "", "", 0L, 0L, 100L, 100L
        );
    }
}
