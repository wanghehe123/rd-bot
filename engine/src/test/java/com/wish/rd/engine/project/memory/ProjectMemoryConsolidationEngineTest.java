package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.project.memory.model.ProjectMemoryCandidate;
import com.wish.rd.engine.project.memory.model.ProjectMemoryConsolidationRequest;
import com.wish.rd.engine.project.memory.model.ProjectMemoryConsolidationResult;
import com.wish.rd.rag.project.memory.ProjectMemoryStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryConsolidationEngineTest {

    private static final String PROJECT = "101";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void addCreatesFirstActiveHeadForNewLogicalIdentity() {
        InMemoryProjectMemoryStore store = new InMemoryProjectMemoryStore();
        ProjectMemoryConsolidationEngine engine = engine(store);
        ProjectMemoryCandidate candidate = candidate("build-command", "Run tests", "npm test");
        ProjectMemoryConsolidationResult result = engine.consolidate(request(
                store, "memory-1", "revision-1", "source-1", candidate, HASH_A, false));

        assertEquals(ProjectMemoryResolver.Action.ADD, result.action());
        ProjectMemory memory = store.find("memory-1").orElseThrow();
        assertEquals("revision-1", memory.headRevisionId());
        assertEquals(ProjectMemoryRevisionStatus.ACTIVE,
                store.listRevisions(memory.memoryId()).getFirst().status());
        assertEquals(1, store.listSources("revision-1").size());
    }

    @Test
    void noopAddsEquivalentSourceWithoutCreatingDuplicateRevision() {
        InMemoryProjectMemoryStore store = seededActiveHead("memory-1", "revision-1", "build-command");
        ProjectMemoryConsolidationEngine engine = engine(store);
        ProjectMemoryCandidate equivalent = candidate("build-command", "Run tests", "npm test");

        ProjectMemoryConsolidationResult first = engine.consolidate(request(
                store, "memory-1", "revision-2", "source-2", equivalent, HASH_B, false));
        ProjectMemoryConsolidationResult second = engine.consolidate(request(
                store, "memory-1", "revision-3", "source-3", equivalent, "c".repeat(64), false));

        assertEquals(ProjectMemoryResolver.Action.NOOP, first.action());
        assertEquals(ProjectMemoryResolver.Action.NOOP, second.action());
        assertEquals(1, store.listRevisions("memory-1").size());
        assertEquals(3, store.listSources("revision-1").size());
    }

    @Test
    void supersedesActiveHeadWhenContentChanges() {
        InMemoryProjectMemoryStore store = seededActiveHead("memory-1", "revision-1", "build-command");
        ProjectMemoryConsolidationEngine engine = engine(store);
        ProjectMemoryCandidate updated = candidate("build-command", "Run tests", "pnpm test");

        ProjectMemoryConsolidationResult result = engine.consolidate(request(
                store, "memory-1", "revision-2", "source-2", updated, HASH_B, false));

        assertEquals(ProjectMemoryResolver.Action.SUPERSEDE, result.action());
        assertEquals("revision-2", store.find("memory-1").orElseThrow().headRevisionId());
        assertEquals(ProjectMemoryRevisionStatus.SUPERSEDED,
                revision(store, "revision-1").status());
        assertEquals(ProjectMemoryRevisionStatus.ACTIVE,
                revision(store, "revision-2").status());
    }

    @Test
    void quarantinesIrreconcilableConflictWithoutReplacingActiveHead() {
        InMemoryProjectMemoryStore store = seededActiveHead("memory-1", "revision-1", "build-command");
        ProjectMemoryConsolidationEngine engine = engine(store);
        ProjectMemoryCandidate conflicting = candidate("build-command", "Run tests", "yarn test");

        ProjectMemoryConsolidationResult result = engine.consolidate(request(
                store, "memory-1", "revision-q", "source-q", conflicting, HASH_B, true));

        assertEquals(ProjectMemoryResolver.Action.QUARANTINE, result.action());
        assertEquals("revision-1", store.find("memory-1").orElseThrow().headRevisionId());
        assertEquals(ProjectMemoryRevisionStatus.QUARANTINED,
                revision(store, "revision-q").status());
        assertEquals(List.of(revision(store, "revision-1")),
                store.listRetrievable(PROJECT, "CODING_AGENT"));
    }

    @Test
    void retriesOnceAfterCasFailureThenSupersedesOnSecondRead() {
        InMemoryProjectMemoryStore delegate = seededActiveHead("memory-1", "revision-1", "build-command");
        CasFailOnceStore store = new CasFailOnceStore(delegate);
        ProjectMemoryConsolidationEngine engine = engine(store);
        ProjectMemoryCandidate updated = candidate("build-command", "Run tests", "pnpm test");

        ProjectMemoryConsolidationResult result = engine.consolidate(request(
                store, "memory-1", "revision-2", "source-2", updated, HASH_B, false));

        assertEquals(ProjectMemoryResolver.Action.SUPERSEDE, result.action());
        assertEquals("revision-2", delegate.find("memory-1").orElseThrow().headRevisionId());
    }

    @Test
    void quarantinesWhenCasConflictPersistsAfterOneRetry() {
        InMemoryProjectMemoryStore delegate = seededActiveHead("memory-1", "revision-1", "build-command");
        CasAlwaysFailStore store = new CasAlwaysFailStore(delegate);
        ProjectMemoryConsolidationEngine engine = engine(store);
        ProjectMemoryCandidate updated = candidate("build-command", "Run tests", "pnpm test");

        ProjectMemoryConsolidationResult result = engine.consolidate(request(
                store, "memory-1", "revision-2", "source-2", updated, HASH_B, false));

        assertEquals(ProjectMemoryResolver.Action.QUARANTINE, result.action());
        assertEquals("revision-1", delegate.find("memory-1").orElseThrow().headRevisionId());
    }

    @Test
    void crashReplayDoesNotDuplicateRevision() {
        InMemoryProjectMemoryStore store = new InMemoryProjectMemoryStore();
        ProjectMemoryConsolidationEngine engine = engine(store);
        ProjectMemoryConsolidationRequest request = request(
                store, "memory-1", "revision-1", "source-1",
                candidate("build-command", "Run tests", "npm test"), HASH_A, false);

        ProjectMemoryConsolidationResult first = engine.consolidate(request);
        ProjectMemoryConsolidationResult replay = engine.consolidate(request);

        assertFalse(first.replayedExistingRevision());
        assertTrue(replay.replayedExistingRevision());
        assertEquals(1, store.listRevisions("memory-1").size());
        assertEquals(1, store.listSources("revision-1").size());
    }

    private static ProjectMemoryConsolidationEngine engine(ProjectMemoryStore store) {
        return new ProjectMemoryConsolidationEngine(store, new ProjectMemoryResolver(), new ProjectMemoryPromotionPolicy());
    }

    private static InMemoryProjectMemoryStore seededActiveHead(
            String memoryId, String revisionId, String logicalKey
    ) {
        InMemoryProjectMemoryStore store = new InMemoryProjectMemoryStore();
        ProjectMemoryCandidate seedCandidate = candidate(logicalKey, "Run tests", "npm test");
        String contentHash = ProjectMemoryContentHasher.sha256(seedCandidate);
        ProjectMemory memory = store.create(new ProjectMemory(
                memoryId, PROJECT, "CODING_AGENT", ProjectMemoryType.PROCEDURAL, logicalKey, 1L));
        ProjectMemoryRevision revision = new ProjectMemoryRevision(
                revisionId, memory.memoryId(), 1L, ProjectMemoryRevisionStatus.ACTIVE,
                seedCandidate.title(), seedCandidate.summary(), "{}", contentHash, "schema-1", "", 1L, 1L);
        store.addRevision(revision);
        store.advanceHead(memory.memoryId(), revision.revisionId(), 1L);
        store.addSource(new com.wish.rd.rag.project.memory.model.ProjectMemorySource(
                "seed-source", revisionId, PROJECT, "task-1", "stage-1", "artifact-1",
                "rd-artifact://artifact-1", HASH_A, "abc", "extractor-1", "schema-1", "npm test"));
        return store;
    }

    private static ProjectMemoryRevision revision(InMemoryProjectMemoryStore store, String revisionId) {
        return store.listRevisions("memory-1").stream()
                .filter(revision -> revision.revisionId().equals(revisionId))
                .findFirst()
                .orElseThrow();
    }

    private static ProjectMemoryCandidate candidate(String logicalKey, String title, String summary) {
        return new ProjectMemoryCandidate(PROJECT, "artifact-1", "PROCEDURAL", logicalKey, title, summary, "schema-1");
    }

    private static ProjectMemoryConsolidationRequest request(
            ProjectMemoryStore store,
            String memoryId,
            String revisionId,
            String sourceId,
            ProjectMemoryCandidate candidate,
            String sourceHash,
            boolean conflict
    ) {
        return new ProjectMemoryConsolidationRequest(
                memoryId, revisionId, sourceId, "CODING_AGENT", candidate,
                new ProjectMemoryCaptureEvidence(PROJECT, "artifact-1", sourceHash, "extractor-1"),
                new ProjectMemoryPromotionEvidence(true, true, false, false),
                "rd-artifact://artifact-1", "task-1", "stage-1", "abc", 1L, conflict);
    }

    private static class CasFailOnceStore implements ProjectMemoryStore {
        private final InMemoryProjectMemoryStore delegate;
        private boolean failNextAdvance;

        private CasFailOnceStore(InMemoryProjectMemoryStore delegate) {
            this.delegate = delegate;
        }

        @Override public ProjectMemory create(ProjectMemory memory) { return delegate.create(memory); }
        @Override public java.util.Optional<ProjectMemory> find(String memoryId) { return delegate.find(memoryId); }
        @Override public java.util.Optional<ProjectMemory> findByLogicalKey(
                String projectId, String scopeRole, ProjectMemoryType memoryType, String logicalKey
        ) { return delegate.findByLogicalKey(projectId, scopeRole, memoryType, logicalKey); }
        @Override public void addRevision(ProjectMemoryRevision revision) { delegate.addRevision(revision); }
        @Override public void advanceHead(String memoryId, String revisionId, long expectedRowVersion) {
            if (failNextAdvance) {
                failNextAdvance = false;
                throw new IllegalStateException("project memory row version conflict: " + memoryId);
            }
            delegate.advanceHead(memoryId, revisionId, expectedRowVersion);
        }
        @Override public void supersedeRevision(String revisionId) { delegate.supersedeRevision(revisionId); }
        @Override public List<ProjectMemoryRevision> listRevisions(String memoryId) { return delegate.listRevisions(memoryId); }
        @Override public List<ProjectMemory> listByProject(String projectId) { return delegate.listByProject(projectId); }
        @Override public List<ProjectMemoryRevision> listRetrievable(String projectId, String role) {
            return delegate.listRetrievable(projectId, role);
        }
        @Override public void addSource(com.wish.rd.rag.project.memory.model.ProjectMemorySource source) {
            delegate.addSource(source);
        }
        @Override public List<com.wish.rd.rag.project.memory.model.ProjectMemorySource> listSources(String revisionId) {
            return delegate.listSources(revisionId);
        }
        @Override public boolean replaceRevision(ProjectMemoryRevision revision, long expectedRowVersion) {
            return delegate.replaceRevision(revision, expectedRowVersion);
        }
        @Override public boolean replaceMemory(ProjectMemory memory, long expectedRowVersion) {
            return delegate.replaceMemory(memory, expectedRowVersion);
        }
    }

    private static final class CasAlwaysFailStore extends CasFailOnceStore {
        private CasAlwaysFailStore(InMemoryProjectMemoryStore delegate) {
            super(delegate);
        }

        @Override
        public void advanceHead(String memoryId, String revisionId, long expectedRowVersion) {
            throw new IllegalStateException("project memory row version conflict: " + memoryId);
        }
    }
}
