package com.wish.rd.engine.evaluation.impl;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCodingBenchmarkTrialStoreTest {

    @Test
    void shouldClaimOneQueuedTrialAndRecordATransition() {
        InMemoryCodingBenchmarkTrialStore store = new InMemoryCodingBenchmarkTrialStore();
        store.createAll("100", List.of(
                queued("trial-1", "case-01", CodingBenchmarkArm.A),
                queued("trial-2", "case-01", CodingBenchmarkArm.B)
        ));

        Optional<CodingBenchmarkTrial> claimed = store.claimNext("100", "worker-a", 10L, 1_000L);
        assertTrue(claimed.isPresent());
        assertEquals(CodingBenchmarkTrialStatus.PREPARING, claimed.orElseThrow().status());
        assertEquals(1L, claimed.orElseThrow().version());

        CodingBenchmarkTrial running = store.transition(
                claimed.orElseThrow().trialId(),
                CodingBenchmarkTrialStatus.PREPARING,
                claimed.orElseThrow().version(),
                CodingBenchmarkTrialStatus.RUNNING_AGENTS,
                CodingBenchmarkVerdict.PENDING,
                "",
                "",
                20L
        );
        assertEquals(CodingBenchmarkTrialStatus.RUNNING_AGENTS, running.status());
        assertEquals(3, store.listEvents(running.trialId()).size());
    }

    private static CodingBenchmarkTrial queued(String trialId, String caseId, CodingBenchmarkArm arm) {
        return new CodingBenchmarkTrial(
                trialId, "100", caseId, arm, 0, CodingBenchmarkTrialStatus.QUEUED,
                CodingBenchmarkVerdict.PENDING, 1, 0L, "", 0L, "", "", 1L, 1L);
    }
}
