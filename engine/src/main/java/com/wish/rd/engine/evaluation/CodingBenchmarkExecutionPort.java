package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionResult;

/** Executes one prepared coding benchmark trial using the repository's isolated runtime contract. */
@FunctionalInterface
public interface CodingBenchmarkExecutionPort {

    /** @return container exit state and runtime attestation without exposing secret relay credentials. */
    CodingBenchmarkExecutionResult execute(CodingBenchmarkExecutionRequest request);
}
