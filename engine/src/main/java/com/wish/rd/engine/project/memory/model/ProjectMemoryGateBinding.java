package com.wish.rd.engine.project.memory.model;

/** Per-project binding between a frozen gate revision and a successful shadow audit. */
public record ProjectMemoryGateBinding(
        String projectId,
        String gateRevision,
        String datasetRevision,
        long boundAtEpochMillis
) {
    public ProjectMemoryGateBinding {
        projectId = projectId == null ? "" : projectId.strip();
        gateRevision = gateRevision == null ? "" : gateRevision.strip();
        datasetRevision = datasetRevision == null ? "" : datasetRevision.strip();
        boundAtEpochMillis = Math.max(0L, boundAtEpochMillis);
    }
}
