package com.wish.rd.engine.requirement.manager;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.audit.AuditedContractRef;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordKind;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateCodec;
import com.wish.rd.engine.requirement.audit.EvidenceRef;
import com.wish.rd.engine.requirement.audit.EvidenceSourceKind;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagerPolicyTest {

    @Test
    void qaWithPendingBlockingAcceptanceExecutesBoundedCoding() {
        ManagerPolicy.Output output = ManagerPolicy.decide(input(
                "ROLE_EXECUTION:QA_AGENT",
                false,
                false,
                sealed(List.of(pendingRequirement("AC-003"))),
                1,
                0));

        assertEquals(ManagerRoute.EXECUTE, output.decision().route());
        assertEquals(AgentRole.CODING_AGENT.name(), output.decision().executorRoute());
        assertEquals(List.of("AC-003"), output.decision().targetRecordIds());
        assertTrue(output.decision().boundedContract().contains("AC-003"));
        assertTrue(output.gapFix());
        assertTrue(output.continuation().isTerminal());
    }

    @Test
    void qaWithAllBlockingRequirementsCompletedContinuesToReview() {
        ManagerPolicy.Output output = ManagerPolicy.decide(input(
                "ROLE_EXECUTION:QA_AGENT",
                false,
                false,
                sealed(List.of(completedRequirement("AC-001"))),
                1,
                0));

        assertEquals(ManagerRoute.DONE, output.decision().route());
        assertEquals("DETERMINISTIC_REVIEW", output.continuation().stage());
        assertFalse(output.gapFix());
    }

    @Test
    void hostVerifySuccessExecutesQaWithoutGapFix() {
        ManagerPolicy.Output output = ManagerPolicy.decide(input(
                "HOST_VERIFY",
                false,
                false,
                sealed(List.of(pendingRequirement("AC-001"))),
                1,
                0));

        assertEquals(ManagerRoute.EXECUTE, output.decision().route());
        assertEquals(AgentRole.QA_AGENT.name(), output.decision().executorRoute());
        assertEquals("ROLE_EXECUTION:QA_AGENT", output.continuation().stage());
        assertFalse(output.gapFix());
    }

    @Test
    void pausedTaskBlocksWithoutDone() {
        ManagerPolicy.Output output = ManagerPolicy.decide(input(
                "ROLE_EXECUTION:QA_AGENT",
                true,
                false,
                sealed(List.of(pendingRequirement("AC-001"))),
                1,
                0));

        assertEquals(ManagerRoute.BLOCKED, output.decision().route());
        assertTrue(output.continuation().isTerminal());
        assertFalse(output.gapFix());
    }

    @Test
    void missingOperatorMaterialAsks() {
        ManagerPolicy.Output output = ManagerPolicy.decide(input(
                "HOST_VERIFY",
                false,
                true,
                sealed(List.of(pendingRequirement("AC-001"))),
                1,
                0));

        assertEquals(ManagerRoute.ASK, output.decision().route());
        assertTrue(output.continuation().isTerminal());
        assertFalse(output.gapFix());
    }

    @Test
    void exhaustedCodingBudgetBlocksInsteadOfDone() {
        ManagerPolicy.Output output = ManagerPolicy.decide(input(
                "ROLE_EXECUTION:QA_AGENT",
                false,
                false,
                sealed(List.of(pendingRequirement("AC-001"))),
                ManagerPolicy.MAX_CODING_ATTEMPTS,
                AgentRemediationKind.MANAGER_GAP_FIX.maximumRounds()));

        assertEquals(ManagerRoute.BLOCKED, output.decision().route());
        assertTrue(output.continuation().isTerminal());
        assertFalse(output.gapFix());
    }

    private static ManagerPolicy.Input input(
            String previousStage,
            boolean paused,
            boolean needUserInput,
            AuditedTaskState head,
            int usedCodingAttempts,
            int usedManagerGapFixRounds
    ) {
        return new ManagerPolicy.Input(
                "task-1",
                "source-1",
                previousStage,
                paused,
                needUserInput,
                head,
                usedCodingAttempts,
                usedManagerGapFixRounds);
    }

    private static AuditedTaskState sealed(List<AuditedRecord> records) {
        return new AuditedTaskStateCodec().seal(new AuditedTaskState(
                "task-1",
                1L,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 1L, 1L),
                records,
                "audit-1"));
    }

    private static AuditedRecord pendingRequirement(String id) {
        return new AuditedRecord(
                id,
                AuditedRecordKind.REQUIREMENT,
                true,
                "criterion " + id,
                AuditedRecordStatus.PENDING,
                List.of(),
                "",
                "");
    }

    private static AuditedRecord completedRequirement(String id) {
        EvidenceRef evidence = new EvidenceRef(
                "audit-1",
                EvidenceSourceKind.HOST_ASSERTION,
                "host-assertion://acceptance/" + id,
                "sha256:" + "a".repeat(64));
        return new AuditedRecord(
                id,
                AuditedRecordKind.REQUIREMENT,
                true,
                "criterion " + id,
                AuditedRecordStatus.COMPLETED,
                List.of(evidence),
                "",
                "");
    }
}
