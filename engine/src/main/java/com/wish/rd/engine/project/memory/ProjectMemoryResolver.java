package com.wish.rd.engine.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;

import java.util.Optional;

/** Pure resolver for ADD / NOOP / SUPERSEDE / QUARANTINE consolidation decisions. */
public final class ProjectMemoryResolver {

    public enum Action {
        ADD,
        NOOP,
        SUPERSEDE,
        QUARANTINE
    }

    public Action resolve(
            Optional<ProjectMemory> memory,
            Optional<ProjectMemoryRevision> headRevision,
            String candidateContentHash,
            boolean irreconcilableConflict
    ) {
        if (candidateContentHash == null || !candidateContentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("candidateContentHash must be a SHA-256 hex digest");
        }
        if (irreconcilableConflict && headRevision.isPresent()) {
            return Action.QUARANTINE;
        }
        if (memory.isEmpty() || headRevision.isEmpty() || headRevision.get().revisionId().isBlank()) {
            return Action.ADD;
        }
        if (headRevision.get().contentHash().equalsIgnoreCase(candidateContentHash)) {
            return Action.NOOP;
        }
        if (irreconcilableConflict) {
            return Action.QUARANTINE;
        }
        return Action.SUPERSEDE;
    }
}
