package com.wish.rd.engine.evaluation.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodingBenchmarkTrialTest {

    @Test
    void shouldCreateQueuedFirstAttempt() {
        CodingBenchmarkTrial trial = CodingBenchmarkTrial.queued(
                "trial-1", "campaign-1", "case-a", CodingBenchmarkArm.A, 0, 100L
        );

        assertEquals(CodingBenchmarkTrialStatus.QUEUED, trial.status());
        assertEquals(CodingBenchmarkVerdict.PENDING, trial.verdict());
        assertEquals(0, trial.replicateNo());
        assertEquals(1, trial.attemptNo());
        assertEquals(0L, trial.version());
        assertEquals("", trial.leaseOwner());
    }

    @Test
    void shouldRejectUnsupportedReplicateNumber() {
        assertThrows(IllegalArgumentException.class, () -> CodingBenchmarkTrial.queued(
                "trial-1", "campaign-1", "case-a", CodingBenchmarkArm.A, 2, 100L
        ));
    }

    @Test
    void shouldAllowOnlyTerminalTrialStatusesToBeTerminal() {
        assertEquals(false, CodingBenchmarkTrialStatus.RUNNING_AGENTS.isTerminal());
        assertEquals(true, CodingBenchmarkTrialStatus.SUCCEEDED.isTerminal());
        assertEquals(true, CodingBenchmarkTrialStatus.FAILED.isTerminal());
        assertEquals(true, CodingBenchmarkTrialStatus.CANCELLED.isTerminal());
    }
}
