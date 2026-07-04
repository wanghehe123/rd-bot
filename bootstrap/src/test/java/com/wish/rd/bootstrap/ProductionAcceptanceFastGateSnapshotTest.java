package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

@EnabledIfSystemProperty(named = "rd.integration.fast-final-acceptance.enabled", matches = "true")
class ProductionAcceptanceFastGateSnapshotTest {

    @Test
    void writesFreshLedgerAndRequiresFullProductionAcceptance() throws Exception {
        Path evidenceRoot = MultiAgentProductionAcceptanceReport.resolveReportRoot(System.getProperty(
                "rd.multi-agent.smoke.report-dir",
                "qa-runs/multi-agent-production-acceptance"
        ));
        Path accepted = new ProductionAcceptanceFastGate().writeFreshLedgerAndAssertPassed(evidenceRoot);
        System.out.println("[fast-acceptance-gate] accepted=" + accepted.toAbsolutePath());
    }
}
