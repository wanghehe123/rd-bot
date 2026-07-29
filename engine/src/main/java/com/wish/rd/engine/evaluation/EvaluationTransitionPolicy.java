package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import com.wish.rd.engine.evaluation.model.EvaluationMode;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** Central allowlist for evaluation lifecycle transitions. */
public final class EvaluationTransitionPolicy {
    private final Map<EvaluationMode, Map<EvaluationRunStatus, EnumSet<EvaluationRunStatus>>> allowedByMode =
            new EnumMap<>(EvaluationMode.class);

    public EvaluationTransitionPolicy() {
        configureLegacy(EvaluationMode.LEGACY_QUALITY);
        configureLegacy(EvaluationMode.TASK_AUDIT);
        configureCodingBenchmark();
    }

    private void configureLegacy(EvaluationMode mode) {
        allow(mode, EvaluationRunStatus.CREATED, EvaluationRunStatus.QUEUED, EvaluationRunStatus.FAILED);
        allow(mode, EvaluationRunStatus.QUEUED, EvaluationRunStatus.RECORDING,
                EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(mode, EvaluationRunStatus.RECORDING, EvaluationRunStatus.SCORING,
                EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(mode, EvaluationRunStatus.SCORING, EvaluationRunStatus.REPORTING,
                EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(mode, EvaluationRunStatus.REPORTING, EvaluationRunStatus.DIFFING, EvaluationRunStatus.SUCCEEDED,
                EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(mode, EvaluationRunStatus.DIFFING, EvaluationRunStatus.SUCCEEDED,
                EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(mode, EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.CANCELLED);
    }

    private void configureCodingBenchmark() {
        allowed(EvaluationMode.CODING_BENCHMARK);
        allow(EvaluationMode.CODING_BENCHMARK, EvaluationRunStatus.CREATED,
                EvaluationRunStatus.QUEUED, EvaluationRunStatus.FAILED);
        allow(EvaluationMode.CODING_BENCHMARK, EvaluationRunStatus.QUEUED,
                EvaluationRunStatus.PREPARING, EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(EvaluationMode.CODING_BENCHMARK, EvaluationRunStatus.PREPARING,
                EvaluationRunStatus.RUNNING_TRIALS, EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(EvaluationMode.CODING_BENCHMARK, EvaluationRunStatus.RUNNING_TRIALS,
                EvaluationRunStatus.SCORING, EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(EvaluationMode.CODING_BENCHMARK, EvaluationRunStatus.SCORING,
                EvaluationRunStatus.REPORTING, EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(EvaluationMode.CODING_BENCHMARK, EvaluationRunStatus.REPORTING,
                EvaluationRunStatus.SUCCEEDED, EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(EvaluationMode.CODING_BENCHMARK, EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.CANCELLED);
    }

    /** Returns whether the requested exact transition is allowed. */
    public boolean canTransition(EvaluationRunStatus from, EvaluationRunStatus to) {
        return canTransition(EvaluationMode.LEGACY_QUALITY, from, to);
    }

    /** Returns whether an exact transition is allowed for the selected campaign mode. */
    public boolean canTransition(EvaluationMode mode, EvaluationRunStatus from, EvaluationRunStatus to) {
        EvaluationMode safeMode = mode == null ? EvaluationMode.LEGACY_QUALITY : mode;
        return from != null && to != null && allowedByMode.getOrDefault(safeMode, Map.of())
                .getOrDefault(from, EnumSet.noneOf(EvaluationRunStatus.class)).contains(to);
    }

    /** Throws when a caller attempts to bypass the state machine. */
    public void requireTransition(EvaluationRunStatus from, EvaluationRunStatus to) {
        requireTransition(EvaluationMode.LEGACY_QUALITY, from, to);
    }

    /** Throws when a caller attempts to bypass the mode-specific state machine. */
    public void requireTransition(EvaluationMode mode, EvaluationRunStatus from, EvaluationRunStatus to) {
        if (!canTransition(mode, from, to)) {
            throw new IllegalStateException("illegal evaluation transition: " + from + " -> " + to);
        }
    }

    private void allow(EvaluationRunStatus from, EvaluationRunStatus... targets) {
        allow(EvaluationMode.LEGACY_QUALITY, from, targets);
    }

    private void allow(EvaluationMode mode, EvaluationRunStatus from, EvaluationRunStatus... targets) {
        allowed(mode).put(from, EnumSet.of(targets[0], targets));
    }

    private Map<EvaluationRunStatus, EnumSet<EvaluationRunStatus>> allowed(EvaluationMode mode) {
        return allowedByMode.computeIfAbsent(mode, ignored -> new EnumMap<>(EvaluationRunStatus.class));
    }
}
