package com.wish.rd.engine.evaluation.model;

/** Versioned JSONL dataset discovered under the server-owned dataset root. */
public record EvaluationDataset(
        String id,
        int sampleCount,
        long sizeBytes,
        EvaluationDatasetKind kind,
        String version,
        String sha256
) {
    public EvaluationDataset {
        kind = kind == null ? EvaluationDatasetKind.SCORER_SMOKE : kind;
        version = version == null ? "" : version;
        sha256 = sha256 == null ? "" : sha256;
    }

    /** Backward-compatible descriptor for callers that do not yet classify datasets. */
    public EvaluationDataset(String id, int sampleCount, long sizeBytes) {
        this(id, sampleCount, sizeBytes, EvaluationDatasetKind.SCORER_SMOKE, "", "");
    }
}
