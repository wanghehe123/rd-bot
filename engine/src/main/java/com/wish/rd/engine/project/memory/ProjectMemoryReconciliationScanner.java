package com.wish.rd.engine.project.memory;

import com.wish.rd.rag.project.memory.ProjectMemoryOperationStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperation;

import java.util.List;
import java.util.Objects;

/**
 * Scans finalized terminal evidence and backfills missing deterministic operations.
 * This path never replays requirement delivery or stage execution.
 */
public final class ProjectMemoryReconciliationScanner {
    private final ProjectMemoryReconciliationSourcePort source;
    private final ProjectMemoryOperationStore operationStore;

    public ProjectMemoryReconciliationScanner(
            ProjectMemoryReconciliationSourcePort source,
            ProjectMemoryOperationStore operationStore
    ) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.operationStore = Objects.requireNonNull(operationStore, "operationStore must not be null");
    }

    public ProjectMemoryReconciliationResult reconcileOnce(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        List<ProjectMemoryReconciliationCandidate> candidates =
                source.listTerminalEvidenceMissingOperation(batchSize);
        int registered = 0;
        int skippedExisting = 0;
        for (ProjectMemoryReconciliationCandidate candidate : candidates) {
            String operationKey = ProjectMemoryReconciliationDraftFactory.operationKey(candidate.draft());
            if (operationStore.findByKey(operationKey).isPresent()) {
                skippedExisting += 1;
                continue;
            }
            ProjectMemoryOperation registeredOperation =
                    ProjectMemoryFinalizationRegistrar.registerIfPresent(operationStore, candidate.draft());
            if (registeredOperation != null) {
                registered += 1;
            }
        }
        return new ProjectMemoryReconciliationResult(candidates.size(), registered, skippedExisting);
    }
}
