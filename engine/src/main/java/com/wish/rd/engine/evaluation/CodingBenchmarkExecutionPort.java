package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionResult;

/** Executes one prepared coding benchmark trial using the repository's isolated runtime contract. */
@FunctionalInterface
public interface CodingBenchmarkExecutionPort {

    /**
     * Runs the trial without observing the oracle phase boundary.
     *
     * @param request prepared trial workspace and image contract
     * @return container exit state and runtime attestation without exposing secret relay credentials.
     */
    default CodingBenchmarkExecutionResult execute(CodingBenchmarkExecutionRequest request) {
        return execute(request, CodingBenchmarkExecutionHooks.noop());
    }

    /**
     * Runs the trial and reports the agent-to-oracle boundary so the caller can throttle oracle work.
     *
     * @param request prepared trial workspace and image contract
     * @param hooks phase callbacks; {@link CodingBenchmarkExecutionHooks#beforeOracle()} is invoked only
     *              when agent work succeeded and oracle work is about to start
     * @return container exit state and runtime attestation without exposing secret relay credentials.
     */
    CodingBenchmarkExecutionResult execute(
            CodingBenchmarkExecutionRequest request,
            CodingBenchmarkExecutionHooks hooks);
}
