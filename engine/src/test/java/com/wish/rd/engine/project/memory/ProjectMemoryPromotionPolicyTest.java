package com.wish.rd.engine.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectMemoryPromotionPolicyTest {

    private final ProjectMemoryPromotionPolicy policy = new ProjectMemoryPromotionPolicy();

    @Test
    void proceduralStageOutputStaysCandidateUnlessDeliveryAndQaOrAdministratorConfirmationExist() {
        assertEquals(ProjectMemoryRevisionStatus.CANDIDATE, policy.statusFor(
                ProjectMemoryType.PROCEDURAL, new ProjectMemoryPromotionEvidence(true, false, false, false)
        ));
        assertEquals(ProjectMemoryRevisionStatus.ACTIVE, policy.statusFor(
                ProjectMemoryType.PROCEDURAL, new ProjectMemoryPromotionEvidence(true, true, false, false)
        ));
        assertEquals(ProjectMemoryRevisionStatus.ACTIVE, policy.statusFor(
                ProjectMemoryType.PROCEDURAL, new ProjectMemoryPromotionEvidence(false, false, false, true)
        ));
    }

    @Test
    void failedOrCancelledOutputCannotAutomaticallyBecomeProceduralMemory() {
        assertEquals(ProjectMemoryRevisionStatus.CANDIDATE, policy.statusFor(
                ProjectMemoryType.PROCEDURAL, new ProjectMemoryPromotionEvidence(true, true, true, false)
        ));
    }
}
