package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityMetricsRealSmokePreconditionsTest {

    @Test
    void shouldListMissingRequiredObservabilityMetricsSmokeProperties() {
        java.util.List<String> missing = ObservabilityMetricsRealSmokeTest.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.observability-metrics.smoke.production-evidence"));
        assertTrue(missing.contains("rd.observability-metrics.smoke.base-url"));
        assertTrue(missing.contains("rd.observability-metrics.smoke.postgres-url"));
        assertTrue(missing.contains("rd.observability-metrics.smoke.task-id"));
        assertTrue(missing.contains("rd.observability-metrics.smoke.github-pr-remote-evidence-json"));
        assertTrue(missing.contains("rd.observability-metrics.smoke.secret-scan-needles"));
    }
}
