package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkCase;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkPlan;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkSlice;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Creates the immutable 20-case, four-arm coding benchmark matrix before any worker dispatches.
 */
public final class CodingBenchmarkPlanFactory {
    private static final List<String> SEQUENCE_NAMES = List.of("ABCD", "BCDA", "CDAB", "DABC");
    private static final List<List<CodingBenchmarkArm>> ARM_SEQUENCES = List.of(
            List.of(CodingBenchmarkArm.A, CodingBenchmarkArm.B, CodingBenchmarkArm.C, CodingBenchmarkArm.D),
            List.of(CodingBenchmarkArm.B, CodingBenchmarkArm.C, CodingBenchmarkArm.D, CodingBenchmarkArm.A),
            List.of(CodingBenchmarkArm.C, CodingBenchmarkArm.D, CodingBenchmarkArm.A, CodingBenchmarkArm.B),
            List.of(CodingBenchmarkArm.D, CodingBenchmarkArm.A, CodingBenchmarkArm.B, CodingBenchmarkArm.C)
    );

    private final Supplier<String> trialIdSupplier;
    private final LongSupplier nowSupplier;

    /** Creates a factory with production-safe generated IDs and the current wall-clock time. */
    public CodingBenchmarkPlanFactory() {
        this(() -> UUID.randomUUID().toString(), System::currentTimeMillis);
    }

    /**
     * Creates a factory with a Snowflake ID supplier for PostgreSQL-compatible numeric IDs.
     *
     * @param trialIdSupplier source of unique persisted logical trial IDs (Snowflake recommended)
     */
    public CodingBenchmarkPlanFactory(Supplier<String> trialIdSupplier) {
        this(trialIdSupplier, System::currentTimeMillis);
    }

    /**
     * Creates a factory with injectable ID and time sources for deterministic tests and adapters.
     *
     * @param trialIdSupplier source of unique persisted logical trial IDs
     * @param nowSupplier source of creation timestamps in epoch milliseconds
     */
    public CodingBenchmarkPlanFactory(Supplier<String> trialIdSupplier, LongSupplier nowSupplier) {
        this.trialIdSupplier = Objects.requireNonNull(trialIdSupplier, "trial id supplier must not be null");
        this.nowSupplier = Objects.requireNonNull(nowSupplier, "now supplier must not be null");
    }

    /**
     * Pre-registers exactly 80 formal matrix cells and eight A/D sentinel reruns.
     *
     * @param campaignId parent coding benchmark campaign ID
     * @param cases exactly ten fresh-primary and ten public-anchor cases
     * @param sentinelCaseIds exactly four existing case IDs selected before dispatch
     * @return immutable 88-trial plan with four balanced primary arm sequences
     */
    public CodingBenchmarkPlan create(
            String campaignId,
            List<CodingBenchmarkCase> cases,
            Set<String> sentinelCaseIds
    ) {
        List<CodingBenchmarkCase> sortedCases = validateAndSortCases(cases);
        Set<String> sentinels = validateSentinels(sortedCases, sentinelCaseIds);
        long now = nowSupplier.getAsLong();
        if (now < 0L) {
            throw new IllegalArgumentException("creation timestamp must not be negative");
        }

        List<CodingBenchmarkTrial> trials = new ArrayList<>(88);
        Set<String> trialIds = new LinkedHashSet<>();
        LinkedHashMap<String, String> caseSequences = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> sequenceCounts = new LinkedHashMap<>();
        for (String sequence : SEQUENCE_NAMES) {
            sequenceCounts.put(sequence, 0);
        }

        for (int index = 0; index < sortedCases.size(); index++) {
            CodingBenchmarkCase benchmarkCase = sortedCases.get(index);
            int sequenceIndex = index / 5;
            String sequenceName = SEQUENCE_NAMES.get(sequenceIndex);
            caseSequences.put(benchmarkCase.caseId(), sequenceName);
            sequenceCounts.compute(sequenceName, (ignored, count) -> count + 1);
            for (CodingBenchmarkArm arm : ARM_SEQUENCES.get(sequenceIndex)) {
                trials.add(queued(trialIds, campaignId, benchmarkCase.caseId(), arm, 0, now));
            }
        }

        sortedCases.stream()
                .map(CodingBenchmarkCase::caseId)
                .filter(sentinels::contains)
                .forEach(caseId -> {
                    trials.add(queued(trialIds, campaignId, caseId, CodingBenchmarkArm.A, 1, now));
                    trials.add(queued(trialIds, campaignId, caseId, CodingBenchmarkArm.D, 1, now));
                });

        return new CodingBenchmarkPlan(trials, caseSequences, sequenceCounts);
    }

    private CodingBenchmarkTrial queued(
            Set<String> trialIds,
            String campaignId,
            String caseId,
            CodingBenchmarkArm arm,
            int replicateNo,
            long now
    ) {
        String trialId = trialIdSupplier.get();
        if (!trialIds.add(trialId)) {
            throw new IllegalStateException("trial id supplier returned a duplicate ID");
        }
        return CodingBenchmarkTrial.queued(trialId, campaignId, caseId, arm, replicateNo, now);
    }

    private static List<CodingBenchmarkCase> validateAndSortCases(List<CodingBenchmarkCase> cases) {
        Objects.requireNonNull(cases, "cases must not be null");
        if (cases.size() != 20) {
            throw new IllegalArgumentException("coding benchmark must contain exactly 20 cases");
        }
        Set<String> caseIds = new LinkedHashSet<>();
        int freshPrimary = 0;
        int publicAnchor = 0;
        for (CodingBenchmarkCase benchmarkCase : cases) {
            Objects.requireNonNull(benchmarkCase, "benchmark case must not be null");
            if (!caseIds.add(benchmarkCase.caseId())) {
                throw new IllegalArgumentException("coding benchmark contains duplicate case id: " + benchmarkCase.caseId());
            }
            if (benchmarkCase.slice() == CodingBenchmarkSlice.FRESH_PRIMARY) {
                freshPrimary++;
            } else if (benchmarkCase.slice() == CodingBenchmarkSlice.PUBLIC_ANCHOR) {
                publicAnchor++;
            }
        }
        if (freshPrimary != 10 || publicAnchor != 10) {
            throw new IllegalArgumentException("coding benchmark must contain ten fresh-primary and ten public-anchor cases");
        }
        return cases.stream().sorted(Comparator.comparing(CodingBenchmarkCase::caseId)).toList();
    }

    private static Set<String> validateSentinels(
            List<CodingBenchmarkCase> cases,
            Set<String> sentinelCaseIds
    ) {
        Objects.requireNonNull(sentinelCaseIds, "sentinel case ids must not be null");
        if (sentinelCaseIds.size() != 4) {
            throw new IllegalArgumentException("coding benchmark must contain exactly four sentinel cases");
        }
        Set<String> existingCaseIds = cases.stream()
                .map(CodingBenchmarkCase::caseId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> normalizedSentinels = new LinkedHashSet<>();
        for (String caseId : sentinelCaseIds) {
            String normalized = caseId == null ? "" : caseId.strip();
            if (!existingCaseIds.contains(normalized)) {
                throw new IllegalArgumentException("sentinel case is not part of the coding benchmark: " + normalized);
            }
            normalizedSentinels.add(normalized);
        }
        return Set.copyOf(normalizedSentinels);
    }
}
