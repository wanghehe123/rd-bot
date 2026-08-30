package com.wish.rd.engine.admin.projectmemory;

import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminDetailView;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminRevisionView;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminSourceView;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminSummaryView;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryRetrievalAuditView;
import com.wish.rd.rag.project.memory.ProjectMemoryAdminQueryPort;
import com.wish.rd.rag.project.memory.ProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read-only admin orchestration for project memory inspection. */
@Service
public final class ProjectMemoryAdminService {

    private final ProjectMemoryAdminQueryPort queryPort;
    private final ProjectMemoryStore memoryStore;

    public ProjectMemoryAdminService(ProjectMemoryAdminQueryPort queryPort, ProjectMemoryStore memoryStore) {
        this.queryPort = queryPort;
        this.memoryStore = memoryStore;
    }

    public List<ProjectMemoryAdminSummaryView> listByProject(String projectId) {
        return queryPort.listMemories(projectId).stream().map(this::toSummary).toList();
    }

    public ProjectMemoryAdminDetailView getDetail(String projectId, String memoryId) {
        ProjectMemory memory = memoryStore.find(memoryId)
                .orElseThrow(() -> new IllegalArgumentException("unknown memory: " + memoryId));
        if (!memory.projectId().equals(projectId)) {
            throw new IllegalArgumentException("memory does not belong to project: " + projectId);
        }
        Map<String, ProjectMemoryRevisionStatus> statuses = memoryStore.listRevisions(memoryId).stream()
                .collect(Collectors.toMap(ProjectMemoryRevision::revisionId, ProjectMemoryRevision::status));
        List<ProjectMemoryAdminRevisionView> revisions = memoryStore.listRevisions(memoryId).stream()
                .sorted(Comparator.comparingLong(ProjectMemoryRevision::version))
                .map(revision -> new ProjectMemoryAdminRevisionView(
                        revision.revisionId(),
                        revision.version(),
                        revision.status(),
                        revision.title(),
                        revision.summary(),
                        revision.contentHash(),
                        revision.rowVersion(),
                        revision.revisionId().equals(memory.headRevisionId())
                ))
                .toList();
        List<ProjectMemoryAdminSourceView> sources = memoryStore.listRevisions(memoryId).stream()
                .flatMap(revision -> memoryStore.listSources(revision.revisionId()).stream())
                .sorted(Comparator.comparing(ProjectMemorySource::sourceId))
                .map(this::toSourceView)
                .toList();
        List<ProjectMemoryRetrievalAuditView> audits = queryPort.listRecentRetrievalAudits(memoryId, 20).stream()
                .map(entry -> new ProjectMemoryRetrievalAuditView(
                        entry.memoryId(),
                        entry.revisionId(),
                        entry.revisionVersion(),
                        entry.querySummary(),
                        entry.examinedRowCount(),
                        entry.observedAtEpochMillis()
                ))
                .toList();
        ProjectMemoryRevisionStatus headStatus = statuses.getOrDefault(
                memory.headRevisionId(), ProjectMemoryRevisionStatus.CANDIDATE);
        return new ProjectMemoryAdminDetailView(
                memory.memoryId(),
                memory.projectId(),
                memory.scopeRole(),
                memory.memoryType(),
                memory.logicalKey(),
                memory.rowVersion(),
                memory.headRevisionId(),
                memory.headVersion(),
                memory.deleted(),
                revisions,
                sources,
                audits
        );
    }

    private ProjectMemoryAdminSummaryView toSummary(ProjectMemory memory) {
        ProjectMemoryRevisionStatus headStatus = memory.headRevisionId().isBlank()
                ? ProjectMemoryRevisionStatus.CANDIDATE
                : memoryStore.listRevisions(memory.memoryId()).stream()
                        .filter(revision -> revision.revisionId().equals(memory.headRevisionId()))
                        .findFirst()
                        .map(ProjectMemoryRevision::status)
                        .orElse(ProjectMemoryRevisionStatus.CANDIDATE);
        return new ProjectMemoryAdminSummaryView(
                memory.memoryId(),
                memory.projectId(),
                memory.scopeRole(),
                memory.memoryType(),
                memory.logicalKey(),
                memory.rowVersion(),
                memory.headRevisionId(),
                memory.headVersion(),
                headStatus,
                memory.deleted()
        );
    }

    private ProjectMemoryAdminSourceView toSourceView(ProjectMemorySource source) {
        boolean referencesAvailable = !source.taskId().isBlank()
                || !source.stageRunId().isBlank()
                || !source.artifactId().isBlank();
        return new ProjectMemoryAdminSourceView(
                source.sourceId(),
                source.revisionId(),
                source.projectId(),
                source.taskId(),
                source.stageRunId(),
                source.artifactId(),
                source.sourceUri(),
                source.sourceContentHash(),
                source.repositoryRevision(),
                source.extractorVersion(),
                source.schemaVersion(),
                source.redactedSummary(),
                referencesAvailable
        );
    }
}
