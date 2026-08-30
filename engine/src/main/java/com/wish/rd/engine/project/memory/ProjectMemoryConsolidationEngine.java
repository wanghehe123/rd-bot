package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.project.memory.model.ProjectMemoryCandidate;
import com.wish.rd.engine.project.memory.model.ProjectMemoryConsolidationRequest;
import com.wish.rd.engine.project.memory.model.ProjectMemoryConsolidationResult;
import com.wish.rd.rag.project.memory.ProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;

import java.util.Objects;
import java.util.Optional;

/** Applies resolver decisions with single CAS retry and deterministic replay safety. */
public final class ProjectMemoryConsolidationEngine {
    private final ProjectMemoryStore store;
    private final ProjectMemoryResolver resolver;
    private final ProjectMemoryPromotionPolicy promotionPolicy;

    public ProjectMemoryConsolidationEngine(
            ProjectMemoryStore store,
            ProjectMemoryResolver resolver,
            ProjectMemoryPromotionPolicy promotionPolicy
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.promotionPolicy = Objects.requireNonNull(promotionPolicy, "promotionPolicy must not be null");
    }

    public ProjectMemoryConsolidationResult consolidate(ProjectMemoryConsolidationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ProjectMemoryCandidate candidate = Objects.requireNonNull(request.candidate(), "candidate must not be null");
        String contentHash = ProjectMemoryContentHasher.sha256(candidate);
        Optional<ProjectMemory> existingMemory = store.findByLogicalKey(
                candidate.projectId(), request.scopeRole(), candidate.memoryType(), candidate.logicalKey());
        if (existingMemory.isPresent() && revisionExists(existingMemory.get().memoryId(), request.revisionId())) {
            ProjectMemory memory = existingMemory.get();
            if (request.revisionId().equals(memory.headRevisionId())) {
                return new ProjectMemoryConsolidationResult(
                        ProjectMemoryResolver.Action.ADD, memory.memoryId(),
                        request.revisionId(), request.sourceId(), true);
            }
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<ProjectMemory> memory = store.findByLogicalKey(
                    candidate.projectId(), request.scopeRole(), candidate.memoryType(), candidate.logicalKey());
            if (memory.isPresent() && revisionExists(memory.get().memoryId(), request.revisionId())) {
                ProjectMemory aggregate = memory.get();
                if (request.revisionId().equals(aggregate.headRevisionId())) {
                    return new ProjectMemoryConsolidationResult(
                            ProjectMemoryResolver.Action.ADD, aggregate.memoryId(),
                            request.revisionId(), request.sourceId(), true);
                }
                if (attempt > 0) {
                    return quarantine(request, candidate, contentHash, aggregate);
                }
            }
            Optional<ProjectMemoryRevision> head = memory.flatMap(value -> headRevision(value));
            ProjectMemoryResolver.Action action = resolver.resolve(
                    memory, head, contentHash, request.irreconcilableConflict());
            try {
                return switch (action) {
                    case ADD -> add(request, candidate, contentHash, memory, head);
                    case NOOP -> noop(request, candidate, memory.orElseThrow(), head.orElseThrow());
                    case SUPERSEDE -> supersede(request, candidate, contentHash, memory.orElseThrow(), head.orElseThrow());
                    case QUARANTINE -> quarantine(request, candidate, contentHash, memory.orElseThrow());
                };
            } catch (IllegalStateException conflict) {
                if (attempt == 0 && conflict.getMessage() != null
                        && conflict.getMessage().contains("row version conflict")) {
                    continue;
                }
                if (attempt == 0) {
                    return quarantine(request, candidate, contentHash, memory.orElseGet(() -> ensureMemory(request, candidate)));
                }
                throw conflict;
            }
        }
        throw new IllegalStateException("consolidation exhausted CAS retries");
    }

    private boolean revisionExists(String memoryId, String revisionId) {
        return store.listRevisions(memoryId).stream().anyMatch(revision -> revision.revisionId().equals(revisionId));
    }

    private ProjectMemoryConsolidationResult add(
            ProjectMemoryConsolidationRequest request,
            ProjectMemoryCandidate candidate,
            String contentHash,
            Optional<ProjectMemory> memory,
            Optional<ProjectMemoryRevision> head
    ) {
        ProjectMemory aggregate = memory.orElseGet(() -> ensureMemory(request, candidate));
        if (revisionExists(aggregate.memoryId(), request.revisionId())) {
            return new ProjectMemoryConsolidationResult(
                    ProjectMemoryResolver.Action.ADD, aggregate.memoryId(), request.revisionId(), request.sourceId(), true);
        }
        ProjectMemoryRevisionStatus status = promotionPolicy.statusFor(candidate.memoryType(), request.promotionEvidence());
        long version = head.map(ProjectMemoryRevision::version).orElse(0L) + 1L;
        ProjectMemoryRevision revision = new ProjectMemoryRevision(
                request.revisionId(), aggregate.memoryId(), version, status,
                candidate.title(), candidate.summary(), "{}", contentHash, candidate.schemaVersion(),
                head.map(ProjectMemoryRevision::revisionId).orElse(""), request.createdAtEpochMillis(), 1L);
        ProjectMemorySource source = source(request, candidate, revision.revisionId());
        persistRevisionAndSource(revision, source);
        if (status == ProjectMemoryRevisionStatus.ACTIVE) {
            store.advanceHead(aggregate.memoryId(), revision.revisionId(), aggregate.rowVersion());
        }
        return new ProjectMemoryConsolidationResult(
                ProjectMemoryResolver.Action.ADD, aggregate.memoryId(), revision.revisionId(), source.sourceId(), false);
    }

    private ProjectMemoryConsolidationResult noop(
            ProjectMemoryConsolidationRequest request,
            ProjectMemoryCandidate candidate,
            ProjectMemory memory,
            ProjectMemoryRevision head
    ) {
        if (store.listSources(head.revisionId()).stream()
                .anyMatch(source -> source.sourceContentHash().equalsIgnoreCase(request.captureEvidence().sourceContentHash()))) {
            return new ProjectMemoryConsolidationResult(
                    ProjectMemoryResolver.Action.NOOP, memory.memoryId(), head.revisionId(), "", false);
        }
        ProjectMemorySource source = source(request, candidate, head.revisionId());
        store.addSource(source);
        return new ProjectMemoryConsolidationResult(
                ProjectMemoryResolver.Action.NOOP, memory.memoryId(), head.revisionId(), source.sourceId(), false);
    }

    private ProjectMemoryConsolidationResult supersede(
            ProjectMemoryConsolidationRequest request,
            ProjectMemoryCandidate candidate,
            String contentHash,
            ProjectMemory memory,
            ProjectMemoryRevision head
    ) {
        if (revisionExists(memory.memoryId(), request.revisionId())) {
            return new ProjectMemoryConsolidationResult(
                    ProjectMemoryResolver.Action.SUPERSEDE, memory.memoryId(), request.revisionId(), request.sourceId(), true);
        }
        ProjectMemoryRevisionStatus status = promotionPolicy.statusFor(candidate.memoryType(), request.promotionEvidence());
        ProjectMemoryRevision revision = new ProjectMemoryRevision(
                request.revisionId(), memory.memoryId(), head.version() + 1L, status,
                candidate.title(), candidate.summary(), "{}", contentHash, candidate.schemaVersion(),
                head.revisionId(), request.createdAtEpochMillis(), 1L);
        ProjectMemorySource source = source(request, candidate, revision.revisionId());
        persistRevisionAndSource(revision, source);
        if (status == ProjectMemoryRevisionStatus.ACTIVE) {
            store.supersedeRevision(head.revisionId());
            store.advanceHead(memory.memoryId(), revision.revisionId(), memory.rowVersion());
        }
        return new ProjectMemoryConsolidationResult(
                ProjectMemoryResolver.Action.SUPERSEDE, memory.memoryId(), revision.revisionId(), source.sourceId(), false);
    }

    private ProjectMemoryConsolidationResult quarantine(
            ProjectMemoryConsolidationRequest request,
            ProjectMemoryCandidate candidate,
            String contentHash,
            ProjectMemory memory
    ) {
        if (revisionExists(memory.memoryId(), request.revisionId())) {
            return new ProjectMemoryConsolidationResult(
                    ProjectMemoryResolver.Action.QUARANTINE, memory.memoryId(), request.revisionId(), request.sourceId(), true);
        }
        ProjectMemoryRevision revision = new ProjectMemoryRevision(
                request.revisionId(), memory.memoryId(), nextVersion(memory), ProjectMemoryRevisionStatus.QUARANTINED,
                candidate.title(), candidate.summary(), "{}", contentHash, candidate.schemaVersion(),
                memory.headRevisionId(), request.createdAtEpochMillis(), 1L);
        ProjectMemorySource source = source(request, candidate, revision.revisionId());
        persistRevisionAndSource(revision, source);
        return new ProjectMemoryConsolidationResult(
                ProjectMemoryResolver.Action.QUARANTINE, memory.memoryId(), revision.revisionId(), source.sourceId(), false);
    }

    private void persistRevisionAndSource(ProjectMemoryRevision revision, ProjectMemorySource source) {
        try {
            store.addRevision(revision);
            store.addSource(source);
        } catch (IllegalStateException duplicate) {
            if (duplicate.getMessage() != null && duplicate.getMessage().contains("already exists")) {
                throw duplicate;
            }
            throw duplicate;
        }
    }

    private ProjectMemory ensureMemory(ProjectMemoryConsolidationRequest request, ProjectMemoryCandidate candidate) {
        if (request.memoryId().isBlank()) {
            throw new IllegalArgumentException("memoryId is required for a new logical identity");
        }
        return store.create(new ProjectMemory(
                request.memoryId(), candidate.projectId(), request.scopeRole(),
                candidate.memoryType(), candidate.logicalKey(), 1L));
    }

    private Optional<ProjectMemoryRevision> headRevision(ProjectMemory memory) {
        if (memory.headRevisionId().isBlank()) {
            return Optional.empty();
        }
        return store.listRevisions(memory.memoryId()).stream()
                .filter(revision -> revision.revisionId().equals(memory.headRevisionId()))
                .findFirst();
    }

    private long nextVersion(ProjectMemory memory) {
        return store.listRevisions(memory.memoryId()).stream()
                .mapToLong(ProjectMemoryRevision::version)
                .max()
                .orElse(0L) + 1L;
    }

    private static ProjectMemorySource source(
            ProjectMemoryConsolidationRequest request,
            ProjectMemoryCandidate candidate,
            String revisionId
    ) {
        return new ProjectMemorySource(
                request.sourceId(), revisionId, candidate.projectId(), request.taskId(), request.stageRunId(),
                candidate.sourceArtifactId(), request.sourceUri(), request.captureEvidence().sourceContentHash(),
                request.repositoryRevision(), request.captureEvidence().extractorVersion(),
                candidate.schemaVersion(), candidate.summary());
    }
}
