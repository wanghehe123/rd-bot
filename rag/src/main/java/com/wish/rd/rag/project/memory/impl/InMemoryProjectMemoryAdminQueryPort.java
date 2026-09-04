package com.wish.rd.rag.project.memory.impl;

import com.wish.rd.rag.project.memory.ProjectMemoryAdminQueryPort;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRetrievalAuditEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Contract-only admin query adapter backed by the in-memory store. */
public final class InMemoryProjectMemoryAdminQueryPort implements ProjectMemoryAdminQueryPort {

    private final InMemoryProjectMemoryStore store;
    private final CopyOnWriteArrayList<ProjectMemoryRetrievalAuditEntry> retrievalAudits =
            new CopyOnWriteArrayList<>();

    public InMemoryProjectMemoryAdminQueryPort(InMemoryProjectMemoryStore store) {
        this.store = store;
    }

    public void recordRetrievalAudit(ProjectMemoryRetrievalAuditEntry entry) {
        retrievalAudits.add(entry);
    }

    @Override
    public List<ProjectMemory> listMemories(String projectId) {
        return store.listByProject(projectId);
    }

    @Override
    public List<ProjectMemoryRetrievalAuditEntry> listRecentRetrievalAudits(String memoryId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        List<ProjectMemoryRetrievalAuditEntry> matches = new ArrayList<>();
        for (ProjectMemoryRetrievalAuditEntry entry : retrievalAudits) {
            if (entry.memoryId().equals(memoryId)) {
                matches.add(entry);
            }
        }
        matches.sort(Comparator.comparingLong(ProjectMemoryRetrievalAuditEntry::observedAtEpochMillis).reversed());
        if (matches.size() <= bounded) {
            return List.copyOf(matches);
        }
        return List.copyOf(matches.subList(0, bounded));
    }

    /** Removes retrieval audits for the given memory identities. */
    public void purgeRetrievalAudits(List<String> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        retrievalAudits.removeIf(entry -> memoryIds.contains(entry.memoryId()));
    }
}
