package com.wish.rd.rag.project.memory.impl;

import com.wish.rd.rag.project.memory.ProjectMemoryGovernanceConflictException;
import com.wish.rd.rag.project.memory.ProjectMemoryGovernancePort;
import com.wish.rd.rag.project.memory.ProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryGovernanceResult;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;

/** Contract-only governance adapter with CAS row-version checks. */
public final class InMemoryProjectMemoryGovernancePort implements ProjectMemoryGovernancePort {

    private final ProjectMemoryStore store;

    public InMemoryProjectMemoryGovernancePort(ProjectMemoryStore store) {
        this.store = store;
    }

    @Override
    public ProjectMemoryGovernanceResult confirm(ProjectMemoryConfirmCommand command) {
        ProjectMemory memory = requireMemory(command.projectId(), command.memoryId(), command.expectedMemoryRowVersion());
        ProjectMemoryRevision revision = requireRevision(
                memory, command.revisionId(), command.expectedRevisionRowVersion());
        if (revision.status() != ProjectMemoryRevisionStatus.CANDIDATE) {
            throw new IllegalArgumentException("only CANDIDATE revisions may be confirmed: " + revision.revisionId());
        }
        ProjectMemoryRevision active = revision.withStatus(ProjectMemoryRevisionStatus.ACTIVE);
        if (!store.replaceRevision(active, command.expectedRevisionRowVersion())) {
            throw conflict("revision", revision.revisionId());
        }
        store.advanceHead(memory.memoryId(), active.revisionId(), command.expectedMemoryRowVersion());
        ProjectMemory updated = store.find(memory.memoryId()).orElseThrow();
        ProjectMemoryRevision committed = store.listRevisions(memory.memoryId()).stream()
                .filter(item -> item.revisionId().equals(active.revisionId()))
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

    @Override
    public ProjectMemoryGovernanceResult correct(ProjectMemoryCorrectCommand command) {
        ProjectMemory memory = requireMemory(command.projectId(), command.memoryId(), command.expectedMemoryRowVersion());
        ProjectMemoryRevision current = requireRevision(
                memory, command.revisionId(), command.expectedRevisionRowVersion());
        if (current.status() != ProjectMemoryRevisionStatus.ACTIVE) {
            throw new IllegalArgumentException("only ACTIVE revisions may be corrected: " + current.revisionId());
        }
        long nextVersion = store.listRevisions(memory.memoryId()).stream()
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
        store.supersedeRevision(current.revisionId());
        store.addRevision(replacement);
        store.advanceHead(memory.memoryId(), replacement.revisionId(), memory.rowVersion());
        ProjectMemory updated = store.find(memory.memoryId()).orElseThrow();
        return new ProjectMemoryGovernanceResult(
                updated.memoryId(),
                replacement.revisionId(),
                updated.rowVersion(),
                replacement.rowVersion(),
                replacement.status(),
                updated.deleted()
        );
    }

    @Override
    public ProjectMemoryGovernanceResult invalidate(ProjectMemoryInvalidateCommand command) {
        ProjectMemory memory = requireMemory(command.projectId(), command.memoryId(), command.expectedMemoryRowVersion());
        ProjectMemoryRevision revision = requireRevision(
                memory, command.revisionId(), command.expectedRevisionRowVersion());
        if (revision.status() == ProjectMemoryRevisionStatus.DELETED
                || revision.status() == ProjectMemoryRevisionStatus.SUPERSEDED) {
            throw new IllegalArgumentException("revision is not invalidatable: " + revision.revisionId());
        }
        ProjectMemoryRevision expired = revision.withStatus(ProjectMemoryRevisionStatus.EXPIRED);
        if (!store.replaceRevision(expired, command.expectedRevisionRowVersion())) {
            throw conflict("revision", revision.revisionId());
        }
        ProjectMemory updated = store.find(memory.memoryId()).orElseThrow();
        return new ProjectMemoryGovernanceResult(
                updated.memoryId(),
                expired.revisionId(),
                updated.rowVersion(),
                expired.rowVersion(),
                expired.status(),
                updated.deleted()
        );
    }

    @Override
    public ProjectMemoryGovernanceResult softDelete(ProjectMemorySoftDeleteCommand command) {
        ProjectMemory memory = requireMemory(command.projectId(), command.memoryId(), command.expectedMemoryRowVersion());
        if (memory.deleted()) {
            throw new IllegalArgumentException("memory already deleted: " + memory.memoryId());
        }
        ProjectMemory deleted = memory.withDeleted();
        if (!store.replaceMemory(deleted, command.expectedMemoryRowVersion())) {
            throw conflict("memory", memory.memoryId());
        }
        if (!memory.headRevisionId().isBlank()) {
            ProjectMemoryRevision head = store.listRevisions(memory.memoryId()).stream()
                    .filter(revision -> revision.revisionId().equals(memory.headRevisionId()))
                    .findFirst()
                    .orElseThrow();
            ProjectMemoryRevision tombstone = head.withStatus(ProjectMemoryRevisionStatus.DELETED);
            if (!store.replaceRevision(tombstone, head.rowVersion())) {
                throw conflict("revision", head.revisionId());
            }
        }
        ProjectMemory updated = store.find(memory.memoryId()).orElseThrow();
        String revisionId = updated.headRevisionId();
        long revisionRowVersion = 1L;
        ProjectMemoryRevisionStatus status = ProjectMemoryRevisionStatus.DELETED;
        if (!revisionId.isBlank()) {
            ProjectMemoryRevision committed = store.listRevisions(memory.memoryId()).stream()
                    .filter(revision -> revision.revisionId().equals(revisionId))
                    .findFirst()
                    .orElseThrow();
            revisionRowVersion = committed.rowVersion();
            status = committed.status();
        }
        return new ProjectMemoryGovernanceResult(
                updated.memoryId(),
                revisionId,
                updated.rowVersion(),
                revisionRowVersion,
                status,
                true
        );
    }

    private ProjectMemory requireMemory(String projectId, String memoryId, long expectedRowVersion) {
        ProjectMemory memory = store.find(memoryId)
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
        ProjectMemoryRevision revision = store.listRevisions(memory.memoryId()).stream()
                .filter(item -> item.revisionId().equals(revisionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown revision: " + revisionId));
        if (revision.rowVersion() != expectedRowVersion) {
            throw conflict("revision", revisionId);
        }
        return revision;
    }

    private static ProjectMemoryGovernanceConflictException conflict(String entity, String id) {
        return new ProjectMemoryGovernanceConflictException(entity + " row version conflict: " + id);
    }
}
