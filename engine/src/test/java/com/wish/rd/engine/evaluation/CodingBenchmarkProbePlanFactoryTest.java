package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkCase;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkSlice;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class CodingBenchmarkProbePlanFactoryTest {

    private final CodingBenchmarkProbePlanFactory factory = new CodingBenchmarkProbePlanFactory(() -> "T", () -> 1_000_000_000L);

    @Test
    void oneCase_producesFourTrials() {
        List<CodingBenchmarkTrial> trials = factory.create("c1", List.of("rd-bot--1d1c7c804a"));
        assertEquals(4, trials.size());
        for (CodingBenchmarkArm arm : CodingBenchmarkArm.values()) {
            assertTrue(trials.stream().anyMatch(t -> t.arm() == arm && t.caseId().equals("rd-bot--1d1c7c804a")));
        }
        assertTrue(trials.stream().allMatch(t -> t.replicateNo() == 0));
        assertTrue(trials.stream().allMatch(t -> t.status() == com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus.QUEUED));
        assertTrue(trials.stream().allMatch(t -> t.verdict() == com.wish.rd.engine.evaluation.model.CodingBenchmarkVerdict.PENDING));
    }

    @Test
    void twoCases_producesEightTrials() {
        List<CodingBenchmarkTrial> trials = factory.create("c1",
                List.of("rd-bot--1d1c7c804a", "mockito__mockito-3133"));
        assertEquals(8, trials.size());
        // Sorted by caseId
        assertEquals("mockito__mockito-3133", trials.get(0).caseId());
        assertEquals("rd-bot--1d1c7c804a", trials.get(4).caseId());
    }

    @Test
    void trialIds_areUnique() {
        List<CodingBenchmarkTrial> trials = factory.create("c1",
                List.of("rd-bot--1d1c7c804a", "mockito__mockito-3133"));
        long unique = trials.stream().map(CodingBenchmarkTrial::trialId).distinct().count();
        assertEquals(8, unique);
    }

    @Test
    void rejectsEmptyCaseList() {
        assertThrows(IllegalArgumentException.class, () -> factory.create("c1", List.of()));
    }

    @Test
    void fiveCases_producesTwentyTrials() {
        List<CodingBenchmarkTrial> trials = factory.create("c1",
                List.of("a", "b", "c", "d", "e"));
        assertEquals(20, trials.size());
    }

    @Test
    void rejectsMoreThanFiveCases() {
        assertThrows(IllegalArgumentException.class, () -> factory.create("c1",
                List.of("a", "b", "c", "d", "e", "f")));
    }

    @Test
    void rejectsDuplicateCases() {
        assertThrows(IllegalArgumentException.class, () -> factory.create("c1",
                List.of("rd-bot--1d1c7c804a", "rd-bot--1d1c7c804a")));
    }

    @Test
    void resultIsImmutable() {
        List<CodingBenchmarkTrial> trials = factory.create("c1", List.of("rd-bot--1d1c7c804a"));
        assertThrows(UnsupportedOperationException.class, () -> trials.add(trials.get(0)));
    }
}
