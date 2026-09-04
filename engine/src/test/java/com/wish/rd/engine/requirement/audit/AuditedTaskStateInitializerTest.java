package com.wish.rd.engine.requirement.audit;

import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditedTaskStateInitializerTest {

    private final AuditedTaskStateInitializer initializer = new AuditedTaskStateInitializer(new AuditedTaskStateCodec());

    @Test
    void initializesPendingAcceptanceGatesAndPrArtifact() {
        RdRequirementTask task = AuditedTaskFixtures.task("[\"完整验收 A\",\"完整验收 B\"]", false);
        AuditedTaskState state = initializer.initialize(task);

        assertEquals(1L, state.stateVersion());
        assertEquals("task-1", state.taskId());
        assertEquals(2L, state.contractRef().taskVersionAtFreeze());
        assertEquals(7L, state.contractRef().fencingTokenAtFreeze());
        assertTrue(state.contractRef().acceptanceCriteriaHash().startsWith("sha256:"));
        assertEquals(7, state.records().size());
        assertEquals(List.of("AC-001", "AC-002"), state.records().stream()
                .filter(record -> record.kind() == AuditedRecordKind.REQUIREMENT)
                .map(AuditedRecord::id)
                .toList());
        assertEquals(
                List.of("GATE-BUILD", "GATE-STATIC", "GATE-QA-EVIDENCE", "GATE-WORKSPACE-INTEGRITY"),
                state.records().stream()
                        .filter(record -> record.kind() == AuditedRecordKind.GATE)
                        .map(AuditedRecord::id)
                        .toList());
        assertEquals("ART-PR", state.record("ART-PR").id());
        assertTrue(state.records().stream().allMatch(record ->
                record.status() == AuditedRecordStatus.PENDING && record.evidenceRefs().isEmpty()));
        assertTrue(state.find("GATE-HOST-ASSERTION").isEmpty());
    }

    @Test
    void appendsHostAssertionGateWhenBundlePresent() {
        RdRequirementTask task = AuditedTaskFixtures.task("[\"一条验收\"]", true);
        AuditedTaskState state = initializer.initialize(task);
        AuditedRecord gate = state.record("GATE-HOST-ASSERTION");
        assertEquals(AuditedRecordKind.GATE, gate.kind());
        assertTrue(gate.blocking());
        assertEquals(AuditedRecordStatus.PENDING, gate.status());
        assertEquals(1 + 4 + 1 + 1, state.records().size());
    }
}
