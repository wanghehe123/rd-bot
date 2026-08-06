package com.wish.rd.engine.retrieval.iterative;

/**
 * Bounds for plan→search→assess→rewrite iterations.
 *
 * @param maxRounds              hard round cap (inclusive)
 * @param maxTokenBudget         cumulative token budget across rounds
 * @param maxElapsedMillis       wall-clock budget
 * @param noGainRoundsToStop     consecutive rounds without new evidence before stop
 */
public record RetrievalIterationLimits(
        int maxRounds,
        long maxTokenBudget,
        long maxElapsedMillis,
        int noGainRoundsToStop
) {

    public RetrievalIterationLimits {
        maxRounds = Math.max(1, maxRounds);
        maxTokenBudget = Math.max(1L, maxTokenBudget);
        maxElapsedMillis = Math.max(1L, maxElapsedMillis);
        noGainRoundsToStop = Math.max(1, noGainRoundsToStop);
    }

    public static RetrievalIterationLimits defaults() {
        return new RetrievalIterationLimits(3, 8_000L, 30_000L, 2);
    }
}
