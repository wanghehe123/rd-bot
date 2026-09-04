package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.engine.requirement.audit.AuditCompletion;
import com.wish.rd.engine.requirement.audit.AuditIntegrity;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedContractRef;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordKind;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedStateMutation;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateCodec;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateStore;
import com.wish.rd.engine.requirement.audit.CompletionBinding;
import com.wish.rd.engine.requirement.audit.ContractAuditVerdict;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Focused writeback tests for {@link AuditedStateFinalizationWriter}. */
class AuditedStateFinalizationWriterTest {

    @Test
    void applyDelegatesToStoreCasAndIgnoresNullMutation() {
        AuditedTaskStateStore store = mock(AuditedTaskStateStore.class);
        AuditedStateFinalizationWriter writer = new AuditedStateFinalizationWriter(store);
        assertDoesNotThrow(() -> writer.apply(null));
        verifyNoInteractions(store);

        AuditedStateMutation mutation = mutation("700", "701", "AC-001");
        when(store.head("700")).thenReturn(Optional.of(mutation.nextState()));
        writer.apply(mutation);
        verify(store).appendRevision(
                mutation.expectedStateVersion(), mutation.nextState(), mutation.auditRun());
        writer.apply(mutation);
        verify(store, org.mockito.Mockito.times(2)).appendRevision(
                mutation.expectedStateVersion(), mutation.nextState(), mutation.auditRun());
        verify(store, never()).bindCompletion(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void applyBindsCompletionWhenMutationCarriesBinding() {
        AuditedTaskStateStore store = mock(AuditedTaskStateStore.class);
        AuditedStateFinalizationWriter writer = new AuditedStateFinalizationWriter(store);
        AuditedStateMutation mutation = mutation("700", "701", "AC-001");
        CompletionBinding binding = new CompletionBinding(
                "700", mutation.auditRun().auditRunId(),
                mutation.nextState().stateVersion(), mutation.nextState().stateHash());
        AuditedStateMutation bound = mutation.withCompletionBinding(binding);
        when(store.head("700")).thenReturn(Optional.of(bound.nextState()));
        writer.apply(bound);
        verify(store).appendRevision(bound.expectedStateVersion(), bound.nextState(), bound.auditRun());
        verify(store).bindCompletion("700", binding.auditRunId(), binding.stateVersion(), binding.stateHash());
    }

    @Test
    void applyInitializesMissingHeadThenAppendsMutation() {
        AuditedTaskStateStore store = mock(AuditedTaskStateStore.class);
        RdTaskStore tasks = mock(RdTaskStore.class);
        RdRequirementTask task = RdRequirementTask.created("700", new CreateRequirementTaskCommand(
                "title", "P1", "https://example.test/repo", "owner", "repo", "main", "done",
                List.of("works"), false), 1L)
                .withState(RdTaskStatus.EXECUTING, "", "{}", "", "", 1L)
                .withConcurrency(1L, 1L);
        when(store.head("700")).thenReturn(Optional.empty());
        when(tasks.findRequirementTask("700")).thenReturn(Optional.of(task));
        when(store.initializeIfAbsent(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AuditedStateMutation mutation = versionTwoMutation("700", "701", "AC-001");
        AuditedStateFinalizationWriter writer = new AuditedStateFinalizationWriter(store, tasks);

        writer.apply(mutation);

        var ordered = inOrder(store, tasks);
        ordered.verify(store).head("700");
        ordered.verify(tasks).findRequirementTask("700");
        ordered.verify(store).initializeIfAbsent(any());
        ordered.verify(store).appendRevision(
                mutation.expectedStateVersion(), mutation.nextState(), mutation.auditRun());
        verify(store, never()).appendRevision(
                org.mockito.ArgumentMatchers.eq(1L), any(), any());
        assertEquals(2L, mutation.expectedStateVersion());
    }

    @Test
    void applyFailsClosedWhenHeadIsMissingAndTaskStoreIsUnavailable() {
        AuditedTaskStateStore store = mock(AuditedTaskStateStore.class);
        when(store.head("700")).thenReturn(Optional.empty());
        AuditedStateFinalizationWriter writer = new AuditedStateFinalizationWriter(store);
        assertThrows(IllegalStateException.class, () -> writer.apply(versionTwoMutation("700", "701", "AC-001")));
        verify(store, never()).appendRevision(anyLong(), any(), any());
    }

    private static AuditedStateMutation mutation(String taskId, String commandId, String recordId) {
        AuditedTaskState next = new AuditedTaskStateCodec().seal(new AuditedTaskState(
                taskId,
                1L,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 1L, 1L),
                List.of(new AuditedRecord(
                        recordId,
                        AuditedRecordKind.REQUIREMENT,
                        true,
                        "criterion " + recordId,
                        AuditedRecordStatus.PENDING,
                        List.of(),
                        "",
                        "")),
                "audit-" + commandId));
        AuditRun run = new AuditRun(
                "audit-" + commandId,
                taskId,
                "stage-1",
                "HOST_VERIFY",
                commandId,
                AuditCompletion.INCOMPLETE,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                List.of(),
                List.of(recordId),
                List.of(),
                List.of(),
                List.of(),
                1_700_000_000_000L);
        return new AuditedStateMutation(run, next, next.stateVersion());
    }

    private static AuditedStateMutation versionTwoMutation(String taskId, String commandId, String recordId) {
        AuditedTaskState next = new AuditedTaskStateCodec().seal(new AuditedTaskState(
                taskId,
                2L,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 1L, 1L),
                List.of(new AuditedRecord(
                        recordId,
                        AuditedRecordKind.REQUIREMENT,
                        true,
                        "criterion " + recordId,
                        AuditedRecordStatus.PENDING,
                        List.of(),
                        "",
                        "")),
                "audit-" + commandId));
        AuditRun run = new AuditRun(
                "audit-" + commandId,
                taskId,
                "stage-1",
                "CODING_AGENT",
                commandId,
                AuditCompletion.INCOMPLETE,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                List.of(),
                List.of(recordId),
                List.of("CLAIM-stage-1-1"),
                List.of(),
                List.of(),
                1_700_000_000_000L);
        return new AuditedStateMutation(run, next, next.stateVersion());
    }
}
