package com.wish.rd.bootstrap.evaluation.impl;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkRuntimeAttestation;

/** Creates redacted evidence of the fixed image and network contract requested for a trial. */
public final class CodingBenchmarkRuntimeAttestor {

    /** Builds attestation data without retaining host paths, command arguments or relay tokens. */
    public CodingBenchmarkRuntimeAttestation attest(CodingBenchmarkExecutionRequest request, long now) {
        return new CodingBenchmarkRuntimeAttestation(
                request.trial().trialId(), request.agentImageDigest(), request.oracleImageDigest(),
                "trial-internal", "none", true, now
        );
    }
}
