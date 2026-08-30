package com.wish.rd.engine.admin.projectmemory.model;

import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;

/** Project-scoped memory summary for admin list views. */
public record ProjectMemoryAdminSummaryView(
        String memoryId,
        String projectId,
        String scopeRole,
        ProjectMemoryType memoryType,
        String logicalKey,
        long memoryRowVersion,
        String headRevisionId,
        long headVersion,
        ProjectMemoryRevisionStatus headStatus,
        boolean deleted
) {}
