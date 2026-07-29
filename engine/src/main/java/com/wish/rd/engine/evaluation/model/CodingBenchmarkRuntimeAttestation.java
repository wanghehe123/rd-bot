package com.wish.rd.engine.evaluation.model;

/** Immutable runtime evidence proving the images and network isolation actually requested. */
public record CodingBenchmarkRuntimeAttestation(
        String trialId,
        String agentImageDigest,
        String oracleImageDigest,
        String agentNetworkMode,
        String oracleNetworkMode,
        boolean agentNetworkInternal,
        long recordedAtEpochMillis
) {
}
