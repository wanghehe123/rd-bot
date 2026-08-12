package com.wish.rd.engine.oracle;

import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionOutcome;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionRunReport;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HostAssertionOracle fail-closed aggregation for WP-3 runner SPI.
 */
class HostAssertionOracleTest {

    @TempDir
    Path workspace;

    @Test
    void shouldFailClosedWhenRunnerMissingForAssertionType() {
        AssertionSpecBundle bundle = AssertionSpecBundle.freeze(List.of(httpStatus("a1", "200")));
        HostAssertionOracle oracle = new HostAssertionOracle(Map.of());

        AssertionRunReport report = oracle.evaluate(bundle, AssertionEvaluationContext.of(workspace));

        assertFalse(report.passed());
        assertEquals(AssertionOutcome.UNSUPPORTED, report.results().getFirst().outcome());
        assertTrue(report.failureSummary().contains("UNSUPPORTED"));
    }

    @Test
    void shouldPassWhenAllRegisteredRunnersPass() {
        AssertionSpecBundle bundle = AssertionSpecBundle.freeze(List.of(httpStatus("a1", "200")));
        HostAssertionOracle oracle = new HostAssertionOracle(Map.of(
                AssertionType.HTTP_STATUS,
                (spec, context) -> AssertionResult.passed(
                        spec.id(), spec.assertionType(), "status matches", List.of("qa-evidence/network/"))
        ));

        AssertionRunReport report = oracle.evaluate(bundle, AssertionEvaluationContext.of(workspace));

        assertTrue(report.passed());
        assertEquals(bundle.contentHash(), report.bundleContentHash());
        assertEquals(AssertionOutcome.PASSED, report.results().getFirst().outcome());
    }

    @Test
    void shouldFailWhenRunnerReportsBusinessMismatchEvenIfExitWouldBeZero() {
        AssertionSpecBundle bundle = AssertionSpecBundle.freeze(List.of(httpStatus("a1", "200")));
        HostAssertionOracle oracle = new HostAssertionOracle(Map.of(
                AssertionType.HTTP_STATUS,
                (spec, context) -> AssertionResult.failed(
                        spec.id(),
                        spec.assertionType(),
                        "expected 200 but was 500",
                        List.of("qa-evidence/network/")
                )
        ));

        AssertionRunReport report = oracle.evaluate(bundle, AssertionEvaluationContext.of(workspace));

        assertFalse(report.passed());
        assertEquals(AssertionOutcome.FAILED, report.results().getFirst().outcome());
        assertTrue(report.failureSummary().contains("expected 200 but was 500"));
    }

    @Test
    void shouldRejectEmptyBundle() {
        AssertionSpecBundle bundle = AssertionSpecBundle.freeze(List.of());
        HostAssertionOracle oracle = new HostAssertionOracle(Map.of());

        AssertionRunReport report = oracle.evaluate(bundle, AssertionEvaluationContext.of(workspace));

        assertFalse(report.passed());
        assertTrue(report.failureSummary().contains("no specs"));
    }

    private static AssertionSpec httpStatus(String id, String expected) {
        return new AssertionSpec(
                id,
                "criteria-" + id,
                List.of(),
                "",
                "GET /health",
                AssertionType.HTTP_STATUS,
                "/health",
                "eq",
                expected,
                "",
                List.of("qa-evidence/network/"),
                5_000L,
                ""
        );
    }
}
