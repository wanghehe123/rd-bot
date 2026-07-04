package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Writes a local readiness snapshot without calling external systems.
 */
@EnabledIfSystemProperty(named = "rd.integration.readiness.enabled", matches = "true")
class ProductionAcceptanceReadinessSnapshotTest {

    @Test
    void writesCurrentProductionAcceptanceReadinessSnapshot() throws Exception {
        Path evidenceRoot = MultiAgentProductionAcceptanceReport.resolveReportRoot(
                "qa-runs/multi-agent-production-acceptance"
        );
        ProductionAcceptanceReadinessReport report = new ProductionAcceptanceReadinessReport(
                evidenceRoot,
                Clock.systemUTC()
        );

        Path reportPath = report.write(evidenceRoot, System.getenv());

        System.out.println("[readiness] report=" + reportPath.toAbsolutePath());
    }
}
