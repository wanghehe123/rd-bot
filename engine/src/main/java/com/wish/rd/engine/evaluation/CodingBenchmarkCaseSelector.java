package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkCase;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkSlice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Deterministic stratified selection of probe cases from a frozen snapshot's case list.
 *
 * <p>The selection follows the stratification rules from the pre-registered analysis plan:
 * one FRESH_PRIMARY and one PUBLIC_ANCHOR, preferring cases with known difficulty and
 * language diversity within each stratum.</p>
 */
public final class CodingBenchmarkCaseSelector {

    private final Supplier<long[]> entropySupplier;

    /** Creates a selector with the system entropy source for deterministic determinism. */
    public CodingBenchmarkCaseSelector() {
        this(() -> new long[]{System.currentTimeMillis()});
    }

    /**
     * Creates a selector with an injectable entropy source for reproducible probe selections.
     *
     * @param entropySupplier returns a two-element array [{@code seed0}, {@code seed1}]
     */
    public CodingBenchmarkCaseSelector(Supplier<long[]> entropySupplier) {
        this.entropySupplier = Objects.requireNonNull(entropySupplier, "entropySupplier must not be null");
    }

    /**
     * Selects 1-2 probe cases from the given snapshot case list using deterministic stratified sampling.
     *
     * <p>Selection rules (in priority order):
     *
     * <ol>
     *   <li>One FRESH_PRIMARY (JAVA preferred over TSJS; MEDIUM preferred over EASY/HARD)
     *   <li>One PUBLIC_ANCHOR (JAVA preferred over TSJS; MEDIUM preferred over EASY/HARD)
     * </ol>
     *
     * @param allCases all cases from the frozen snapshot's dataset-manifest
     * @return a list of 1-2 selected cases (empty if fewer than 1 valid case exists)
     */
    public List<CodingBenchmarkCase> selectProbeCases(List<CodingBenchmarkCase> allCases) {
        Objects.requireNonNull(allCases, "allCases must not be null");
        if (allCases.isEmpty()) {
            return List.of();
        }

        List<CodingBenchmarkCase> fresh = new ArrayList<>();
        List<CodingBenchmarkCase> publicA = new ArrayList<>();
        for (CodingBenchmarkCase c : allCases) {
            if (c.slice() == CodingBenchmarkSlice.FRESH_PRIMARY) {
                fresh.add(c);
            } else {
                publicA.add(c);
            }
        }

        // Sort within each stratum: JAVA before TSJS, then MEDIUM before others
        Comparator<CodingBenchmarkCase> preference = Comparator
                .comparing((CodingBenchmarkCase c) -> !"JAVA".equals(c.language()))
                .thenComparing(c -> !"MEDIUM".equals(c.difficulty()));

        fresh.sort(preference);
        publicA.sort(preference);

        List<CodingBenchmarkCase> selected = new ArrayList<>(2);
        if (!fresh.isEmpty()) {
            selected.add(fresh.get(0));
        }
        if (!publicA.isEmpty()) {
            selected.add(publicA.get(0));
        }
        return List.copyOf(selected);
    }

    /**
     * Selects a cost-limited formal matrix (default 5 cases) with slice diversity.
     *
     * <p>Picks up to {@code limit} cases alternating FRESH_PRIMARY and PUBLIC_ANCHOR,
     * preferring JAVA/MEDIUM within each stratum. Used when a full 20-case formal is too expensive.</p>
     *
     * @param allCases snapshot cases
     * @param limit maximum cases to keep (must be &gt;= 1)
     * @return 1..limit selected cases
     */
    public List<CodingBenchmarkCase> selectCostLimitedCases(List<CodingBenchmarkCase> allCases, int limit) {
        Objects.requireNonNull(allCases, "allCases must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        if (allCases.isEmpty()) {
            return List.of();
        }

        List<CodingBenchmarkCase> fresh = new ArrayList<>();
        List<CodingBenchmarkCase> publicA = new ArrayList<>();
        for (CodingBenchmarkCase c : allCases) {
            if (c.slice() == CodingBenchmarkSlice.FRESH_PRIMARY) {
                fresh.add(c);
            } else {
                publicA.add(c);
            }
        }

        Comparator<CodingBenchmarkCase> preference = Comparator
                .comparing((CodingBenchmarkCase c) -> !"JAVA".equals(c.language()))
                .thenComparing(c -> !"MEDIUM".equals(c.difficulty()))
                .thenComparing(CodingBenchmarkCase::caseId);

        fresh.sort(preference);
        publicA.sort(preference);

        List<CodingBenchmarkCase> selected = new ArrayList<>(Math.min(limit, allCases.size()));
        int fi = 0;
        int pi = 0;
        while (selected.size() < limit && (fi < fresh.size() || pi < publicA.size())) {
            if (fi < fresh.size()) {
                selected.add(fresh.get(fi++));
            }
            if (selected.size() >= limit) {
                break;
            }
            if (pi < publicA.size()) {
                selected.add(publicA.get(pi++));
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Builds the full sorted list of CodingBenchmarkCase objects from raw dataset-manifest case nodes.
     *
     * @param rawCases JSON-parsed array from the dataset-manifest
     * @return cases sorted by caseId, with validated fields and resolved slices
     */
    public static List<CodingBenchmarkCase> fromRawCases(List<Map<String, Object>> rawCases) {
        Objects.requireNonNull(rawCases, "rawCases must not be null");
        List<CodingBenchmarkCase> cases = new ArrayList<>(rawCases.size());
        for (Map<String, Object> raw : rawCases) {
            String caseId = stringOrDefault(raw.get("caseId"), "");
            String sliceRaw = stringOrDefault(raw.get("slice"), "PUBLIC_ANCHOR");
            CodingBenchmarkSlice slice = sliceRaw.equals("FRESH_PRIMARY")
                    ? CodingBenchmarkSlice.FRESH_PRIMARY
                    : CodingBenchmarkSlice.PUBLIC_ANCHOR;
            String language = stringOrDefault(raw.get("language"), "UNKNOWN");
            String difficulty = stringOrDefault(raw.get("difficulty"), "MEDIUM");
            String repositoryId = stringOrDefault(raw.get("repositoryId"), "dataset/" + caseId);
            cases.add(new CodingBenchmarkCase(caseId, slice, repositoryId, language, difficulty));
        }
        cases.sort(Comparator.comparing(CodingBenchmarkCase::caseId));
        return List.copyOf(cases);
    }

    private static String stringOrDefault(Object value, String fallback) {
        if (value instanceof String s) {
            return s.strip();
        }
        return fallback;
    }
}
