package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QaFailureBlockerRealSmokePreconditionsTest {

    @Test
    void shouldListMissingRequiredQaFailureSmokeProperties() {
        java.util.List<String> missing = QaFailureBlockerRealSmokeTest.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.qa-failure.smoke.production-evidence"));
        assertTrue(missing.contains("rd.qa-failure.smoke.base-url"));
        assertTrue(missing.contains("rd.qa-failure.smoke.postgres-url"));
        assertTrue(missing.contains("rd.qa-failure.smoke.task-id"));
        assertTrue(missing.contains("rd.qa-failure.smoke.qa-report-artifact-uri"));
        assertTrue(missing.contains("rd.qa-failure.smoke.validation-log-artifact-uris"));
        assertTrue(missing.contains("rd.qa-failure.smoke.feishu-alert-message-id"));
    }
}
