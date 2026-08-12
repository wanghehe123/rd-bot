package com.wish.rd.engine.retry;

import com.wish.rd.engine.retry.impl.InMemoryTaskRetryFailureProvenanceStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryTaskRetryFailureProvenanceStoreTest {

    @Test
    void rejectsReusingAProvenanceIdForAnotherFailureSnapshot() {
        InMemoryTaskRetryFailureProvenanceStore store = new InMemoryTaskRetryFailureProvenanceStore();
        store.save(provenance(31L, 41L));

        assertThrows(IllegalStateException.class, () -> store.save(provenance(32L, 42L)));
    }

    @Test
    void rejectsTwoOutcomeStatusesForTheSameTaskVersionAndFenceSnapshot() {
        InMemoryTaskRetryFailureProvenanceStore store = new InMemoryTaskRetryFailureProvenanceStore();
        store.save(provenance(31L, 41L));
        TaskRetryFailureProvenance conflicting = new TaskRetryFailureProvenance(
                "provenance-2", "task-1", "command-2", 2,
                "ROLE_EXECUTION:CODING_AGENT", TaskFailurePhase.AGENT_ROLE,
                RdTaskStatus.FAILED_NEEDS_HUMAN, 31L, 41L, "stage-run-2", "", "",
                "policy-1", "sha256:" + "a".repeat(64), "", "BUSINESS", 101L);

        assertThrows(IllegalStateException.class, () -> store.save(conflicting));
    }

    private TaskRetryFailureProvenance provenance(long version, long fence) {
        return new TaskRetryFailureProvenance(
                "provenance-1", "task-1", "command-1", 2,
                "ROLE_EXECUTION:CODING_AGENT", TaskFailurePhase.AGENT_ROLE,
                RdTaskStatus.FAILED_RETRYABLE, version, fence, "stage-run-1", "", "",
                "policy-1", "sha256:" + "a".repeat(64), "", "TECHNICAL", 100L);
    }
}
