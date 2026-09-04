package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.rag.project.memory.ProjectMemoryAdminQueryPort;
import com.wish.rd.rag.project.memory.ProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRetrievalAuditEntry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** PostgreSQL admin read adapter backed by the canonical project memory store. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresProjectMemoryAdminQueryPort implements ProjectMemoryAdminQueryPort {

    private final ProjectMemoryStore memoryStore;

    public PostgresProjectMemoryAdminQueryPort(ProjectMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public List<ProjectMemory> listMemories(String projectId) {
        return memoryStore.listByProject(projectId);
    }

    @Override
    public List<ProjectMemoryRetrievalAuditEntry> listRecentRetrievalAudits(String memoryId, int limit) {
        // Retrieval audit persistence is introduced with shadow metrics; admin reads stay bounded and empty until then.
        int bounded = Math.max(1, Math.min(limit, 100));
        if (bounded <= 0 || memoryId == null || memoryId.isBlank()) {
            return List.of();
        }
        return List.of();
    }
}
