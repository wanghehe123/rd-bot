package com.wish.rd.engine.admin.projectmemory.model;

import com.wish.rd.rag.project.memory.model.ProjectMemoryType;

import java.util.List;

/** Full admin read model for one project memory identity. */
public record ProjectMemoryAdminDetailView(
        String memoryId,
        String projectId,
        String scopeRole,
        ProjectMemoryType memoryType,
        String logicalKey,
        long memoryRowVersion,
        String headRevisionId,
        long headVersion,
        boolean deleted,
        List<ProjectMemoryAdminRevisionView> revisions,
        List<ProjectMemoryAdminSourceView> sources,
        List<ProjectMemoryRetrievalAuditView> retrievalAudits
) {
    public ProjectMemoryAdminDetailView {
        revisions = revisions == null ? List.of() : List.copyOf(revisions);
        sources = sources == null ? List.of() : List.copyOf(sources);
        retrievalAudits = retrievalAudits == null ? List.of() : List.copyOf(retrievalAudits);
    }
}
