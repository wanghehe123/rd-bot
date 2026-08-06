package com.wish.rd.engine.retrieval.iterative;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IterativeRetrievalLoopTest {

    @Test
    void shouldStopWhenGateSatisfiedOnFirstRound() {
        IterativeRetrievalLoop loop = new IterativeRetrievalLoop();
        RetrievalIterationLimits limits = new RetrievalIterationLimits(3, 1_000L, 10_000L, 2);
        AtomicInteger rounds = new AtomicInteger();

        RetrievalStopReason stop = loop.run(limits, System.currentTimeMillis(), before -> {
            rounds.incrementAndGet();
            return before.withAssess(true, false, Set.of("e1"), 10L, 50L);
        });

        assertEquals(RetrievalStopReason.GATE_SATISFIED, stop);
        assertEquals(1, rounds.get());
    }

    @Test
    void shouldBoundRoundsWhenEvidenceNeverSatisfiesGate() {
        IterativeRetrievalLoop loop = new IterativeRetrievalLoop();
        RetrievalIterationLimits limits = new RetrievalIterationLimits(3, 10_000L, 60_000L, 5);
        AtomicInteger rounds = new AtomicInteger();

        RetrievalStopReason stop = loop.run(limits, System.currentTimeMillis(), before -> {
            int n = rounds.incrementAndGet();
            return before.withAssess(false, false, Set.of("e" + n), 10L, n * 10L);
        });

        assertEquals(RetrievalStopReason.MAX_ROUNDS, stop);
        assertEquals(3, rounds.get());
    }

    @Test
    void shouldStopOnNoNewEvidence() {
        IterativeRetrievalLoop loop = new IterativeRetrievalLoop();
        RetrievalIterationLimits limits = new RetrievalIterationLimits(5, 10_000L, 60_000L, 2);
        AtomicInteger rounds = new AtomicInteger();

        RetrievalStopReason stop = loop.run(limits, System.currentTimeMillis(), before -> {
            rounds.incrementAndGet();
            // same id every round → zero information gain
            return before.withAssess(false, false, Set.of("stale"), 5L, rounds.get() * 20L);
        });

        assertEquals(RetrievalStopReason.NO_NEW_EVIDENCE, stop);
        assertTrue(rounds.get() >= 2);
    }
}
