package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.EvaluationExecutionResult;
import com.wish.rd.engine.evaluation.model.EvaluationRun;

/** Executes the allowlisted Python evaluation pipeline on the local machine. */
public interface EvaluationExecutionPort {
    EvaluationExecutionResult execute(EvaluationRun run, EvaluationProgressListener listener);

    void cancel(String runId);
}
