package com.wish.rd.rag.project.memory.model;

/** One leased worker claim over a durable operation row. */
public record ProjectMemoryOperationClaim(
        String operationId,
        String projectId,
        String kind,
        String sourceIdentity,
        String sourceContentHash,
        String extractorVersion,
        String schemaVersion,
        String operationKey,
        ProjectMemoryOperationStatus status,
        String leaseOwner,
        long fencingToken,
        long rowVersion,
        int attemptNo,
        int maxAttempts,
        long leaseUntilEpochMillis,
        long nextVisibleEpochMillis,
        String checkpointJson
) {
    public ProjectMemoryOperation operation() {
        return new ProjectMemoryOperation(
                operationId, projectId, kind, sourceIdentity, sourceContentHash,
                extractorVersion, schemaVersion, operationKey);
    }
}
