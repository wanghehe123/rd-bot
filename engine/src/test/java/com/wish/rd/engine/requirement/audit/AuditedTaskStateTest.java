package com.wish.rd.engine.requirement.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditedTaskStateTest {

    @Test
    void rejectsDuplicateRecordIds() {
        AuditedRecord first = pending("AC-001", AuditedRecordKind.REQUIREMENT, true, "first");
        AuditedRecord duplicate = pending("AC-001", AuditedRecordKind.REQUIREMENT, true, "again");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> state(List.of(first, duplicate)));
        assertTrue(failure.getMessage().contains("AC-001"));
    }

    @Test
    void completedRecordRequiresAtLeastOneEvidenceRef() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new AuditedRecord(
                "GATE-BUILD",
                AuditedRecordKind.GATE,
                true,
                "build",
                AuditedRecordStatus.COMPLETED,
                List.of(),
                "",
                ""));
        assertTrue(failure.getMessage().toLowerCase().contains("evidence"));
    }

    @Test
    void rejectsMoreThan256Records() {
        List<AuditedRecord> records = IntStream.rangeClosed(1, 257)
                .mapToObj(index -> pending("R-" + index, AuditedRecordKind.FACT, false, "n" + index))
                .toList();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> state(records));
        assertTrue(failure.getMessage().contains("256"));
    }

    @Test
    void rejectsMoreThan16EvidenceRefsPerRecord() {
        List<EvidenceRef> refs = IntStream.rangeClosed(1, 17)
                .mapToObj(index -> new EvidenceRef(
                        "run-1",
                        EvidenceSourceKind.HOST_VERIFICATION,
                        "host-verification://runs/1/artifacts/" + index,
                        "sha256:" + "a".repeat(64)))
                .toList();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new AuditedRecord(
                "GATE-BUILD",
                AuditedRecordKind.GATE,
                true,
                "build",
                AuditedRecordStatus.COMPLETED,
                refs,
                "",
                ""));
        assertTrue(failure.getMessage().contains("16"));
    }

    @Test
    void blockedRecordRequiresBlockedReason() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new AuditedRecord(
                "GATE-WORKSPACE-INTEGRITY",
                AuditedRecordKind.GATE,
                true,
                "integrity",
                AuditedRecordStatus.BLOCKED,
                List.of(),
                "",
                ""));
        assertTrue(failure.getMessage().toLowerCase().contains("blockedreason"));
    }

    @Test
    void copiesRecordsDefensivelyAndAcceptsValidCompletedEvidence() {
        EvidenceRef evidence = new EvidenceRef(
                "audit-1",
                EvidenceSourceKind.HOST_VERIFICATION,
                "host-verification://runs/1/artifacts/log",
                "sha256:" + "b".repeat(64));
        List<AuditedRecord> source = new ArrayList<>(List.of(
                pending("AC-001", AuditedRecordKind.REQUIREMENT, true, "criterion"),
                new AuditedRecord(
                        "GATE-BUILD",
                        AuditedRecordKind.GATE,
                        true,
                        "build",
                        AuditedRecordStatus.COMPLETED,
                        List.of(evidence),
                        "",
                        "")));
        AuditedTaskState state = state(source);
        source.clear();
        assertEquals(2, state.records().size());
        assertEquals(AuditedRecordStatus.COMPLETED, state.record("GATE-BUILD").status());
        assertEquals(1, state.record("GATE-BUILD").evidenceRefs().size());
        assertThrows(UnsupportedOperationException.class, () -> state.records().clear());
    }

    private static AuditedRecord pending(String id, AuditedRecordKind kind, boolean blocking, String text) {
        return new AuditedRecord(
                id, kind, blocking, text, AuditedRecordStatus.PENDING, List.of(), "", "");
    }

    private static AuditedTaskState state(List<AuditedRecord> records) {
        return new AuditedTaskState(
                "task-1",
                1L,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 3L, 9L),
                records,
                "");
    }
}
