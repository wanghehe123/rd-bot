package com.wish.rd.bootstrap.openviking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowRetrievalEvaluationMetricsTest {

    @Test
    void recallAtKIsTheFractionOfExpectedDocumentsAppearingInThePrefix() {
        List<String> ranked = List.of("a", "b", "c", "d");
        Set<String> expected = Set.of("a", "c", "z");
        assertEquals(1.0d / 3.0d, ShadowRetrievalEvaluationMetrics.recallAtK(ranked, expected, 1), 1e-9);
        assertEquals(2.0d / 3.0d, ShadowRetrievalEvaluationMetrics.recallAtK(ranked, expected, 3), 1e-9);
        assertEquals(2.0d / 3.0d, ShadowRetrievalEvaluationMetrics.recallAtK(ranked, expected, 8), 1e-9);
    }

    @Test
    void recallSkipsUnanswerableQuestionsRatherThanInflatingTheAverage() {
        assertTrue(Double.isNaN(ShadowRetrievalEvaluationMetrics.recallAtK(List.of("a"), Set.of(), 8)));
    }

    @Test
    void citationValidityIsTheFractionOfReturnedDocumentsThatWereExpected() {
        assertEquals(0.5d, ShadowRetrievalEvaluationMetrics.citationValidity(
                List.of("a", "x"), Set.of("a", "b")), 1e-9);
        assertEquals(1.0d, ShadowRetrievalEvaluationMetrics.citationValidity(List.of(), Set.of()), 1e-9);
        assertEquals(0.0d, ShadowRetrievalEvaluationMetrics.citationValidity(List.of("x"), Set.of()), 1e-9);
        assertTrue(Double.isNaN(ShadowRetrievalEvaluationMetrics.citationValidity(List.of(), Set.of("a"))));
    }

    @Test
    void unanswerableQuestionsAreFalsePositivesOnlyWhenSomethingIsCited() {
        assertFalse(ShadowRetrievalEvaluationMetrics.falsePositiveCitation(List.of(), Set.of()));
        assertTrue(ShadowRetrievalEvaluationMetrics.falsePositiveCitation(List.of("x"), Set.of()));
        assertFalse(ShadowRetrievalEvaluationMetrics.falsePositiveCitation(List.of("a"), Set.of("a")));
    }

    @Test
    void percentileUsesTheSameNearestRankAsTheWp0Baseline() {
        List<Long> samples = List.of(10L, 20L, 30L, 40L, 50L);
        assertEquals(30L, ShadowRetrievalEvaluationMetrics.percentile(samples, 0.50d));
        assertEquals(50L, ShadowRetrievalEvaluationMetrics.percentile(samples, 0.95d));
    }
}
