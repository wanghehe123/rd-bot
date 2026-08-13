package com.wish.rd.bootstrap.openviking;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 文档级召回与引用有效率。不可答题不进入 Recall 平均，避免空期望把召回抬成 1。
 */
final class ShadowRetrievalEvaluationMetrics {

    private ShadowRetrievalEvaluationMetrics() {
    }

    static double recallAtK(List<String> rankedDocumentIds, Set<String> expectedDocumentIds, int k) {
        Set<String> expected = expectedDocumentIds == null ? Set.of() : Set.copyOf(expectedDocumentIds);
        if (expected.isEmpty()) {
            return Double.NaN;
        }
        Set<String> prefix = prefix(rankedDocumentIds, k);
        int hits = 0;
        for (String documentId : expected) {
            if (prefix.contains(documentId)) {
                hits++;
            }
        }
        return hits / (double) expected.size();
    }

    /**
     * 返回的引用里有多少落在期望集合。不可答题且零引用记 1；有答期望但零引用记 NaN。
     */
    static double citationValidity(List<String> rankedDocumentIds, Set<String> expectedDocumentIds) {
        Set<String> expected = expectedDocumentIds == null ? Set.of() : Set.copyOf(expectedDocumentIds);
        List<String> ranked = rankedDocumentIds == null ? List.of() : rankedDocumentIds;
        if (ranked.isEmpty()) {
            return expected.isEmpty() ? 1.0d : Double.NaN;
        }
        int valid = 0;
        for (String documentId : ranked) {
            if (expected.contains(documentId)) {
                valid++;
            }
        }
        return valid / (double) ranked.size();
    }

    static boolean falsePositiveCitation(List<String> rankedDocumentIds, Set<String> expectedDocumentIds) {
        Set<String> expected = expectedDocumentIds == null ? Set.of() : Set.copyOf(expectedDocumentIds);
        if (!expected.isEmpty()) {
            return false;
        }
        return rankedDocumentIds != null && !rankedDocumentIds.isEmpty();
    }

    /**
     * 与 {@code OpenVikingLocalRetrievalBaselineTest} 同一套 nearest-rank：
     * {@code ceil(p * n) - 1}，再夹到 {@code [0, n-1]}。
     */
    static long percentile(List<Long> sortedAscending, double percentile) {
        if (sortedAscending == null || sortedAscending.isEmpty()) {
            throw new IllegalArgumentException("percentile needs at least one sample");
        }
        int index = Math.min(sortedAscending.size() - 1, (int) Math.ceil(percentile * sortedAscending.size()) - 1);
        return sortedAscending.get(Math.max(0, index));
    }

    static List<String> uniqueInOrder(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(documentIds));
    }

    private static Set<String> prefix(List<String> rankedDocumentIds, int k) {
        if (rankedDocumentIds == null || rankedDocumentIds.isEmpty() || k <= 0) {
            return Set.of();
        }
        int bound = Math.min(k, rankedDocumentIds.size());
        return new LinkedHashSet<>(rankedDocumentIds.subList(0, bound));
    }

    static double meanOfFinite(List<Double> values) {
        double sum = 0.0d;
        int count = 0;
        for (Double value : values == null ? List.<Double>of() : values) {
            if (value != null && !value.isNaN()) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    static List<Long> sortedCopy(List<Long> values) {
        List<Long> copy = new ArrayList<>(values == null ? List.of() : values);
        copy.sort(Long::compareTo);
        return copy;
    }
}
