package com.wish.rd.bootstrap.evaluation.impl;

/**
 * Callback invoked when a coding benchmark campaign has no queued trials and no active workers.
 *
 * <p>Task 5 wires this to advance the parent {@link com.wish.rd.engine.evaluation.model.EvaluationRun}
 * through SCORING and REPORTING; the dispatcher only signals drain completion.
 */
@FunctionalInterface
public interface CodingBenchmarkCampaignCompletion {

    /** Called once when every trial for {@code runId} has reached a terminal state. */
    void onTrialsDrained(String runId);
}
