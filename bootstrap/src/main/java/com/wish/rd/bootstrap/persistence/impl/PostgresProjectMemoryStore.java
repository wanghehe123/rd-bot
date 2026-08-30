package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.ProjectMemoryRevisionRow;
import com.wish.rd.bootstrap.persistence.entity.ProjectMemoryRow;
import com.wish.rd.bootstrap.persistence.entity.ProjectMemorySourceRow;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryMapper;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryRevisionMapper;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemorySourceMapper;
import com.wish.rd.rag.project.memory.ProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** PostgreSQL canonical adapter for project-owned memory identities and revisions. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresProjectMemoryStore implements ProjectMemoryStore {
    private final ProjectMemoryMapper mapper;
    private final ProjectMemoryRevisionMapper revisions;
    private final ProjectMemorySourceMapper sources;

    @Autowired
    public PostgresProjectMemoryStore(
            ProjectMemoryMapper mapper,
            ProjectMemoryRevisionMapper revisions,
            ProjectMemorySourceMapper sources
    ) {
        this.mapper = mapper;
        this.revisions = revisions;
        this.sources = sources;
    }

    @Override
    public ProjectMemory create(ProjectMemory memory) {
        ProjectMemoryRow row = new ProjectMemoryRow();
        row.id = PostgresPersistenceSupport.parseId(memory.memoryId());
        row.projectId = strictProject(memory.projectId());
        row.scopeRole = memory.scopeRole();
        row.memoryType = memory.memoryType().name();
        row.logicalKey = memory.logicalKey();
        row.headRevisionId = PostgresPersistenceSupport.parseOptionalId(memory.headRevisionId());
        row.headVersion = memory.headVersion();
        row.rowVersion = memory.rowVersion();
        row.lifecycleStatus = memory.deleted() ? "DELETED" : "ENABLED";
        mapper.insertMemory(row);
        return memory;
    }

    @Override
    public Optional<ProjectMemory> find(String memoryId) {
        return Optional.ofNullable(mapper.selectById(PostgresPersistenceSupport.parseId(memoryId)))
                .filter(row -> !"DELETED".equals(row.lifecycleStatus))
                .map(this::model);
    }

    @Override
    public Optional<ProjectMemory> findByLogicalKey(
            String projectId, String scopeRole, ProjectMemoryType memoryType, String logicalKey
    ) {
        return Optional.ofNullable(mapper.selectByLogicalKey(
                        strictProject(projectId),
                        scopeRole == null ? "" : scopeRole,
                        memoryType.name(),
                        logicalKey))
                .filter(row -> !"DELETED".equals(row.lifecycleStatus))
                .map(this::model);
    }

    @Override
    public void supersedeRevision(String revisionId) {
        requireRevisionMapper();
        if (revisions.markSuperseded(PostgresPersistenceSupport.parseId(revisionId)) != 1) {
            throw new IllegalStateException("revision supersede failed: " + revisionId);
        }
    }

    @Override
    public void addRevision(ProjectMemoryRevision revision) {
        requireRevisionMapper();
        ProjectMemoryRevisionRow row = new ProjectMemoryRevisionRow();
        row.id = PostgresPersistenceSupport.parseId(revision.revisionId());
        row.memoryId = PostgresPersistenceSupport.parseId(revision.memoryId());
        row.version = revision.version();
        row.status = revision.status().name();
        row.title = revision.title();
        row.summary = revision.summary();
        row.contentJson = revision.contentJson();
        row.contentHash = revision.contentHash();
        row.schemaVersion = revision.schemaVersion();
        row.supersedesRevisionId = PostgresPersistenceSupport.parseOptionalId(revision.supersedesRevisionId());
        revisions.insert(row);
    }

    @Override
    public void advanceHead(String memoryId, String revisionId, long expectedRowVersion) {
        if (mapper.advanceHead(
                PostgresPersistenceSupport.parseId(memoryId),
                PostgresPersistenceSupport.parseId(revisionId),
                0L,
                expectedRowVersion) != 1) {
            throw new IllegalStateException("project memory row version conflict: " + memoryId);
        }
    }

    @Override
    public List<ProjectMemoryRevision> listRevisions(String memoryId) {
        requireRevisionMapper();
        return revisions.selectByMemoryId(PostgresPersistenceSupport.parseId(memoryId)).stream()
                .map(this::revisionModel)
                .toList();
    }

    @Override
    public List<ProjectMemory> listByProject(String projectId) {
        return mapper.selectByProjectId(strictProject(projectId)).stream()
                .map(this::model)
                .toList();
    }

    @Override
    public List<ProjectMemoryRevision> listRetrievable(String projectId, String role) {
        return List.of();
    }

    @Override
    public void addSource(ProjectMemorySource source) {
        requireSourceMapper();
        ProjectMemorySourceRow row = new ProjectMemorySourceRow();
        row.id = PostgresPersistenceSupport.parseId(source.sourceId());
        row.revisionId = PostgresPersistenceSupport.parseId(source.revisionId());
        row.projectId = PostgresPersistenceSupport.parseId(source.projectId());
        row.taskId = PostgresPersistenceSupport.parseOptionalId(source.taskId());
        row.stageRunId = PostgresPersistenceSupport.parseOptionalId(source.stageRunId());
        row.artifactId = PostgresPersistenceSupport.parseOptionalId(source.artifactId());
        row.sourceUri = source.sourceUri();
        row.sourceContentHash = source.sourceContentHash();
        row.repositoryRevision = source.repositoryRevision();
        row.extractorVersion = source.extractorVersion();
        row.schemaVersion = source.schemaVersion();
        row.redactedSummary = source.redactedSummary();
        sources.insert(row);
    }

    /** Persists immutable revision and source before advancing the single head in one transaction. */
    @Transactional
    public void persistRevisionSourceAndAdvanceHead(
            ProjectMemoryRevision revision,
            ProjectMemorySource source,
            long expectedRowVersion
    ) {
        addRevision(revision);
        addSource(source);
        if (mapper.advanceHead(
                PostgresPersistenceSupport.parseId(revision.memoryId()),
                PostgresPersistenceSupport.parseId(revision.revisionId()),
                revision.version(),
                expectedRowVersion) != 1) {
            throw new IllegalStateException("project memory row version conflict: " + revision.memoryId());
        }
    }

    @Override
    public List<ProjectMemorySource> listSources(String revisionId) {
        requireSourceMapper();
        return sources.selectByRevisionId(PostgresPersistenceSupport.parseId(revisionId)).stream()
                .map(this::sourceModel)
                .toList();
    }

    @Override
    public boolean replaceRevision(ProjectMemoryRevision revision, long expectedRowVersion) {
        requireRevisionMapper();
        return revisions.replaceRevision(
                PostgresPersistenceSupport.parseId(revision.revisionId()),
                revision.status().name(),
                revision.title(),
                revision.summary(),
                revision.contentJson(),
                revision.contentHash(),
                expectedRowVersion) == 1;
    }

    @Override
    public boolean replaceMemory(ProjectMemory memory, long expectedRowVersion) {
        return mapper.replaceMemory(
                PostgresPersistenceSupport.parseId(memory.memoryId()),
                memory.deleted() ? "DELETED" : "ENABLED",
                PostgresPersistenceSupport.parseOptionalId(memory.headRevisionId()),
                memory.headVersion(),
                expectedRowVersion) == 1;
    }

    /** Loads a memory row for admin/governance paths, including soft-deleted identities. */
    public Optional<ProjectMemory> findIncludingDeleted(String memoryId) {
        return Optional.ofNullable(mapper.selectById(PostgresPersistenceSupport.parseId(memoryId)))
                .map(this::model);
    }

    private ProjectMemory model(ProjectMemoryRow row) {
        return new ProjectMemory(
                row.id.toString(),
                row.projectId.toString(),
                row.scopeRole,
                ProjectMemoryType.valueOf(row.memoryType),
                row.logicalKey,
                row.rowVersion,
                PostgresPersistenceSupport.idString(row.headRevisionId),
                row.headVersion,
                "DELETED".equals(row.lifecycleStatus));
    }

    private ProjectMemoryRevision revisionModel(ProjectMemoryRevisionRow row) {
        return new ProjectMemoryRevision(
                row.id.toString(),
                row.memoryId.toString(),
                row.version,
                com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus.valueOf(row.status),
                row.title == null ? "" : row.title,
                row.summary == null ? "" : row.summary,
                row.contentJson == null ? "{}" : row.contentJson,
                row.contentHash,
                row.schemaVersion,
                PostgresPersistenceSupport.idString(row.supersedesRevisionId),
                0L,
                row.rowVersion == null ? 1L : row.rowVersion
        );
    }

    private ProjectMemorySource sourceModel(ProjectMemorySourceRow row) {
        return new ProjectMemorySource(
                row.id.toString(),
                row.revisionId.toString(),
                row.projectId.toString(),
                PostgresPersistenceSupport.idString(row.taskId),
                PostgresPersistenceSupport.idString(row.stageRunId),
                PostgresPersistenceSupport.idString(row.artifactId),
                row.sourceUri == null ? "" : row.sourceUri,
                row.sourceContentHash,
                row.repositoryRevision == null ? "" : row.repositoryRevision,
                row.extractorVersion,
                row.schemaVersion,
                row.redactedSummary == null ? "" : row.redactedSummary
        );
    }

    private static Long strictProject(String projectId) {
        if (projectId == null || !projectId.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("projectId must be a non-empty numeric identifier");
        }
        return PostgresPersistenceSupport.parseId(projectId);
    }

    private void requireRevisionMapper() {
        if (revisions == null) {
            throw new UnsupportedOperationException("revision persistence not configured");
        }
    }

    private void requireSourceMapper() {
        if (sources == null) {
            throw new UnsupportedOperationException("source persistence not configured");
        }
    }
}
