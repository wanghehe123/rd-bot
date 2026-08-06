package com.wish.rd.engine.retrieval.iterative;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IterativeRetrievalStopGateTest {

    private final IterativeRetrievalStopGate gate = new IterativeRetrievalStopGate();
    private final RetrievalIterationLimits limits = new RetrievalIterationLimits(3, 1_000L, 10_000L, 2);

    @Test
    void shouldStopWhenGateSatisfied() {
        RetrievalIterationState state = base().withAssess(true, false, Set.of("e1"), 10L, 100L);
        assertEquals(RetrievalStopReason.GATE_SATISFIED, gate.decide(state, limits));
    }

    @Test
    void shouldStopAfterConsecutiveNoGainRounds() {
        RetrievalIterationState noGain1 = new RetrievalIterationState(
                0, false, false, Set.of(), Set.of("e1"), 0L, 0L, 0L, 0)
                .withAssess(false, false, Set.of("e1"), 10L, 100L);
        RetrievalIterationState noGain2 = noGain1.withAssess(false, false, Set.of("e1"), 10L, 200L);

        assertEquals(1, noGain1.consecutiveNoGainRounds());
        assertEquals(2, noGain2.consecutiveNoGainRounds());
        assertEquals(0, noGain1.informationGain());
        assertEquals(RetrievalStopReason.CONTINUE, gate.decide(noGain1, limits));
        assertEquals(RetrievalStopReason.NO_NEW_EVIDENCE, gate.decide(noGain2, limits));
    }

    @Test
    void shouldStopOnMaxRounds() {
        RetrievalIterationState state = new RetrievalIterationState(
                3, false, false, Set.of("n1"), Set.of(), 10L, 30L, 100L, 0);
        assertEquals(RetrievalStopReason.MAX_ROUNDS, gate.decide(state, limits));
    }

    @Test
    void shouldStopOnTokenBudget() {
        RetrievalIterationState state = new RetrievalIterationState(
                1, false, false, Set.of("n1"), Set.of(), 50L, 1_000L, 100L, 0);
        assertEquals(RetrievalStopReason.TOKEN_BUDGET, gate.decide(state, limits));
    }

    @Test
    void shouldStopOnClarification() {
        RetrievalIterationState state = base().withAssess(false, true, Set.of(), 10L, 50L);
        assertEquals(RetrievalStopReason.NEEDS_CLARIFICATION, gate.decide(state, limits));
    }

    private static RetrievalIterationState base() {
        return new RetrievalIterationState(0, false, false, Set.of(), Set.of(), 0L, 0L, 0L, 0);
    }
}
