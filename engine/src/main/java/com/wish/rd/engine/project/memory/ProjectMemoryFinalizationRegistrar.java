package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort.ProjectMemoryOperationDraft;
import com.wish.rd.rag.project.memory.ProjectMemoryOperationStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperation;

/** Registers deterministic project-memory operations at the stage finalization boundary. */
public final class ProjectMemoryFinalizationRegistrar {

    private ProjectMemoryFinalizationRegistrar() {
    }

    /**
     * Registers or re-validates one memory operation when the draft is enabled.
     *
     * @param store durable operation store bound to the same transaction as finalization
     * @param draft host-owned registration intent; {@link ProjectMemoryOperationDraft#none()} is a no-op
     * @return registered operation, or {@code null} when the draft is disabled
     * @throws IllegalStateException when the draft is enabled but the store is absent, or immutable inputs conflict
     */
    public static ProjectMemoryOperation registerIfPresent(
            ProjectMemoryOperationStore store,
            ProjectMemoryOperationDraft draft
    ) {
        if (draft == null || !draft.enabled()) {
            return null;
        }
        if (store == null) {
            throw new IllegalStateException("project memory operation store is not configured");
        }
        ProjectMemoryOperation operation = ProjectMemoryOperation.pending(
                draft.operationId(),
                draft.projectId(),
                draft.kind(),
                draft.sourceIdentity(),
                draft.sourceContentHash(),
                draft.extractorVersion(),
                draft.schemaVersion());
        return store.register(operation);
    }
}
