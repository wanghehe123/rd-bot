package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRetrievalAuditEntry;

import java.util.List;

/** Read-only admin boundary for project-scoped memory inspection. */
public interface ProjectMemoryAdminQueryPort {
    List<ProjectMemory> listMemories(String projectId);

    List<ProjectMemoryRetrievalAuditEntry> listRecentRetrievalAudits(String memoryId, int limit);
}
