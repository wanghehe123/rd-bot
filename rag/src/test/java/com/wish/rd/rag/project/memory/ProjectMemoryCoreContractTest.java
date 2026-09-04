package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryOperationStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperation;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationKey;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryCoreContractTest {

    @Test
    void rejectsMissingProjectInsteadOfFallingBackToRepositoryIdentity() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectMemory("memory-1", " ", "", ProjectMemoryType.SEMANTIC, "subject", 1L)
        );

        assertEquals("projectId must be a non-empty numeric identifier", error.getMessage());
    }

    @Test
    void keepsQuarantinedContenderBesideCurrentActiveHeadWithoutMakingItRetrievable() {
        InMemoryProjectMemoryStore store = new InMemoryProjectMemoryStore();
        ProjectMemory memory = store.create(new ProjectMemory(
                "memory-1", "101", "CODING_AGENT", ProjectMemoryType.PROCEDURAL, "build-command", 1L
        ));
        ProjectMemoryRevision active = revision("revision-1", memory.memoryId(), 1L, ProjectMemoryRevisionStatus.ACTIVE);
        ProjectMemoryRevision contender = revision("revision-2", memory.memoryId(), 2L, ProjectMemoryRevisionStatus.QUARANTINED);

        store.addRevision(active);
        store.advanceHead(memory.memoryId(), active.revisionId(), 1L);
        store.addRevision(contender);

        assertEquals(active.revisionId(), store.find(memory.memoryId()).orElseThrow().headRevisionId());
        assertEquals(List.of(active), store.listRetrievable("101", "CODING_AGENT"));
        assertTrue(store.listRevisions(memory.memoryId()).contains(contender));
    }

    @Test
    void advancesTheSingleHeadWithCompareAndSwapAndRejectsCrossMemoryReferences() {
        InMemoryProjectMemoryStore store = new InMemoryProjectMemoryStore();
        ProjectMemory left = store.create(new ProjectMemory(
                "memory-left", "101", "", ProjectMemoryType.SEMANTIC, "left", 1L
        ));
        ProjectMemory right = store.create(new ProjectMemory(
                "memory-right", "101", "", ProjectMemoryType.SEMANTIC, "right", 1L
        ));
        ProjectMemoryRevision leftRevision = revision("revision-left", left.memoryId(), 1L, ProjectMemoryRevisionStatus.ACTIVE);
        ProjectMemoryRevision rightRevision = revision("revision-right", right.memoryId(), 1L, ProjectMemoryRevisionStatus.ACTIVE);
        store.addRevision(leftRevision);
        store.addRevision(rightRevision);

        assertThrows(IllegalArgumentException.class, () -> store.advanceHead(left.memoryId(), rightRevision.revisionId(), 1L));
        ProjectMemoryRevision crossMemorySupersedes = new ProjectMemoryRevision(
                "revision-cross", left.memoryId(), 2L, ProjectMemoryRevisionStatus.CANDIDATE,
                "title", "summary", "{}", "a".repeat(64), "v1", "revision-right", 1L, 1L
        );
        assertThrows(IllegalArgumentException.class, () -> store.addRevision(crossMemorySupersedes));

        ProjectMemoryRevision replacement = revision("revision-left-next", left.memoryId(), 2L, ProjectMemoryRevisionStatus.ACTIVE);
        store.addRevision(replacement);
        store.advanceHead(left.memoryId(), leftRevision.revisionId(), 1L);
        store.advanceHead(left.memoryId(), replacement.revisionId(), 2L);

        assertEquals(replacement.revisionId(), store.find(left.memoryId()).orElseThrow().headRevisionId());
        assertThrows(IllegalStateException.class, () -> store.advanceHead(left.memoryId(), leftRevision.revisionId(), 2L));
    }

    @Test
    void storesImmutableRevisionAndAuditableSourceSnapshotAfterReferencesDisappear() {
        InMemoryProjectMemoryStore store = new InMemoryProjectMemoryStore();
        ProjectMemory memory = store.create(new ProjectMemory(
                "memory-1", "101", "", ProjectMemoryType.EPISODIC, "release", 1L
        ));
        ProjectMemoryRevision revision = revision("revision-1", memory.memoryId(), 1L, ProjectMemoryRevisionStatus.CANDIDATE);
        ProjectMemorySource source = new ProjectMemorySource(
                "source-1", revision.revisionId(), "101", "task-9", "stage-1", "artifact-1",
                "rd-artifact://task-9", "b".repeat(64), "abc123", "extractor-1", "schema-1", "redacted summary"
        );

        store.addRevision(revision);
        store.addSource(source);
        store.markSourceReferencesUnavailable(source.sourceId());

        assertEquals("redacted summary", store.listSources(revision.revisionId()).getFirst().redactedSummary());
        assertEquals("b".repeat(64), store.listSources(revision.revisionId()).getFirst().sourceContentHash());
        assertFalse(store.replaceRevision(
                revision.withContent("changed", "changed", "{}", "c".repeat(64)),
                revision.rowVersion() + 1L
        ));
        assertEquals(revision, store.listRevisions(memory.memoryId()).getFirst());
    }

    @Test
    void derivesStableFieldNamedLengthPrefixedOperationKeys() {
        ProjectMemoryOperation first = ProjectMemoryOperation.pending(
                "op-1", "101", "STAGE_FINALIZATION", "stage-run-1", "c".repeat(64), "extractor-1", "schema-1"
        );
        ProjectMemoryOperation second = ProjectMemoryOperation.pending(
                "op-2", "101", "STAGE_FINALIZATION", "stage-run-1", "c".repeat(64), "extractor-1", "schema-1"
        );

        assertEquals(first.operationKey(), second.operationKey());
        assertTrue(first.operationKey().matches("[0-9a-f]{64}"));
        assertFalse(first.operationKey().equals(ProjectMemoryOperationKey.sha256(
                "101", "STAGE_FINALIZATION", "stage-run-1c", "d".repeat(64), "extractor-1", "schema-1"
        )));
    }

    @Test
    void reusesOnlyAnOperationWhoseImmutableCanonicalInputsMatch() {
        InMemoryProjectMemoryOperationStore store = new InMemoryProjectMemoryOperationStore();
        ProjectMemoryOperation original = ProjectMemoryOperation.pending(
                "op-1", "101", "STAGE_FINALIZATION", "stage-run-1", "c".repeat(64), "extractor-1", "schema-1"
        );

        assertEquals(original, store.register(original));
        assertEquals(original, store.register(ProjectMemoryOperation.pending(
                "op-2", "101", "STAGE_FINALIZATION", "stage-run-1", "c".repeat(64), "extractor-1", "schema-1"
        )));
        assertThrows(IllegalStateException.class, () -> store.register(new ProjectMemoryOperation(
                "op-3", "102", original.kind(), original.sourceIdentity(), original.sourceContentHash(),
                original.extractorVersion(), original.schemaVersion(), original.operationKey()
        )));
    }

    private static ProjectMemoryRevision revision(
            String revisionId,
            String memoryId,
            long version,
            ProjectMemoryRevisionStatus status
    ) {
        return new ProjectMemoryRevision(
                revisionId, memoryId, version, status, "title", "summary", "{}",
                "a".repeat(64), "v1", "", 1L, 1L
        );
    }
}
