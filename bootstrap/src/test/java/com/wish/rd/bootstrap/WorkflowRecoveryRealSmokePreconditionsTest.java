package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRecoveryRealSmokePreconditionsTest {

    @Test
    void shouldRequireRealHttpPostgresAndRestartEvidenceProperties() {
        List<String> missing = WorkflowRecoveryRealSmokeTest.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.workflow.recovery.smoke.production-evidence"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.base-url"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.postgres-url"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.task-id"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.stage-run-count-before-restart"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.stage-event-count-before-restart"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.startup-log-evidence-uri"));
        assertTrue(missing.contains("rd.workflow.recovery.smoke.database-snapshot-evidence-uri"));
    }
}
