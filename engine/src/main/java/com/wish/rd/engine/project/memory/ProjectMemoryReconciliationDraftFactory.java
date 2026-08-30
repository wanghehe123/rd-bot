package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort.ProjectMemoryOperationDraft;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationKey;

/** Host-owned deterministic draft builder shared by finalization and reconciliation. */
public final class ProjectMemoryReconciliationDraftFactory {
    public static final String SCHEMA_VERSION = "project-agent-memory-v1";
    public static final String EXTRACTOR_VERSION = "host-stage-finalization-v1";
    public static final String SOURCE_PREFIX = "stage-finalization:";

    private ProjectMemoryReconciliationDraftFactory() {
    }

    public static ProjectMemoryOperationDraft fromFinalizedCommand(
            String operationId,
            String projectId,
            String commandId,
            String terminalContentHash,
            boolean captureEnabled
    ) {
        String sourceIdentity = SOURCE_PREFIX + require(commandId, "commandId");
        String contentHash = requireHash(terminalContentHash);
        if (captureEnabled) {
            return ProjectMemoryOperationDraft.stageCapture(
                    require(operationId, "operationId"),
                    require(projectId, "projectId"),
                    sourceIdentity,
                    contentHash,
                    EXTRACTOR_VERSION,
                    SCHEMA_VERSION);
        }
        return ProjectMemoryOperationDraft.skippedByPolicy(
                require(operationId, "operationId"),
                require(projectId, "projectId"),
                sourceIdentity,
                contentHash,
                SCHEMA_VERSION);
    }

    public static String operationKey(ProjectMemoryOperationDraft draft) {
        return ProjectMemoryOperationKey.sha256(
                draft.projectId(),
                draft.kind(),
                draft.sourceIdentity(),
                draft.sourceContentHash(),
                draft.extractorVersion(),
                draft.schemaVersion());
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String requireHash(String value) {
        String normalized = require(value, "terminalContentHash");
        if (!normalized.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("terminalContentHash must be a 64-char lowercase hex digest");
        }
        return normalized;
    }
}
