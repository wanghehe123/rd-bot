package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Writes a local evidence ledger without calling external systems.
 */
@EnabledIfSystemProperty(named = "rd.integration.evidence-ledger.enabled", matches = "true")
class ProductionAcceptanceEvidenceLedgerSnapshotTest {

    @Test
    void writesCurrentProductionAcceptanceEvidenceLedger() throws Exception {
        Path evidenceRoot = MultiAgentProductionAcceptanceReport.resolveReportRoot(
                "qa-runs/multi-agent-production-acceptance"
        );
        ProductionAcceptanceEvidenceLedger ledger = new ProductionAcceptanceEvidenceLedger(
                evidenceRoot,
                Clock.systemUTC()
        );

        Path reportPath = ledger.write(evidenceRoot);

        System.out.println("[evidence-ledger] report=" + reportPath.toAbsolutePath());
    }
}
