package com.wish.rd.engine.retrieval.iterative;

import java.util.Objects;

/**
 * Deterministic stop gate for iterative retrieval.
 * Hard filters (tenant/project scope) remain outside this policy.
 */
public final class IterativeRetrievalStopGate {

    /**
     * Decides whether another rewrite/search round is allowed.
     *
     * @param state  post-assess state for the round just finished
     * @param limits iteration budgets
     * @return CONTINUE or a terminal stop reason
     */
    public RetrievalStopReason decide(RetrievalIterationState state, RetrievalIterationLimits limits) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(limits, "limits must not be null");

        if (state.needsClarification()) {
            return RetrievalStopReason.NEEDS_CLARIFICATION;
        }
        if (state.gateSatisfied()) {
            return RetrievalStopReason.GATE_SATISFIED;
        }
        if (state.round() >= limits.maxRounds()) {
            return RetrievalStopReason.MAX_ROUNDS;
        }
        if (state.cumulativeTokens() >= limits.maxTokenBudget()) {
            return RetrievalStopReason.TOKEN_BUDGET;
        }
        if (state.elapsedMillis() >= limits.maxElapsedMillis()) {
            return RetrievalStopReason.TIME_BUDGET;
        }
        if (state.consecutiveNoGainRounds() >= limits.noGainRoundsToStop()) {
            return RetrievalStopReason.NO_NEW_EVIDENCE;
        }
        return RetrievalStopReason.CONTINUE;
    }
}
