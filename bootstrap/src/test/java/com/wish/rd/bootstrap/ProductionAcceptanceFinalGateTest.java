package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionAcceptanceFinalGateTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPassOnlyWhenLatestLedgerHasAllFifteenAcceptancePointsPassed() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("production-acceptance-evidence-ledger-20260704-100000.json"),
                passedLedgerJson()
        );
        ProductionAcceptanceFinalGate gate = new ProductionAcceptanceFinalGate();

        Path accepted = gate.assertLatestLedgerPassed(evidenceRoot);

        assertEquals(
                evidenceRoot.resolve("production-acceptance-evidence-ledger-20260704-100000.json"),
                accepted
        );
    }

    @Test
    void shouldFailWithNextActionsWhenLatestLedgerIsNotPassed() throws Exception {
        Path evidenceRoot = tempDir.resolve("evidence");
        Files.createDirectories(evidenceRoot);
        Files.writeString(
                evidenceRoot.resolve("production-acceptance-evidence-ledger-20260704-100000.json"),
                """
                        {
                          "conclusion": "FAILED_PRODUCTION_ACCEPTANCE_LEDGER",
                          "totalAcceptancePointCount": 15,
                          "passedCount": 3,
                          "failedCount": 2,
                          "notRunCount": 10,
                          "nextActions": [
                            {
                              "item": "long-cat",
                              "reason": "PROVIDER_AUTHENTICATION_FAILED",
                              "action": "long-cat：重新注入有效 provider secret，并重跑 ProviderPreflightRealSmokeTest。",
                              "evidence": "provider-preflight-production-acceptance-20260704-080657.json"
                            },
                            {
                              "item": "minimax",
                              "reason": "PROVIDER_QUOTA_OR_RATE_LIMIT",
                              "action": "minimax：恢复 provider quota 或 Token Plan 额度，并重跑 ProviderPreflightRealSmokeTest。",
                              "evidence": "provider-preflight-production-acceptance-20260704-080657.json"
                            }
                          ]
                        }
                        """
        );
        ProductionAcceptanceFinalGate gate = new ProductionAcceptanceFinalGate();

        AssertionError failure = assertThrows(
                AssertionError.class,
                () -> gate.assertLatestLedgerPassed(evidenceRoot)
        );

        assertTrue(failure.getMessage().contains("FAILED_PRODUCTION_ACCEPTANCE_LEDGER"));
        assertTrue(failure.getMessage().contains("passed=3"));
        assertTrue(failure.getMessage().contains("failed=2"));
        assertTrue(failure.getMessage().contains("notRun=10"));
        assertTrue(failure.getMessage().contains("PROVIDER_AUTHENTICATION_FAILED"));
        assertTrue(failure.getMessage().contains("PROVIDER_QUOTA_OR_RATE_LIMIT"));
        assertTrue(failure.getMessage().contains("provider-preflight-production-acceptance-20260704-080657.json"));
    }

    @Test
    void shouldFailWhenNoLedgerExists() {
        ProductionAcceptanceFinalGate gate = new ProductionAcceptanceFinalGate();

        AssertionError failure = assertThrows(
                AssertionError.class,
                () -> gate.assertLatestLedgerPassed(tempDir.resolve("empty"))
        );

        assertTrue(failure.getMessage().contains("production-acceptance-evidence-ledger-*.json"));
    }

    private static String passedLedgerJson() {
        return """
                {
                  "conclusion": "PASSED_PRODUCTION_ACCEPTANCE_LEDGER",
                  "totalAcceptancePointCount": 15,
                  "passedCount": 15,
                  "failedCount": 0,
                  "notRunCount": 0,
                  "rows": [
                    {"number": 1, "status": "PASSED"},
                    {"number": 2, "status": "PASSED"},
                    {"number": 3, "status": "PASSED"},
                    {"number": 4, "status": "PASSED"},
                    {"number": 5, "status": "PASSED"},
                    {"number": 6, "status": "PASSED"},
                    {"number": 7, "status": "PASSED"},
                    {"number": 8, "status": "PASSED"},
                    {"number": 9, "status": "PASSED"},
                    {"number": 10, "status": "PASSED"},
                    {"number": 11, "status": "PASSED"},
                    {"number": 12, "status": "PASSED"},
                    {"number": 13, "status": "PASSED"},
                    {"number": 14, "status": "PASSED"},
                    {"number": 15, "status": "PASSED"}
                  ],
                  "nextActions": []
                }
                """;
    }
}
