package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;

import com.wish.rd.rag.project.memory.model.ProjectMemoryType;

import java.util.List;
import java.util.Optional;

/** Persistence port for project-owned memory identities, immutable revisions and source snapshots. */
public interface ProjectMemoryStore {
    ProjectMemory create(ProjectMemory memory);

    Optional<ProjectMemory> find(String memoryId);

    Optional<ProjectMemory> findByLogicalKey(
            String projectId, String scopeRole, ProjectMemoryType memoryType, String logicalKey);

    void addRevision(ProjectMemoryRevision revision);

    void advanceHead(String memoryId, String revisionId, long expectedRowVersion);

    void supersedeRevision(String revisionId);

    List<ProjectMemoryRevision> listRevisions(String memoryId);

    List<ProjectMemory> listByProject(String projectId);

    List<ProjectMemoryRevision> listRetrievable(String projectId, String role);
    void addSource(ProjectMemorySource source);
    List<ProjectMemorySource> listSources(String revisionId);

    boolean replaceRevision(ProjectMemoryRevision revision, long expectedRowVersion);

    boolean replaceMemory(ProjectMemory memory, long expectedRowVersion);
}
