package com.wish.rd.engine.oracle.model;

import java.util.List;

/**
 * Aggregated Host Oracle report for one frozen assertion bundle.
 *
 * @param bundleContentHash verified bundle hash
 * @param results           per-assertion results
 * @param passed            overall pass (all PASSED)
 * @param failureSummary    first failure/error/unsupported summary
 */
public record AssertionRunReport(
        String bundleContentHash,
        List<AssertionResult> results,
        boolean passed,
        String failureSummary
) {

    public AssertionRunReport {
        bundleContentHash = bundleContentHash == null ? "" : bundleContentHash.strip();
        results = results == null ? List.of() : List.copyOf(results);
        failureSummary = failureSummary == null ? "" : failureSummary.strip();
    }
}
