package com.wish.rd.engine.evaluation.model;

/** Selects the immutable lifecycle and execution contract for one evaluation campaign. */
public enum EvaluationMode {
    LEGACY_QUALITY,
    TASK_AUDIT,
    CODING_BENCHMARK;

    /** @return whether the campaign owns persistent case/arm trials. */
    public boolean isCodingBenchmark() {
        return this == CODING_BENCHMARK;
    }
}
