package com.wish.rd.bootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;

/**
 * One-shot production acceptance gate: refresh the evidence ledger, then run the final gate.
 */
final class ProductionAcceptanceFastGate {

    private final Clock clock;

    ProductionAcceptanceFastGate() {
        this(Clock.systemUTC());
    }

    ProductionAcceptanceFastGate(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    Path writeFreshLedgerAndAssertPassed(Path evidenceRoot) throws IOException {
        Path safeEvidenceRoot = evidenceRoot == null
                ? MultiAgentProductionAcceptanceReport.resolveReportRoot("qa-runs/multi-agent-production-acceptance")
                : evidenceRoot.toAbsolutePath().normalize();
        ProductionAcceptanceEvidenceLedger ledger =
                new ProductionAcceptanceEvidenceLedger(safeEvidenceRoot, clock);
        ledger.write(safeEvidenceRoot);
        return new ProductionAcceptanceFinalGate().assertLatestLedgerPassed(safeEvidenceRoot);
    }
}
