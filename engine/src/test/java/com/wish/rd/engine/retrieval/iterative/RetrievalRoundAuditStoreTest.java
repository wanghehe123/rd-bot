package com.wish.rd.engine.retrieval.iterative;

import com.wish.rd.engine.retrieval.iterative.impl.InMemoryRetrievalRoundAuditStore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies round audit persistence remains append-only and ordered per retrieval run. */
class RetrievalRoundAuditStoreTest {

    @Test
    void appendsAndListsRoundsInOrder() {
        InMemoryRetrievalRoundAuditStore store = new InMemoryRetrievalRoundAuditStore();
        store.append(new RetrievalRoundAudit(
                "a-2", "task-1", "run-1", 2, "query-2", List.of("c2"), List.of("s2"),
                List.of("API_CONTRACT"), 1, 64, 96, "MAX_ROUNDS", "project/repo", 102L
        ));
        store.append(new RetrievalRoundAudit(
                "a-1", "task-1", "run-1", 1, "query-1", List.of("c1"), List.of("s1"),
                List.of("DATABASE_SCHEMA"), 1, 32, 48, "CONTINUE", "project/repo", 101L
        ));

        assertEquals(List.of(1, 2), store.listByRun("run-1").stream()
                .map(RetrievalRoundAudit::roundNo).toList());
        assertEquals(List.of("a-1", "a-2"), store.listByRun("run-1").stream()
                .map(RetrievalRoundAudit::auditId).toList());
    }
}
