package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;

/** Receives phase boundaries from the local execution adapter. */
@FunctionalInterface
public interface EvaluationProgressListener {
    void phase(EvaluationRunStatus status, String message);
}
