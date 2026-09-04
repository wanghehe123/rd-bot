package com.wish.rd.engine.admin.projectmemory.model;

import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;

/** Result of a governance mutation for admin feedback. */
public record ProjectMemoryAdminMutationResult(
        String memoryId,
        String revisionId,
        long memoryRowVersion,
        long revisionRowVersion,
        ProjectMemoryRevisionStatus revisionStatus,
        boolean memoryDeleted,
        String operatorId,
        String requestId
) {}
