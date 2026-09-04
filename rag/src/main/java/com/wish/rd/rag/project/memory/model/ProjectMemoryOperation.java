package com.wish.rd.rag.project.memory.model;

/** Durable input identity for asynchronous consolidation. */
public record ProjectMemoryOperation(
        String operationId,
        String projectId,
        String kind,
        String sourceIdentity,
        String sourceContentHash,
        String extractorVersion,
        String schemaVersion,
        String operationKey
) {
    public static ProjectMemoryOperation pending(String operationId, String projectId, String kind, String sourceIdentity,
                                                 String sourceContentHash, String extractorVersion, String schemaVersion) {
        return new ProjectMemoryOperation(operationId, projectId, kind, sourceIdentity, sourceContentHash,
                extractorVersion, schemaVersion, ProjectMemoryOperationKey.sha256(
                        projectId, kind, sourceIdentity, sourceContentHash, extractorVersion, schemaVersion));
    }
}
