package com.wish.rd.engine.evaluation.model;

/** Outcome of the isolated Agent and Oracle container phases for one logical trial attempt. */
public record CodingBenchmarkExecutionResult(
        int agentExitCode,
        int oracleExitCode,
        boolean infrastructureFailure,
        String errorMessage,
        CodingBenchmarkRuntimeAttestation attestation
) {
    public CodingBenchmarkExecutionResult {
        errorMessage = errorMessage == null ? "" : errorMessage.strip();
    }
}
