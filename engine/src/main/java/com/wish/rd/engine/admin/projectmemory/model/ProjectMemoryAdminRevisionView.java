package com.wish.rd.engine.admin.projectmemory.model;

import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;

/** Immutable revision row for admin detail views. */
public record ProjectMemoryAdminRevisionView(
        String revisionId,
        long version,
        ProjectMemoryRevisionStatus status,
        String title,
        String summary,
        String contentHash,
        long rowVersion,
        boolean head
) {}
