package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;
import com.wish.rd.engine.agent.model.WorkflowExperienceType;
import com.wish.rd.rag.project.memory.ProjectMemoryLegacyLinkStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryLegacyLinkStore;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.LegacyExperienceInventoryDecision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyExperienceInventoryTest {
    private static final String HASH = "a".repeat(64);
    private static final String REPO = "repo-alpha";

    private final InMemoryProjectMemoryLegacyLinkStore linkStore = new InMemoryProjectMemoryLegacyLinkStore();
    private final InMemoryProjectMemoryStore memoryStore = new InMemoryProjectMemoryStore();
    private final LegacyExperienceInventoryService service =
            new LegacyExperienceInventoryService(linkStore, memoryStore, () -> 1_700_000_000_000L);

    @Test
    void classifiesEligibleRowsAsCandidateOnlyWithoutAutoActivation() {
        WorkflowExperienceEntry eligible = experience(
                "exp-eligible", "101", REPO, true, false, true, "artifact-1");

        LegacyExperienceInventoryResult result = service.inventory(
                LegacyExperienceInventoryRequest.of("101", REPO, eligible));

        assertEquals(1, result.eligible());
        assertEquals(1, result.candidatesCreated());
        assertEquals(LegacyExperienceInventoryDecision.ELIGIBLE, result.items().getFirst().decision());
        var revision = memoryStore.listRevisions("legacy-memory-exp-eligible").getFirst();
        assertEquals(ProjectMemoryRevisionStatus.CANDIDATE, revision.status());
        assertTrue(linkStore.findByLegacyExperienceId("exp-eligible").isPresent());
    }

    @Test
    void marksDuplicateRowsWithinBatchAndAcrossRunsWithoutCreatingExtraCandidates() {
        WorkflowExperienceEntry first = experience(
                "exp-dup-1", "101", REPO, true, false, true, "artifact-dup");
        WorkflowExperienceEntry sameContent = new WorkflowExperienceEntry(
                "exp-dup-2", "task-2", "stage-2", "artifact-dup", AgentRole.CODING_AGENT,
                WorkflowExperienceType.CODE_CHANGE, first.title(), first.summary(), first.contentJson(),
                true, false, true, 2L, "101", REPO, "intent", java.util.List.of(), "rev", 1.0,
                java.util.List.of(AgentRole.CODING_AGENT));

        LegacyExperienceInventoryResult batch = service.inventory(
                LegacyExperienceInventoryRequest.of("101", REPO, first, sameContent));
        assertEquals(1, batch.eligible());
        assertEquals(1, batch.duplicate());
        assertEquals(1, batch.candidatesCreated());

        LegacyExperienceInventoryResult replay = service.inventory(
                LegacyExperienceInventoryRequest.of("101", REPO, first));
        assertEquals(0, replay.candidatesCreated());
        assertEquals(1, replay.duplicate());
    }

    @Test
    void isolatesAmbiguousRejectedAndRepositoryConflictRows() {
        WorkflowExperienceEntry missingProject = experience(
                "exp-missing-project", "", REPO, true, false, true, "artifact-2");
        WorkflowExperienceEntry repoConflict = experience(
                "exp-repo-conflict", "101", "repo-other", true, false, true, "artifact-3");
        WorkflowExperienceEntry unsanitized = experience(
                "exp-unsanitized", "101", REPO, true, false, false, "artifact-4");
        WorkflowExperienceEntry failedDelivery = experience(
                "exp-failed", "101", REPO, false, true, true, "artifact-5");
        WorkflowExperienceEntry ambiguousSource = experience(
                "exp-no-source", "101", REPO, true, false, true, "");

        LegacyExperienceInventoryResult result = service.inventory(LegacyExperienceInventoryRequest.of(
                "101", REPO, missingProject, repoConflict, unsanitized, failedDelivery, ambiguousSource));

        assertEquals(4, result.ambiguous());
        assertEquals(1, result.rejected());
        assertEquals(0, result.eligible());
        assertEquals(0, result.candidatesCreated());
        assertTrue(result.items().stream().noneMatch(item -> item.decision() == LegacyExperienceInventoryDecision.ELIGIBLE));
    }

    @Test
    void rejectsInvalidProjectRequestsAndEmptyBatchesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> service.inventory(
                LegacyExperienceInventoryRequest.of("", REPO)));
        assertThrows(IllegalArgumentException.class, () -> service.inventory(
                LegacyExperienceInventoryRequest.of("abc", REPO)));

        LegacyExperienceInventoryResult empty = service.inventory(
                LegacyExperienceInventoryRequest.of("101", REPO));
        assertEquals(0, empty.examined());
        assertEquals(0, empty.candidatesCreated());
    }

    @Test
    void neverAutoActivatesFuzzyOrAmbiguousRecords() {
        WorkflowExperienceEntry fuzzy = experience(
                "exp-fuzzy", "101", REPO, true, false, true, "artifact-6");
        fuzzy = new WorkflowExperienceEntry(
                fuzzy.experienceId(), fuzzy.taskId(), fuzzy.stageRunId(), fuzzy.sourceArtifactId(),
                fuzzy.role(), fuzzy.experienceType(), fuzzy.title(), "maybe do X if unsure",
                fuzzy.contentJson(), true, false, true, fuzzy.createdAtEpochMillis(),
                fuzzy.projectId(), fuzzy.repositoryFingerprint(), fuzzy.intentId(), fuzzy.tags(),
                fuzzy.sourceRevision(), 0.2, fuzzy.applicableRoles());

        LegacyExperienceInventoryResult result = service.inventory(
                LegacyExperienceInventoryRequest.of("101", REPO, fuzzy));

        assertEquals(1, result.rejected());
        assertEquals(0, result.candidatesCreated());
        assertFalse(memoryStore.listRevisions("legacy-memory-exp-fuzzy").stream()
                .anyMatch(revision -> revision.status() == ProjectMemoryRevisionStatus.ACTIVE));
    }

    private static WorkflowExperienceEntry experience(
            String experienceId,
            String projectId,
            String repositoryFingerprint,
            boolean reusable,
            boolean failure,
            boolean redacted,
            String sourceArtifactId
    ) {
        return new WorkflowExperienceEntry(
                experienceId,
                "task-" + experienceId,
                "stage-" + experienceId,
                sourceArtifactId,
                AgentRole.CODING_AGENT,
                WorkflowExperienceType.CODE_CHANGE,
                "build",
                "use npm run build",
                "{\"command\":\"npm run build\"}",
                reusable,
                failure,
                redacted,
                1L,
                projectId,
                repositoryFingerprint,
                "intent",
                java.util.List.of("build"),
                "rev-1",
                failure ? 0.0 : 1.0,
                java.util.List.of(AgentRole.CODING_AGENT)
        );
    }
}
