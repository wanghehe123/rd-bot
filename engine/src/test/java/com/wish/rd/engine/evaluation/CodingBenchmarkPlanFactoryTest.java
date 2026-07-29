package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkCase;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkPlan;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkSlice;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodingBenchmarkPlanFactoryTest {

    @Test
    void shouldCreateEightyPrimaryTrialsAndEightSentinelRepeats() {
        AtomicInteger nextTrial = new AtomicInteger();
        CodingBenchmarkPlanFactory factory = new CodingBenchmarkPlanFactory(
                () -> "trial-" + nextTrial.incrementAndGet(), () -> 100L
        );

        CodingBenchmarkPlan plan = factory.create("campaign-1", twentyCases(), sentinelCaseIds());

        assertEquals(88, plan.trials().size());
        assertEquals(80, plan.trials().stream().filter(trial -> trial.replicateNo() == 0).count());
        assertEquals(8, plan.trials().stream().filter(trial -> trial.replicateNo() == 1).count());
        assertEquals(8, plan.trials().stream()
                .filter(trial -> trial.replicateNo() == 1)
                .filter(trial -> trial.arm() == CodingBenchmarkArm.A || trial.arm() == CodingBenchmarkArm.D)
                .count());
    }

    @Test
    void shouldBalancePrimaryCaseArmSequences() {
        AtomicInteger nextTrial = new AtomicInteger();
        CodingBenchmarkPlanFactory factory = new CodingBenchmarkPlanFactory(
                () -> "trial-" + nextTrial.incrementAndGet(), () -> 100L
        );

        CodingBenchmarkPlan plan = factory.create("campaign-1", twentyCases(), sentinelCaseIds());

        assertEquals(5, plan.sequenceCounts().get("ABCD"));
        assertEquals(5, plan.sequenceCounts().get("BCDA"));
        assertEquals(5, plan.sequenceCounts().get("CDAB"));
        assertEquals(5, plan.sequenceCounts().get("DABC"));
        assertEquals("ABCD", plan.sequenceForCase("case-01"));
        assertEquals("DABC", plan.sequenceForCase("case-20"));
    }

    @Test
    void shouldRejectAnInvalidPreRegisteredMatrix() {
        CodingBenchmarkPlanFactory factory = new CodingBenchmarkPlanFactory(() -> "trial-1", () -> 100L);

        assertThrows(IllegalArgumentException.class,
                () -> factory.create("campaign-1", twentyCases().subList(0, 19), sentinelCaseIds()));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create("campaign-1", twentyCases(), Set.of("case-01", "case-02", "case-03", "case-21")));
    }

    private static List<CodingBenchmarkCase> twentyCases() {
        return List.of(
                benchmarkCase("case-01", CodingBenchmarkSlice.FRESH_PRIMARY),
                benchmarkCase("case-02", CodingBenchmarkSlice.FRESH_PRIMARY),
                benchmarkCase("case-03", CodingBenchmarkSlice.FRESH_PRIMARY),
                benchmarkCase("case-04", CodingBenchmarkSlice.FRESH_PRIMARY),
                benchmarkCase("case-05", CodingBenchmarkSlice.FRESH_PRIMARY),
                benchmarkCase("case-06", CodingBenchmarkSlice.FRESH_PRIMARY),
                benchmarkCase("case-07", CodingBenchmarkSlice.FRESH_PRIMARY),
                benchmarkCase("case-08", CodingBenchmarkSlice.FRESH_PRIMARY),
                benchmarkCase("case-09", CodingBenchmarkSlice.FRESH_PRIMARY),
                benchmarkCase("case-10", CodingBenchmarkSlice.FRESH_PRIMARY),
                benchmarkCase("case-11", CodingBenchmarkSlice.PUBLIC_ANCHOR),
                benchmarkCase("case-12", CodingBenchmarkSlice.PUBLIC_ANCHOR),
                benchmarkCase("case-13", CodingBenchmarkSlice.PUBLIC_ANCHOR),
                benchmarkCase("case-14", CodingBenchmarkSlice.PUBLIC_ANCHOR),
                benchmarkCase("case-15", CodingBenchmarkSlice.PUBLIC_ANCHOR),
                benchmarkCase("case-16", CodingBenchmarkSlice.PUBLIC_ANCHOR),
                benchmarkCase("case-17", CodingBenchmarkSlice.PUBLIC_ANCHOR),
                benchmarkCase("case-18", CodingBenchmarkSlice.PUBLIC_ANCHOR),
                benchmarkCase("case-19", CodingBenchmarkSlice.PUBLIC_ANCHOR),
                benchmarkCase("case-20", CodingBenchmarkSlice.PUBLIC_ANCHOR)
        );
    }

    private static CodingBenchmarkCase benchmarkCase(String caseId, CodingBenchmarkSlice slice) {
        return new CodingBenchmarkCase(caseId, slice, "repository-" + caseId, "JAVA", "HARD");
    }

    private static Set<String> sentinelCaseIds() {
        return Set.of("case-01", "case-06", "case-11", "case-16");
    }
}
