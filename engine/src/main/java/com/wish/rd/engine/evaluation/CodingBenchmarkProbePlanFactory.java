package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Creates immutable reduced / probe trial sets for smoke and cost-limited formal runs.
 *
 * <p>Accepts 1-5 cases and produces {@code n_cases × 4} trials (all four arms, replicateNo = 0).
 * No sentinel trials are emitted. Kept separate from {@link CodingBenchmarkPlanFactory}
 * so the full 20-case formal invariant cannot be diluted.</p>
 */
public final class CodingBenchmarkProbePlanFactory {

    /** Cost-limited formal matrix size (5 cases × 4 arms = 20 trials). */
    public static final int COST_LIMITED_MAX_CASES = 5;

    private final Supplier<String> trialIdSupplier;
    private final LongSupplier nowSupplier;

    /** Creates a probe factory with production-safe generated IDs and wall-clock time. */
    public CodingBenchmarkProbePlanFactory(Supplier<String> trialIdSupplier) {
        this(trialIdSupplier, System::currentTimeMillis);
    }

    /**
     * Creates a probe factory with injectable ID and time sources for deterministic tests.
     *
     * @param trialIdSupplier source of unique persisted logical trial IDs
     * @param nowSupplier source of creation timestamps in epoch milliseconds
     */
    public CodingBenchmarkProbePlanFactory(Supplier<String> trialIdSupplier, LongSupplier nowSupplier) {
        this.trialIdSupplier = Objects.requireNonNull(trialIdSupplier, "trialIdSupplier must not be null");
        this.nowSupplier = Objects.requireNonNull(nowSupplier, "nowSupplier must not be null");
    }

    /**
     * Creates a probe factory with UUID-based trial IDs (for tests).
     *
     * @deprecated use {@link #CodingBenchmarkProbePlanFactory(Supplier)} with a Snowflake ID supplier
     */
    @Deprecated
    public CodingBenchmarkProbePlanFactory() {
        this(() -> UUID.randomUUID().toString(), System::currentTimeMillis);
    }

    /**
     * Pre-registers a reduced trial set for the given campaign.
     *
     * @param campaignId parent coding benchmark campaign ID
     * @param caseIds 1-{@link #COST_LIMITED_MAX_CASES} distinct case IDs
     * @return trial list (one entry per arm per case), sorted by caseId then arm name
     * @throws IllegalArgumentException if caseIds is empty, exceeds the max, or contains duplicates
     */
    public List<CodingBenchmarkTrial> create(String campaignId, List<String> caseIds) {
        Objects.requireNonNull(campaignId, "campaignId must not be null");
        Objects.requireNonNull(caseIds, "caseIds must not be null");
        if (caseIds.isEmpty()) {
            throw new IllegalArgumentException("at least one probe case is required");
        }
        if (caseIds.size() > COST_LIMITED_MAX_CASES) {
            throw new IllegalArgumentException(
                    "cost-limited mode accepts at most " + COST_LIMITED_MAX_CASES + " cases");
        }
        Set<String> unique = new LinkedHashSet<>(caseIds);
        if (unique.size() != caseIds.size()) {
            throw new IllegalArgumentException("probe case IDs must be unique");
        }
        long now = nowSupplier.getAsLong();
        if (now < 0L) {
            throw new IllegalArgumentException("creation timestamp must not be negative");
        }
        List<String> sorted = unique.stream().sorted().toList();
        List<CodingBenchmarkTrial> trials = new ArrayList<>(sorted.size() * 4);
        Set<String> trialIds = new LinkedHashSet<>();
        for (String caseId : sorted) {
            for (CodingBenchmarkArm arm : CodingBenchmarkArm.values()) {
                trials.add(queued(trialIds, campaignId, caseId, arm, now, trialIds.size()));
            }
        }
        return List.copyOf(trials);
    }

    private CodingBenchmarkTrial queued(
            Set<String> trialIds,
            String campaignId,
            String caseId,
            CodingBenchmarkArm arm,
            long now,
            int index
    ) {
        String trialId = trialIdSupplier.get() + "-" + index;
        if (!trialIds.add(trialId)) {
            throw new IllegalStateException("trial id supplier returned a duplicate ID");
        }
        return CodingBenchmarkTrial.queued(trialId, campaignId, caseId, arm, 0, now);
    }
}
