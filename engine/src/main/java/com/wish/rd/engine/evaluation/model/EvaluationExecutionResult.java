package com.wish.rd.engine.evaluation.model;

import java.util.List;

/** Parsed terminal output returned by the local Python evaluation adapter. */
public record EvaluationExecutionResult(
        int sampleCount,
        int passedSampleCount,
        int failedSampleCount,
        boolean overallPassed,
        String metricsJson,
        List<EvaluationArtifact> artifacts
) {
    public EvaluationExecutionResult {
        metricsJson = metricsJson == null ? "{}" : metricsJson;
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
