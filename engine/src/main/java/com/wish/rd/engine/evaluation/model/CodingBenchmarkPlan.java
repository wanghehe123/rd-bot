package com.wish.rd.engine.evaluation.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Frozen set of logical trials and balanced arm sequences created before campaign dispatch.
 */
public record CodingBenchmarkPlan(
        List<CodingBenchmarkTrial> trials,
        Map<String, String> caseSequences,
        Map<String, Integer> sequenceCounts
) {
    /** Makes the plan collections immutable so dispatch cannot silently alter the registered matrix. */
    public CodingBenchmarkPlan {
        trials = List.copyOf(Objects.requireNonNull(trials, "trials must not be null"));
        caseSequences = immutableCopy(caseSequences, "case sequences");
        sequenceCounts = immutableCopy(sequenceCounts, "sequence counts");
    }

    /**
     * Returns the pre-registered dispatch order for a case.
     *
     * @param caseId frozen benchmark case identifier
     * @return one of {@code ABCD}, {@code BCDA}, {@code CDAB}, or {@code DABC}
     */
    public String sequenceForCase(String caseId) {
        String sequence = caseSequences.get(caseId);
        if (sequence == null) {
            throw new IllegalArgumentException("case is not part of this benchmark plan: " + caseId);
        }
        return sequence;
    }

    private static <T> Map<String, T> immutableCopy(Map<String, T> value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        return Map.copyOf(new LinkedHashMap<>(value));
    }
}
