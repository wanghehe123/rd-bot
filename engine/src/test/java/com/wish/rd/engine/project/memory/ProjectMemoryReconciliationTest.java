package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort.ProjectMemoryOperationDraft;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryOperationStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryReconciliationTest {
    private static final String HASH = "b".repeat(64);

    @Test
    void registersMissingDeterministicOperationAfterCrashWindowWithoutReplayingDelivery() {
        InMemoryProjectMemoryOperationStore operations = new InMemoryProjectMemoryOperationStore();
        AtomicInteger deliveryReplayAttempts = new AtomicInteger();
        ProjectMemoryOperationDraft draft = ProjectMemoryReconciliationDraftFactory.fromFinalizedCommand(
                "9001", "101", "cmd-9001", HASH, true);
        ProjectMemoryReconciliationSourcePort source = limit -> {
            deliveryReplayAttempts.incrementAndGet();
            return List.of(new ProjectMemoryReconciliationCandidate("finalization:cmd-9001", draft));
        };
        ProjectMemoryReconciliationScanner scanner = new ProjectMemoryReconciliationScanner(source, operations);

        ProjectMemoryReconciliationResult first = scanner.reconcileOnce(10);

        assertEquals(1, first.examined());
        assertEquals(1, first.registered());
        assertEquals(0, first.skippedExisting());
        assertTrue(operations.findByKey(ProjectMemoryReconciliationDraftFactory.operationKey(draft)).isPresent());
        assertEquals(1, deliveryReplayAttempts.get());
    }

    @Test
    void repeatedScanIsIdempotentAndDoesNotRegisterDuplicates() {
        InMemoryProjectMemoryOperationStore operations = new InMemoryProjectMemoryOperationStore();
        ProjectMemoryOperationDraft draft = ProjectMemoryReconciliationDraftFactory.fromFinalizedCommand(
                "9002", "101", "cmd-9002", HASH, false);
        ProjectMemoryReconciliationSourcePort source = new FixedSource(List.of(
                new ProjectMemoryReconciliationCandidate("finalization:cmd-9002", draft)));
        ProjectMemoryReconciliationScanner scanner = new ProjectMemoryReconciliationScanner(source, operations);

        assertEquals(1, scanner.reconcileOnce(5).registered());
        ProjectMemoryReconciliationResult second = scanner.reconcileOnce(5);

        assertEquals(1, second.examined());
        assertEquals(0, second.registered());
        assertEquals(1, second.skippedExisting());
        assertEquals(1, operations.findByKey(ProjectMemoryReconciliationDraftFactory.operationKey(draft))
                .map(ProjectMemoryOperation::operationId)
                .stream()
                .count());
    }

    @Test
    void skipsExistingOperationWithSameDeterministicKeyWithoutMutation() {
        InMemoryProjectMemoryOperationStore operations = new InMemoryProjectMemoryOperationStore();
        ProjectMemoryOperationDraft original = ProjectMemoryReconciliationDraftFactory.fromFinalizedCommand(
                "9003", "101", "cmd-9003", HASH, true);
        operations.register(ProjectMemoryOperation.pending(
                original.operationId(),
                original.projectId(),
                original.kind(),
                original.sourceIdentity(),
                original.sourceContentHash(),
                original.extractorVersion(),
                original.schemaVersion()));
        ProjectMemoryOperationDraft replayWithDifferentOperationId =
                ProjectMemoryReconciliationDraftFactory.fromFinalizedCommand(
                        "9999", "101", "cmd-9003", HASH, true);
        ProjectMemoryReconciliationScanner scanner = new ProjectMemoryReconciliationScanner(
                new FixedSource(List.of(new ProjectMemoryReconciliationCandidate(
                        "finalization:cmd-9003", replayWithDifferentOperationId))),
                operations);

        ProjectMemoryReconciliationResult result = scanner.reconcileOnce(5);

        assertEquals(1, result.examined());
        assertEquals(0, result.registered());
        assertEquals(1, result.skippedExisting());
        assertEquals("9003", operations.findByKey(ProjectMemoryReconciliationDraftFactory.operationKey(original))
                .orElseThrow()
                .operationId());
    }

    private static final class FixedSource implements ProjectMemoryReconciliationSourcePort {
        private final List<ProjectMemoryReconciliationCandidate> candidates;

        private FixedSource(List<ProjectMemoryReconciliationCandidate> candidates) {
            this.candidates = new ArrayList<>(candidates);
        }

        @Override
        public List<ProjectMemoryReconciliationCandidate> listTerminalEvidenceMissingOperation(int limit) {
            return candidates.stream().limit(Math.max(0, limit)).toList();
        }
    }
}
