package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemoryGovernanceResult;

/** Host-owned governance mutations with expected row-version checks. */
public interface ProjectMemoryGovernancePort {

    ProjectMemoryGovernanceResult confirm(ProjectMemoryConfirmCommand command);

    ProjectMemoryGovernanceResult correct(ProjectMemoryCorrectCommand command);

    ProjectMemoryGovernanceResult invalidate(ProjectMemoryInvalidateCommand command);

    ProjectMemoryGovernanceResult softDelete(ProjectMemorySoftDeleteCommand command);

    record ProjectMemoryConfirmCommand(
            String projectId,
            String memoryId,
            String revisionId,
            long expectedMemoryRowVersion,
            long expectedRevisionRowVersion
    ) {}

    record ProjectMemoryCorrectCommand(
            String projectId,
            String memoryId,
            String revisionId,
            long expectedMemoryRowVersion,
            long expectedRevisionRowVersion,
            String correctedTitle,
            String correctedSummary,
            String correctedContentJson,
            String correctedContentHash,
            String newRevisionId
    ) {}

    record ProjectMemoryInvalidateCommand(
            String projectId,
            String memoryId,
            String revisionId,
            long expectedMemoryRowVersion,
            long expectedRevisionRowVersion
    ) {}

    record ProjectMemorySoftDeleteCommand(
            String projectId,
            String memoryId,
            long expectedMemoryRowVersion
    ) {}
}
