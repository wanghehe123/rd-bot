package com.wish.rd.exec.repair.alert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import com.wish.rd.exec.repair.alert.model.RepairAlertType;

class RepairAlertTypeTest {

    @Test
    void shouldExposeMultiAgentWorkflowAlertTypes() {
        assertNotNull(RepairAlertType.STAGE_FAILED_RETRYABLE);
        assertNotNull(RepairAlertType.STAGE_FAILED_NEEDS_HUMAN);
        assertNotNull(RepairAlertType.PROVIDER_FALLBACK);
        assertNotNull(RepairAlertType.POLICY_WAITING_APPROVAL);
        assertNotNull(RepairAlertType.QA_FAILED);
        assertNotNull(RepairAlertType.DELIVERY_REVIEW_FAILED);
        assertNotNull(RepairAlertType.PR_PUBLICATION_FAILED);
        assertNotNull(RepairAlertType.EXPERIENCE_CAPTURE_FAILED);
        assertNotNull(RepairAlertType.WORKFLOW_DEAD_LETTERED);
        assertNotNull(RepairAlertType.SKILL_POLICY_REJECTED);
    }
}
