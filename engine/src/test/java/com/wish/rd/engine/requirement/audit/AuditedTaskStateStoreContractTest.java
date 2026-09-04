package com.wish.rd.engine.requirement.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class AuditedTaskStateStoreContractTest {

    protected abstract AuditedTaskStateStore newStore();

    @Test
    void appendsHeadRevisionAndListsAuditRuns() {
        AuditedTaskStateStore store = newStore();
        AuditedTaskState first = sealed(1L, "AC-001");
        AuditRun run = auditRun("cmd-1", first);
        AuditedTaskState stored = store.appendRevision(1L, first, run);
        assertEquals(first.stateHash(), stored.stateHash());
        assertEquals(first, store.head("task-1").orElseThrow());
        assertEquals(List.of(run.auditRunId()), store.listAuditRuns("task-1").stream()
                .map(AuditRun::auditRunId)
                .toList());
    }

    @Test
    void sameVersionSameHashIsIdempotent() {
        AuditedTaskStateStore store = newStore();
        AuditedTaskState first = sealed(1L, "AC-001");
        AuditRun run = auditRun("cmd-1", first);
        store.appendRevision(1L, first, run);
        AuditedTaskState replay = store.appendRevision(1L, first, run);
        assertEquals(first.stateHash(), replay.stateHash());
        assertEquals(1, store.listAuditRuns("task-1").size());
    }

    @Test
    void sameVersionDifferentHashIsRejected() {
        AuditedTaskStateStore store = newStore();
        AuditedTaskState first = sealed(1L, "AC-001");
        store.appendRevision(1L, first, auditRun("cmd-1", first));
        AuditedTaskState conflict = sealed(1L, "AC-002");
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.appendRevision(1L, conflict, auditRun("cmd-2", conflict)));
        assertTrue(failure.getMessage().toLowerCase().contains("hash"));
        assertEquals(first.stateHash(), store.head("task-1").orElseThrow().stateHash());
    }

    @Test
    void expectedVersionMismatchIsRejected() {
        AuditedTaskStateStore store = newStore();
        AuditedTaskState first = sealed(1L, "AC-001");
        store.appendRevision(1L, first, auditRun("cmd-1", first));
        AuditedTaskState second = sealed(2L, "AC-001");
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.appendRevision(1L, second, auditRun("cmd-2", second)));
        assertTrue(failure.getMessage().toLowerCase().contains("expected"));
        assertEquals(1L, store.head("task-1").orElseThrow().stateVersion());
    }

    @Test
    void initializeIfAbsentIsIdempotentAndRejectsContractMismatch() {
        AuditedTaskStateStore store = newStore();
        AuditedTaskState first = sealed(1L, "AC-001");
        assertEquals(first.stateHash(), store.initializeIfAbsent(first).stateHash());
        assertEquals(first.stateHash(), store.initializeIfAbsent(first).stateHash());
        AuditedTaskState other = new AuditedTaskStateCodec().seal(new AuditedTaskState(
                "task-1",
                1L,
                "",
                new AuditedContractRef("sha256:" + "d".repeat(64), 1L, 1L),
                first.records(),
                ""));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> store.initializeIfAbsent(other));
        assertTrue(failure.getMessage().toLowerCase().contains("contract"));
    }

    @Test
    void bindCompletionIsIdempotentForSameBinding() {
        AuditedTaskStateStore store = newStore();
        AuditedTaskState first = sealed(1L, "AC-001");
        AuditRun run = auditRun("cmd-1", first);
        store.appendRevision(1L, first, run);
        store.bindCompletion("task-1", run.auditRunId(), first.stateVersion(), first.stateHash());
        store.bindCompletion("task-1", run.auditRunId(), first.stateVersion(), first.stateHash());
        CompletionBinding binding = store.completionBinding("task-1").orElseThrow();
        assertEquals(run.auditRunId(), binding.auditRunId());
        assertEquals(first.stateHash(), binding.stateHash());
        AuditedTaskState other = sealed(1L, "AC-009");
        assertThrows(IllegalStateException.class, () -> store.bindCompletion(
                "task-1", run.auditRunId(), other.stateVersion(), other.stateHash()));
    }

    private static AuditedTaskState sealed(long version, String requirementId) {
        return new AuditedTaskStateCodec().seal(new AuditedTaskState(
                "task-1",
                version,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 1L, 1L),
                List.of(new AuditedRecord(
                        requirementId,
                        AuditedRecordKind.REQUIREMENT,
                        true,
                        "criterion " + requirementId,
                        AuditedRecordStatus.PENDING,
                        List.of(),
                        "",
                        "")),
                ""));
    }

    private static AuditRun auditRun(String commandId, AuditedTaskState state) {
        return new AuditRun(
                "audit-" + commandId,
                state.taskId(),
                "stage-1",
                "HOST_VERIFY",
                commandId,
                AuditCompletion.INCOMPLETE,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                List.of(),
                List.of(state.records().getFirst().id()),
                List.of(),
                List.of(),
                List.of(),
                1_700_000_000_000L
        );
    }
}
