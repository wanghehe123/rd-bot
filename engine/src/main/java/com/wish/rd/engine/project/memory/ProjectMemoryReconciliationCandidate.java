package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort.ProjectMemoryOperationDraft;

/** One finalized terminal evidence row that may be missing its deterministic operation. */
public record ProjectMemoryReconciliationCandidate(
        String evidenceId,
        ProjectMemoryOperationDraft draft
) {
}
