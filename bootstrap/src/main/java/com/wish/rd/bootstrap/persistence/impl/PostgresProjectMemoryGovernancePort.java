package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.rag.project.memory.ProjectMemoryGovernanceConflictException;
import com.wish.rd.rag.project.memory.ProjectMemoryGovernancePort;
import com.wish.rd.rag.project.memory.ProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryGovernanceResult;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL governance adapter with CAS row-version checks. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresProjectMemoryGovernancePort implements ProjectMemoryGovernancePort {

    private final ProjectMemoryStore memoryStore;

    public PostgresProjectMemoryGovernancePort(ProjectMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    @Transactional
    public ProjectMemoryGovernanceResult confirm(ProjectMemoryConfirmCommand command) {
        ProjectMemory memory = requireMemory(command.projectId(), command.memoryId(), command.expectedMemoryRowVersion());
        ProjectMemoryRevision revision = requireRevision(
                memory, command.revisionId(), command.expectedRevisionRowVersion());
        if (revision.status() != ProjectMemoryRevisionStatus.CANDIDATE) {
            throw new IllegalArgumentException("only CANDIDATE revisions may be confirmed: " + revision.revisionId());
        }
        ProjectMemoryRevision active = revision.withStatus(ProjectMemoryRevisionStatus.ACTIVE);
        if (!memoryStore.replaceRevision(active, command.expectedRevisionRowVersion())) {
            throw conflict("revision", revision.revisionId());
        }
        memoryStore.advanceHead(memory.memoryId(), active.revisionId(), command.expectedMemoryRowVersion());
        return result(memory.memoryId(), active.revisionId());
    }

    @Override
    @Transactional
    public ProjectMemoryGovernanceResult correct(ProjectMemoryCorrectCommand command) {
        ProjectMemory memory = requireMemory(command.projectId(), command.memoryId(), command.expectedMemoryRowVersion());
        ProjectMemoryRevision current = requireRevision(
                memory, command.revisionId(), command.expectedRevisionRowVersion());
        if (current.status() != ProjectMemoryRevisionStatus.ACTIVE) {
            throw new IllegalArgumentException("only ACTIVE revisions may be corrected: " + current.revisionId());
        }
        long nextVersion = memoryStore.listRevisions(memory.memoryId()).stream()
                .mapToLong(ProjectMemoryRevision::version)
                .max()
                .orElse(0L) + 1L;
        ProjectMemoryRevision replacement = new ProjectMemoryRevision(
                command.newRevisionId(),
                memory.memoryId(),
                nextVersion,
                ProjectMemoryRevisionStatus.ACTIVE,
                command.correctedTitle(),
                command.correctedSummary(),
                command.correctedContentJson(),
                command.correctedContentHash(),
                current.schemaVersion(),
                current.revisionId(),
                System.currentTimeMillis(),
                1L
        );
        memoryStore.supersedeRevision(current.revisionId());
        memoryStore.addRevision(replacement);
        memoryStore.advanceHead(memory.memoryId(), replacement.revisionId(), memory.rowVersion());
        return result(memory.memoryId(), replacement.revisionId());
    }

    @Override
    @Transactional
    public ProjectMemoryGovernanceResult invalidate(ProjectMemoryInvalidateCommand command) {
        ProjectMemory memory = requireMemory(command.projectId(), command.memoryId(), command.expectedMemoryRowVersion());
        ProjectMemoryRevision revision = requireRevision(
                memory, command.revisionId(), command.expectedRevisionRowVersion());
        if (revision.status() == ProjectMemoryRevisionStatus.DELETED
                || revision.status() == ProjectMemoryRevisionStatus.SUPERSEDED) {
            throw new IllegalArgumentException("revision is not invalidatable: " + revision.revisionId());
        }
        ProjectMemoryRevision expired = revision.withStatus(ProjectMemoryRevisionStatus.EXPIRED);
        if (!memoryStore.replaceRevision(expired, command.expectedRevisionRowVersion())) {
            throw conflict("revision", revision.revisionId());
        }
        return result(memory.memoryId(), expired.revisionId());
    }

    @Override
    @Transactional
    public ProjectMemoryGovernanceResult softDelete(ProjectMemorySoftDeleteCommand command) {
        ProjectMemory memory = requireMemory(command.projectId(), command.memoryId(), command.expectedMemoryRowVersion());
        if (memory.deleted()) {
            throw new IllegalArgumentException("memory already deleted: " + memory.memoryId());
        }
        ProjectMemory deleted = memory.withDeleted();
        if (!memoryStore.replaceMemory(deleted, command.expectedMemoryRowVersion())) {
            throw conflict("memory", memory.memoryId());
        }
        if (!memory.headRevisionId().isBlank()) {
            ProjectMemoryRevision head = memoryStore.listRevisions(memory.memoryId()).stream()
                    .filter(revision -> revision.revisionId().equals(memory.headRevisionId()))
                    .findFirst()
                    .orElseThrow();
            ProjectMemoryRevision tombstone = head.withStatus(ProjectMemoryRevisionStatus.DELETED);
            if (!memoryStore.replaceRevision(tombstone, head.rowVersion())) {
                throw conflict("revision", head.revisionId());
            }
        }
        return result(memory.memoryId(), memory.headRevisionId());
    }

    private ProjectMemory requireMemory(String projectId, String memoryId, long expectedRowVersion) {
        ProjectMemory memory = loadMemory(memoryId)
                .orElseThrow(() -> new IllegalArgumentException("unknown memory: " + memoryId));
        if (!memory.projectId().equals(projectId)) {
            throw new IllegalArgumentException("memory does not belong to project: " + projectId);
        }
        if (memory.rowVersion() != expectedRowVersion) {
            throw conflict("memory", memoryId);
        }
        return memory;
    }

    private ProjectMemoryRevision requireRevision(
            ProjectMemory memory,
            String revisionId,
            long expectedRowVersion
    ) {
        ProjectMemoryRevision revision = memoryStore.listRevisions(memory.memoryId()).stream()
                .filter(item -> item.revisionId().equals(revisionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown revision: " + revisionId));
        if (revision.rowVersion() != expectedRowVersion) {
            throw conflict("revision", revisionId);
        }
        return revision;
    }

    private ProjectMemoryGovernanceResult result(String memoryId, String revisionId) {
        ProjectMemory updated = loadMemory(memoryId).orElseThrow();
        ProjectMemoryRevision committed = memoryStore.listRevisions(memoryId).stream()
                .filter(revision -> revision.revisionId().equals(revisionId))
                .findFirst()
                .orElseThrow();
        return new ProjectMemoryGovernanceResult(
                updated.memoryId(),
                committed.revisionId(),
                updated.rowVersion(),
                committed.rowVersion(),
                committed.status(),
                updated.deleted()
        );
    }

    private java.util.Optional<ProjectMemory> loadMemory(String memoryId) {
        if (memoryStore instanceof PostgresProjectMemoryStore postgresStore) {
            return postgresStore.findIncludingDeleted(memoryId);
        }
        return memoryStore.find(memoryId);
    }

    private static ProjectMemoryGovernanceConflictException conflict(String entity, String id) {
        return new ProjectMemoryGovernanceConflictException(entity + " row version conflict: " + id);
    }
}
