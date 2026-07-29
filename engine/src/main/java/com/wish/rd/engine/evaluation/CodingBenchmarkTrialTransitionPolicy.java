package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/** Defines the only legal lifecycle edges for a persisted coding benchmark trial. */
public final class CodingBenchmarkTrialTransitionPolicy {
    private final Map<CodingBenchmarkTrialStatus, EnumSet<CodingBenchmarkTrialStatus>> allowed;

    /** Builds the fixed bounded-retry lifecycle used by the coding benchmark scheduler. */
    public CodingBenchmarkTrialTransitionPolicy() {
        this.allowed = new EnumMap<>(CodingBenchmarkTrialStatus.class);
        allow(CodingBenchmarkTrialStatus.QUEUED,
                CodingBenchmarkTrialStatus.PREPARING, CodingBenchmarkTrialStatus.CANCELLED);
        allow(CodingBenchmarkTrialStatus.PREPARING,
                CodingBenchmarkTrialStatus.RUNNING_AGENTS, CodingBenchmarkTrialStatus.FAILED,
                CodingBenchmarkTrialStatus.CANCELLED);
        allow(CodingBenchmarkTrialStatus.RUNNING_AGENTS,
                CodingBenchmarkTrialStatus.RUNNING_ORACLE, CodingBenchmarkTrialStatus.RETRY_PENDING,
                CodingBenchmarkTrialStatus.FAILED, CodingBenchmarkTrialStatus.CANCELLED);
        allow(CodingBenchmarkTrialStatus.RUNNING_ORACLE,
                CodingBenchmarkTrialStatus.SUCCEEDED, CodingBenchmarkTrialStatus.RETRY_PENDING,
                CodingBenchmarkTrialStatus.FAILED, CodingBenchmarkTrialStatus.CANCELLED);
        allow(CodingBenchmarkTrialStatus.RETRY_PENDING,
                CodingBenchmarkTrialStatus.QUEUED, CodingBenchmarkTrialStatus.FAILED,
                CodingBenchmarkTrialStatus.CANCELLED);
    }

    /** Throws when a requested lifecycle edge would mutate a terminal or otherwise invalid state. */
    public void requireTransition(CodingBenchmarkTrialStatus from, CodingBenchmarkTrialStatus to) {
        CodingBenchmarkTrialStatus safeFrom = Objects.requireNonNull(from, "source trial status must not be null");
        CodingBenchmarkTrialStatus safeTo = Objects.requireNonNull(to, "target trial status must not be null");
        if (!allowed.getOrDefault(safeFrom, EnumSet.noneOf(CodingBenchmarkTrialStatus.class)).contains(safeTo)) {
            throw new IllegalStateException("illegal coding benchmark trial transition: " + safeFrom + " -> " + safeTo);
        }
    }

    private void allow(CodingBenchmarkTrialStatus from, CodingBenchmarkTrialStatus... targets) {
        allowed.put(from, EnumSet.copyOf(java.util.List.of(targets)));
    }
}
