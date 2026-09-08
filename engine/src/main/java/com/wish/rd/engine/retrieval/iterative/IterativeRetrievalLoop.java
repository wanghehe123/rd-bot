package com.wish.rd.engine.retrieval.iterative;

import com.wish.rd.engine.retrieval.iterative.model.RetrievalIterationState;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Drives plan→search→assess→rewrite rounds until {@link IterativeRetrievalStopGate} stops.
 */
public final class IterativeRetrievalLoop {

    private final IterativeRetrievalStopGate stopGate;

    public IterativeRetrievalLoop(IterativeRetrievalStopGate stopGate) {
        this.stopGate = Objects.requireNonNull(stopGate, "stopGate must not be null");
    }

    public IterativeRetrievalLoop() {
        this(new IterativeRetrievalStopGate());
    }

    /**
     * Runs rounds until a terminal stop reason. The assessor produces a post-round state snapshot;
     * the gate decides whether to continue.
     *
     * @param limits     budgets
     * @param startMillis loop wall-clock origin
     * @param assessRound given pre-assess state (round counter before withAssess), return post-assess state
     * @return terminal stop reason (never {@link RetrievalStopReason#CONTINUE})
     */
    public RetrievalStopReason run(
            RetrievalIterationLimits limits,
            long startMillis,
            Function<RetrievalIterationState, RetrievalIterationState> assessRound
    ) {
        Objects.requireNonNull(limits, "limits must not be null");
        Objects.requireNonNull(assessRound, "assessRound must not be null");

        RetrievalIterationState state = new RetrievalIterationState(
                0, false, false, Set.of(), Set.of(), 0L, 0L, 0L, 0
        );
        for (;;) {
            RetrievalIterationState assessed = Objects.requireNonNull(
                    assessRound.apply(state), "assessRound must return state"
            );
            // Ensure elapsed reflects wall clock if assessor left it unchanged/stale.
            if (assessed.elapsedMillis() < state.elapsedMillis()) {
                assessed = new RetrievalIterationState(
                        assessed.round(),
                        assessed.gateSatisfied(),
                        assessed.needsClarification(),
                        assessed.newlySelectedIds(),
                        assessed.previouslySelectedIds(),
                        assessed.tokensUsedThisRound(),
                        assessed.cumulativeTokens(),
                        Math.max(0L, System.currentTimeMillis() - startMillis),
                        assessed.consecutiveNoGainRounds()
                );
            }
            RetrievalStopReason decision = stopGate.decide(assessed, limits);
            if (decision != RetrievalStopReason.CONTINUE) {
                return decision;
            }
            state = assessed;
        }
    }
}
