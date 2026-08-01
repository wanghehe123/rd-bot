package com.wish.rd.engine.evaluation;

/**
 * Phase callbacks handed to {@link CodingBenchmarkExecutionPort} so the caller can gate the oracle
 * phase of one trial; the dispatcher uses it to keep concurrent oracle containers within the
 * convergent scheduling limit instead of discovering the phase only after {@code execute} returns.
 */
@FunctionalInterface
public interface CodingBenchmarkExecutionHooks {

    /**
     * Invoked after agent work has succeeded and before oracle work starts. Implementations may block
     * until an oracle slot is free, so executors must not hold container resources while calling it.
     */
    void beforeOracle();

    /** @return hooks that observe nothing, for callers that do not schedule oracle capacity. */
    static CodingBenchmarkExecutionHooks noop() {
        return () -> {
        };
    }
}
