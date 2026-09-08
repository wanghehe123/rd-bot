package com.wish.rd.engine.requirement.audit;

import com.wish.rd.engine.requirement.audit.impl.InMemoryEvidenceRefResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditedCompletionGateTest {

    private final AuditedTaskStateCodec codec = new AuditedTaskStateCodec();
    private final InMemoryEvidenceRefResolver resolver = new InMemoryEvidenceRefResolver();
    private final AuditedCompletionGate gate = new AuditedCompletionGate(resolver);

    @Test
    void supportsCompletionWhenBlockingRecordsAreEvidencedAndLatestRunIsClean() {
        EvidenceRef evidence = new EvidenceRef(
                "audit-1", EvidenceSourceKind.HOST_VERIFICATION,
                "host-verification://artifacts/ok", "sha256:" + "a".repeat(64));
        resolver.put(evidence);
        AuditedTaskState state = completeHead(evidence);
        AuditRun run = run(AuditIntegrity.CLEAN, ContractAuditVerdict.ALIGNED, List.of());
        CompletionGateDecision decision = gate.evaluate(
                state, run, AuditedWritebackGateMode.ENFORCE);
        assertTrue(decision.supportsCompletion());
        assertTrue(decision.gapRecordIds().isEmpty());
        assertFalse(decision.shadowWouldReject());
    }

    @Test
    void rejectsWhenABlockingGateIsPendingAndCapsGapsAt32() {
        AuditedTaskState state = new AuditedTaskStateInitializer(codec)
                .initialize(AuditedTaskFixtures.task("[\"登录成功\",\"下单成功\"]", false));
        AuditRun run = run(AuditIntegrity.CLEAN, ContractAuditVerdict.ALIGNED, List.of("GATE-BUILD"));
        CompletionGateDecision decision = gate.evaluate(state, run, AuditedWritebackGateMode.ENFORCE);
        assertFalse(decision.supportsCompletion());
        assertTrue(decision.gapRecordIds().contains("GATE-BUILD"));
        assertTrue(decision.gapRecordIds().contains("AC-001"));
        assertTrue(decision.gapRecordIds().size() <= 32);
    }

    @Test
    void shadowModeRecordsWouldRejectButDoesNotBlock() {
        AuditedTaskState state = new AuditedTaskStateInitializer(codec)
                .initialize(AuditedTaskFixtures.task("[\"登录成功\"]", false));
        AuditRun run = run(AuditIntegrity.CLEAN, ContractAuditVerdict.ALIGNED, List.of("GATE-BUILD"));
        CompletionGateDecision decision = gate.evaluate(state, run, AuditedWritebackGateMode.SHADOW);
        assertTrue(decision.supportsCompletion());
        assertTrue(decision.shadowWouldReject());
        assertFalse(decision.gapRecordIds().isEmpty());
    }

    private AuditedTaskState completeHead(EvidenceRef evidence) {
        List<AuditedRecord> records = List.of(
                completed("AC-001", AuditedRecordKind.REQUIREMENT, evidence),
                completed("GATE-BUILD", AuditedRecordKind.GATE, evidence),
                completed("GATE-STATIC", AuditedRecordKind.GATE, evidence),
                completed("GATE-QA-EVIDENCE", AuditedRecordKind.GATE, evidence),
                completed("GATE-WORKSPACE-INTEGRITY", AuditedRecordKind.GATE, evidence),
                new AuditedRecord(
                        "ART-PR", AuditedRecordKind.ARTIFACT, false, "pr",
                        AuditedRecordStatus.PENDING, List.of(), "", "")
        );
        return codec.seal(new AuditedTaskState(
                "task-1", 2L, "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 1L, 1L),
                records, "audit-1"));
    }

    private static AuditedRecord completed(String id, AuditedRecordKind kind, EvidenceRef evidence) {
        return new AuditedRecord(id, kind, true, id, AuditedRecordStatus.COMPLETED, List.of(evidence), "", "");
    }

    private static AuditRun run(
            AuditIntegrity integrity,
            ContractAuditVerdict contractAudit,
            List<String> blockers
    ) {
        return new AuditRun(
                "audit-1", "task-1", "stage-1", "DETERMINISTIC_REVIEW", "cmd-1",
                blockers.isEmpty() ? AuditCompletion.COMPLETE : AuditCompletion.INCOMPLETE,
                integrity, contractAudit, List.of(), List.of(), List.of(), blockers, List.of(), 1L);
    }
}
