package com.wish.rd.engine.retrieval.iterative.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Snapshot after one retrieval iteration assess step.
 *
 * @param round                 1-based round index just completed
 * @param gateSatisfied         evidence gate passed
 * @param needsClarification    assessor requests user clarification
 * @param newlySelectedIds      evidence ids newly selected this round
 * @param previouslySelectedIds cumulative selected ids before this round
 * @param tokensUsedThisRound   tokens consumed this round
 * @param cumulativeTokens      tokens used including this round
 * @param elapsedMillis         elapsed since loop start
 * @param consecutiveNoGainRounds consecutive rounds with zero information gain
 */
public record RetrievalIterationState(
        int round,
        boolean gateSatisfied,
        boolean needsClarification,
        Set<String> newlySelectedIds,
        Set<String> previouslySelectedIds,
        long tokensUsedThisRound,
        long cumulativeTokens,
        long elapsedMillis,
        int consecutiveNoGainRounds
) {

    public RetrievalIterationState {
        round = Math.max(0, round);
        newlySelectedIds = newlySelectedIds == null ? Set.of() : Set.copyOf(newlySelectedIds);
        previouslySelectedIds = previouslySelectedIds == null ? Set.of() : Set.copyOf(previouslySelectedIds);
        tokensUsedThisRound = Math.max(0L, tokensUsedThisRound);
        cumulativeTokens = Math.max(0L, cumulativeTokens);
        elapsedMillis = Math.max(0L, elapsedMillis);
        consecutiveNoGainRounds = Math.max(0, consecutiveNoGainRounds);
    }

    public int informationGain() {
        int gain = 0;
        for (String id : newlySelectedIds) {
            if (id != null && !id.isBlank() && !previouslySelectedIds.contains(id)) {
                gain++;
            }
        }
        return gain;
    }

    public RetrievalIterationState withAssess(
            boolean gateSatisfied,
            boolean needsClarification,
            Set<String> newlySelectedIds,
            long tokensUsedThisRound,
            long elapsedMillis
    ) {
        Set<String> incoming = newlySelectedIds == null ? Set.of() : newlySelectedIds;
        int gain = 0;
        LinkedHashSet<String> cumulative = new LinkedHashSet<>(previouslySelectedIds);
        for (String id : incoming) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (!previouslySelectedIds.contains(id)) {
                gain++;
            }
            cumulative.add(id);
        }
        int noGain = gain == 0 ? consecutiveNoGainRounds + 1 : 0;
        return new RetrievalIterationState(
                round + 1,
                gateSatisfied,
                needsClarification,
                Set.copyOf(incoming),
                Set.copyOf(cumulative),
                Math.max(0L, tokensUsedThisRound),
                cumulativeTokens + Math.max(0L, tokensUsedThisRound),
                elapsedMillis,
                noGain
        );
    }
}
