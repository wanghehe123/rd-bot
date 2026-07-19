package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** Central allowlist for evaluation lifecycle transitions. */
public final class EvaluationTransitionPolicy {
    private final Map<EvaluationRunStatus, EnumSet<EvaluationRunStatus>> allowed =
            new EnumMap<>(EvaluationRunStatus.class);

    public EvaluationTransitionPolicy() {
        allow(EvaluationRunStatus.CREATED, EvaluationRunStatus.QUEUED, EvaluationRunStatus.FAILED);
        allow(EvaluationRunStatus.QUEUED, EvaluationRunStatus.RECORDING,
                EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(EvaluationRunStatus.RECORDING, EvaluationRunStatus.SCORING,
                EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(EvaluationRunStatus.SCORING, EvaluationRunStatus.REPORTING,
                EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(EvaluationRunStatus.REPORTING, EvaluationRunStatus.DIFFING, EvaluationRunStatus.SUCCEEDED,
                EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(EvaluationRunStatus.DIFFING, EvaluationRunStatus.SUCCEEDED,
                EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.FAILED);
        allow(EvaluationRunStatus.CANCEL_REQUESTED, EvaluationRunStatus.CANCELLED);
    }

    /** Returns whether the requested exact transition is allowed. */
    public boolean canTransition(EvaluationRunStatus from, EvaluationRunStatus to) {
        return from != null && to != null && allowed.getOrDefault(from, EnumSet.noneOf(EvaluationRunStatus.class)).contains(to);
    }

    /** Throws when a caller attempts to bypass the state machine. */
    public void requireTransition(EvaluationRunStatus from, EvaluationRunStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("illegal evaluation transition: " + from + " -> " + to);
        }
    }

    private void allow(EvaluationRunStatus from, EvaluationRunStatus... targets) {
        allowed.put(from, EnumSet.of(targets[0], targets));
    }
}
