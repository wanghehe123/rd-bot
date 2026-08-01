package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkCase;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkSlice;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CodingBenchmarkCaseSelectorTest {

    private final CodingBenchmarkCaseSelector selector = new CodingBenchmarkCaseSelector();

    @Test
    void selectsOneFreshPrimaryAndOnePublicAnchor() {
        List<CodingBenchmarkCase> allCases = List.of(
                fresh("rd-bot--1d1c7c804a", "JAVA", "EASY"),
                fresh("rd-bot--94c6b17565", "JAVA", "MEDIUM"),
                anchor("mockito__mockito-3133", "JAVA", "MEDIUM"),
                anchor("darkreader__darkreader-6747", "TSJS", "MEDIUM")
        );
        List<CodingBenchmarkCase> selected = selector.selectProbeCases(allCases);
        assertEquals(2, selected.size());
        assertEquals(1, selected.stream().filter(c -> c.slice() == CodingBenchmarkSlice.FRESH_PRIMARY).count());
        assertEquals(1, selected.stream().filter(c -> c.slice() == CodingBenchmarkSlice.PUBLIC_ANCHOR).count());
    }

    @Test
    void prefersJavaOverTsjs() {
        List<CodingBenchmarkCase> allCases = List.of(
                fresh("rd-bot--tsjs", "TSJS", "MEDIUM"),
                fresh("rd-bot--java", "JAVA", "MEDIUM"),
                anchor("anchor--tsjs", "TSJS", "MEDIUM"),
                anchor("anchor--java", "JAVA", "MEDIUM")
        );
        List<CodingBenchmarkCase> selected = selector.selectProbeCases(allCases);
        assertTrue(selected.stream().anyMatch(c -> c.caseId().equals("rd-bot--java")));
        assertTrue(selected.stream().anyMatch(c -> c.caseId().equals("anchor--java")));
    }

    @Test
    void prefersMediumDifficulty() {
        List<CodingBenchmarkCase> allCases = List.of(
                fresh("rd-bot--easy", "JAVA", "EASY"),
                fresh("rd-bot--medium", "JAVA", "MEDIUM"),
                fresh("rd-bot--hard", "JAVA", "HARD"),
                anchor("anchor--easy", "JAVA", "EASY"),
                anchor("anchor--medium", "JAVA", "MEDIUM"),
                anchor("anchor--hard", "JAVA", "HARD")
        );
        List<CodingBenchmarkCase> selected = selector.selectProbeCases(allCases);
        assertTrue(selected.stream().anyMatch(c -> c.caseId().equals("rd-bot--medium")));
        assertTrue(selected.stream().anyMatch(c -> c.caseId().equals("anchor--medium")));
    }

    @Test
    void selectCostLimitedCases_alternatesFreshAndPublicUpToLimit() {
        List<CodingBenchmarkCase> allCases = List.of(
                fresh("rd-bot--a", "JAVA", "MEDIUM"),
                fresh("rd-bot--b", "JAVA", "EASY"),
                fresh("rd-bot--c", "JAVA", "HARD"),
                anchor("anchor--a", "JAVA", "MEDIUM"),
                anchor("anchor--b", "JAVA", "EASY"),
                anchor("anchor--c", "TSJS", "MEDIUM")
        );
        List<CodingBenchmarkCase> selected = selector.selectCostLimitedCases(allCases, 5);
        assertEquals(5, selected.size());
        assertEquals(3, selected.stream().filter(c -> c.slice() == CodingBenchmarkSlice.FRESH_PRIMARY).count());
        assertEquals(2, selected.stream().filter(c -> c.slice() == CodingBenchmarkSlice.PUBLIC_ANCHOR).count());
        assertEquals("rd-bot--a", selected.get(0).caseId());
        assertEquals("anchor--a", selected.get(1).caseId());
    }

    @Test
    void returnsOneWhenOnlyFreshPrimaryExists() {
        List<CodingBenchmarkCase> allCases = List.of(
                fresh("rd-bot--1d1c7c804a", "JAVA", "MEDIUM")
        );
        List<CodingBenchmarkCase> selected = selector.selectProbeCases(allCases);
        assertEquals(1, selected.size());
        assertEquals("rd-bot--1d1c7c804a", selected.get(0).caseId());
    }

    @Test
    void returnsEmptyForEmptyInput() {
        assertTrue(selector.selectProbeCases(List.of()).isEmpty());
    }

    @Test
    void fromRawCases_parsesCorrectly() {
        List<Map<String, Object>> raw = List.of(
                Map.of("caseId", "rd-bot--1d1c7c804a", "slice", "FRESH_PRIMARY", "language", "JAVA", "difficulty", "EASY"),
                Map.of("caseId", "mockito__mockito-3133", "slice", "PUBLIC_ANCHOR", "language", "JAVA", "difficulty", "MEDIUM")
        );
        List<CodingBenchmarkCase> cases = CodingBenchmarkCaseSelector.fromRawCases(raw);
        // After sort by caseId: mockito (m) < rd-bot (r)
        assertEquals(2, cases.size());
        assertEquals(CodingBenchmarkSlice.PUBLIC_ANCHOR, cases.get(0).slice());
        assertEquals(CodingBenchmarkSlice.FRESH_PRIMARY, cases.get(1).slice());
    }

    private static CodingBenchmarkCase fresh(String caseId, String language, String difficulty) {
        return new CodingBenchmarkCase(caseId, CodingBenchmarkSlice.FRESH_PRIMARY, "rd-bot/repo", language, difficulty);
    }

    private static CodingBenchmarkCase anchor(String caseId, String language, String difficulty) {
        return new CodingBenchmarkCase(caseId, CodingBenchmarkSlice.PUBLIC_ANCHOR, "mockito/repo", language, difficulty);
    }
}
