package com.wish.rd.engine.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;

/** Decides only the initial revision lifecycle from host-owned delivery evidence. */
public final class ProjectMemoryPromotionPolicy {
    public ProjectMemoryRevisionStatus statusFor(ProjectMemoryType type, ProjectMemoryPromotionEvidence evidence) {
        if (type == null || evidence == null) throw new IllegalArgumentException("type and evidence are required");
        if (evidence.administratorConfirmed()) return ProjectMemoryRevisionStatus.ACTIVE;
        if (evidence.failedOrCancelled()) return ProjectMemoryRevisionStatus.CANDIDATE;
        if (type == ProjectMemoryType.PROCEDURAL) {
            return evidence.deliverySucceeded() && evidence.qaSucceeded()
                    ? ProjectMemoryRevisionStatus.ACTIVE : ProjectMemoryRevisionStatus.CANDIDATE;
        }
        return evidence.deliverySucceeded() ? ProjectMemoryRevisionStatus.ACTIVE : ProjectMemoryRevisionStatus.CANDIDATE;
    }
}
