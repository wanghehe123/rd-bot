package com.wish.rd.rag.project.memory.impl;

import com.wish.rd.rag.project.memory.ProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Contract-only in-memory implementation; production truth remains PostgreSQL. */
public final class InMemoryProjectMemoryStore implements ProjectMemoryStore {
    private final ConcurrentMap<String, ProjectMemory> memories = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ProjectMemoryRevision> revisions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ProjectMemorySource> sources = new ConcurrentHashMap<>();

    @Override
    public ProjectMemory create(ProjectMemory memory) {
        if (memories.putIfAbsent(memory.memoryId(), memory) != null) {
            throw new IllegalStateException("project memory already exists: " + memory.memoryId());
        }
        return memory;
    }

    @Override
    public Optional<ProjectMemory> find(String memoryId) {
        return Optional.ofNullable(memories.get(memoryId));
    }

    @Override
    public Optional<ProjectMemory> findByLogicalKey(
            String projectId, String scopeRole, ProjectMemoryType memoryType, String logicalKey
    ) {
        String normalizedRole = scopeRole == null ? "" : scopeRole.strip();
        String normalizedKey = logicalKey == null ? "" : logicalKey.strip();
        return memories.values().stream()
                .filter(memory -> !memory.deleted())
                .filter(memory -> memory.projectId().equals(projectId))
                .filter(memory -> memory.memoryType() == memoryType)
                .filter(memory -> memory.scopeRole().equals(normalizedRole))
                .filter(memory -> memory.logicalKey().equals(normalizedKey))
                .findFirst();
    }

    @Override
    public void supersedeRevision(String revisionId) {
        ProjectMemoryRevision current = revisions.get(revisionId);
        if (current == null) {
            throw new IllegalArgumentException("unknown revision: " + revisionId);
        }
        if (current.status() == ProjectMemoryRevisionStatus.SUPERSEDED) {
            return;
        }
        if (current.status() != ProjectMemoryRevisionStatus.ACTIVE) {
            throw new IllegalStateException("only ACTIVE revisions may be superseded: " + revisionId);
        }
        revisions.put(revisionId, current.withStatus(ProjectMemoryRevisionStatus.SUPERSEDED));
    }

    @Override
    public void addRevision(ProjectMemoryRevision revision) {
        ProjectMemory memory = find(revision.memoryId())
                .orElseThrow(() -> new IllegalArgumentException("unknown memory: " + revision.memoryId()));
        if (!revision.supersedesRevisionId().isEmpty()) {
            ProjectMemoryRevision superseded = revisions.get(revision.supersedesRevisionId());
            if (superseded == null || !superseded.memoryId().equals(memory.memoryId())) {
                throw new IllegalArgumentException("supersedes revision must belong to the same memory");
            }
        }
        if (revisions.putIfAbsent(revision.revisionId(), revision) != null) {
            throw new IllegalStateException("revision already exists: " + revision.revisionId());
        }
    }

    @Override
    public void advanceHead(String memoryId, String revisionId, long expectedRowVersion) {
        memories.compute(memoryId, (ignored, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("unknown memory: " + memoryId);
            }
            ProjectMemoryRevision revision = revisions.get(revisionId);
            if (revision == null || !revision.memoryId().equals(current.memoryId())) {
                throw new IllegalArgumentException("head revision must belong to the same memory");
            }
            if (revision.status() != ProjectMemoryRevisionStatus.ACTIVE) {
                throw new IllegalArgumentException("only ACTIVE revisions may become the head");
            }
            if (current.rowVersion() != expectedRowVersion) {
                throw new IllegalStateException("project memory row version conflict: " + memoryId);
            }
            return current.withHead(revisionId, revision.version());
        });
    }

    @Override
    public List<ProjectMemoryRevision> listRevisions(String memoryId) {
        return revisions.values().stream().filter(revision -> revision.memoryId().equals(memoryId))
                .sorted(Comparator.comparingLong(ProjectMemoryRevision::version)).toList();
    }

    @Override
    public List<ProjectMemory> listByProject(String projectId) {
        return memories.values().stream()
                .filter(memory -> memory.projectId().equals(projectId))
                .sorted(Comparator.comparing(ProjectMemory::memoryId))
                .toList();
    }

    @Override
    public List<ProjectMemoryRevision> listRetrievable(String projectId, String role) {
        String normalizedRole = role == null ? "" : role.strip();
        return memories.values().stream()
                .filter(memory -> !memory.deleted() && memory.projectId().equals(projectId))
                .filter(memory -> memory.scopeRole().isEmpty() || memory.scopeRole().equals(normalizedRole))
                .map(memory -> revisions.get(memory.headRevisionId()))
                .filter(revision -> revision != null && revision.status().retrievable())
                .sorted(Comparator.comparing(ProjectMemoryRevision::revisionId))
                .toList();
    }

    @Override
    public void addSource(ProjectMemorySource source) {
        if (!revisions.containsKey(source.revisionId())) {
            throw new IllegalArgumentException("unknown revision: " + source.revisionId());
        }
        if (sources.putIfAbsent(source.sourceId(), source) != null) {
            throw new IllegalStateException("source already exists: " + source.sourceId());
        }
    }

    @Override
    public List<ProjectMemorySource> listSources(String revisionId) {
        return sources.values().stream().filter(source -> source.revisionId().equals(revisionId))
                .sorted(Comparator.comparing(ProjectMemorySource::sourceId)).toList();
    }

    public void markSourceReferencesUnavailable(String sourceId) {
        sources.computeIfPresent(sourceId, (ignored, source) -> source.referencesUnavailable());
    }

    /** Physically removes all rows for the project; only callable from purge port. */
    public void physicalPurgeProject(String projectId) {
        List<String> memoryIds = memories.values().stream()
                .filter(memory -> memory.projectId().equals(projectId))
                .map(ProjectMemory::memoryId)
                .toList();
        for (String memoryId : memoryIds) {
            for (ProjectMemoryRevision revision : listRevisions(memoryId)) {
                sources.entrySet().removeIf(entry -> entry.getValue().revisionId().equals(revision.revisionId()));
                revisions.remove(revision.revisionId());
            }
            memories.remove(memoryId);
        }
    }

    @Override
    public boolean replaceRevision(ProjectMemoryRevision revision, long expectedRowVersion) {
        ProjectMemoryRevision current = revisions.get(revision.revisionId());
        if (current == null || current.rowVersion() != expectedRowVersion) {
            return false;
        }
        revisions.put(revision.revisionId(), revision);
        return true;
    }

    @Override
    public boolean replaceMemory(ProjectMemory memory, long expectedRowVersion) {
        ProjectMemory current = memories.get(memory.memoryId());
        if (current == null || current.rowVersion() != expectedRowVersion) {
            return false;
        }
        memories.put(memory.memoryId(), memory);
        return true;
    }
}
