package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

/**
 * Fails unless the latest local evidence ledger proves the full production acceptance is complete.
 */
@EnabledIfSystemProperty(named = "rd.integration.final-acceptance-gate.enabled", matches = "true")
class ProductionAcceptanceFinalGateSnapshotTest {

    @Test
    void latestEvidenceLedgerMustProveFullProductionAcceptance() throws Exception {
        Path evidenceRoot = MultiAgentProductionAcceptanceReport.resolveReportRoot(
                "qa-runs/multi-agent-production-acceptance"
        );
        Path accepted = new ProductionAcceptanceFinalGate().assertLatestLedgerPassed(evidenceRoot);

        System.out.println("[final-acceptance-gate] accepted=" + accepted.toAbsolutePath());
    }
}
