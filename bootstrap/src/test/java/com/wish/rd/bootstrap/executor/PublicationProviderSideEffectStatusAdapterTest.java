package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.PublicationProviderSideEffectStatusAdapter;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.provider.ProviderSideEffectStatusPort;
import com.wish.rd.engine.provider.model.ProviderFallbackSideEffectSafety;
import com.wish.rd.engine.provider.ProviderToolOperationStore;
import com.wish.rd.engine.provider.impl.InMemoryProviderToolOperationStore;
import com.wish.rd.engine.provider.model.ProviderToolOperation;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.exec.repair.provider.ProviderFallbackPreflightPort;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicationProviderSideEffectStatusAdapterTest {

    @Test
    void preflightContractCarriesHostHighRiskClassification() {
        assertTrue(Arrays.stream(ProviderFallbackPreflightPort.Request.class.getRecordComponents())
                .anyMatch(component -> "highRiskWork".equals(component.getName())));
    }

    @Test
    void blocksAlternateProviderBeforeInvocationWhenPublicationIsUnknown() {
        InMemoryRequirementPublicationStore store = new InMemoryRequirementPublicationStore();
        RequirementPublication prepared = publication("task-1", "stage-1");
        store.insertPrepared(prepared);
        store.save(prepared.withUnknownRemoteResult("push timeout", 2_000L, 1_000L));
        PublicationProviderSideEffectStatusAdapter adapter =
                new PublicationProviderSideEffectStatusAdapter(store);

        ProviderFallbackPreflightPort.Decision decision = adapter.evaluate(
                new ProviderFallbackPreflightPort.Request(
                        "task-1", "stage-1", "CODING_AGENT",
                        "openai", "TIMEOUT", "anthropic", "attempt-2", true
                )
        );

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("UNKNOWN_REMOTE_RESULT"));
    }

    @Test
    void blocksCapabilityIncompatibleCodingProviderBeforeInvocation() {
        PublicationProviderSideEffectStatusAdapter adapter =
                new PublicationProviderSideEffectStatusAdapter(
                        new InMemoryRequirementPublicationStore()
                );

        ProviderFallbackPreflightPort.Decision decision = adapter.evaluate(
                new ProviderFallbackPreflightPort.Request(
                        "task-1", "stage-1", "CODING_AGENT",
                        "deepseek", "FAILED_VALIDATION", "weak-gen", "attempt-2", true
                )
        );

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("capability"));
    }

    @Test
    void treatsBugCodingRoleAsSideEffectWorkDuringPreflight() {
        PublicationProviderSideEffectStatusAdapter adapter =
                new PublicationProviderSideEffectStatusAdapter(
                        new InMemoryRequirementPublicationStore()
                );

        ProviderFallbackPreflightPort.Decision decision = adapter.evaluate(
                new ProviderFallbackPreflightPort.Request(
                        "task-1", "stage-1", "BUG_CODING_AGENT",
                        "deepseek", "FAILED_VALIDATION", "weak-gen", "attempt-2", true
                )
        );

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("capability"));
    }

    @Test
    void blocksHostClassifiedHighRiskProviderSwitchBeforeInvocation() {
        PublicationProviderSideEffectStatusAdapter adapter =
                new PublicationProviderSideEffectStatusAdapter(
                        new InMemoryRequirementPublicationStore()
                );

        ProviderFallbackPreflightPort.Decision decision = adapter.evaluate(
                new ProviderFallbackPreflightPort.Request(
                        "task-1", "stage-1", "CODING_AGENT",
                        "deepseek", "FAILED_VALIDATION", "anthropic", "attempt-2", true, true
                )
        );

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("high-risk"));
    }

    @Test
    void acceptsHostCreatedCleanAttemptWhenNoRemotePublicationExists() {
        InMemoryProviderToolOperationStore operations = new InMemoryProviderToolOperationStore();
        operations.record(cleanlyAbortedOperation("task-1", "stage-1", "attempt-1"));
        PublicationProviderSideEffectStatusAdapter adapter =
                new PublicationProviderSideEffectStatusAdapter(
                        new InMemoryRequirementPublicationStore(),
                        operations
                );

        ProviderFallbackSideEffectSafety safety = adapter.resolve(
                new ProviderSideEffectStatusPort.Request(
                        "task-1", "stage-1", "task-1:CODING_AGENT:1", 1,
                        AgentRole.CODING_AGENT,
                        ProviderFallbackSideEffectSafety.explicitCleanAttempt(
                                "stage-1", "attempt-2"
                        )
                )
        );

        assertTrue(safety.isExplicitlySafe());
    }

    @Test
    void blocksSideEffectFallbackWhenNoDurableToolOperationExists() {
        PublicationProviderSideEffectStatusAdapter adapter =
                new PublicationProviderSideEffectStatusAdapter(
                        new InMemoryRequirementPublicationStore(),
                        ProviderToolOperationStore.unavailable()
                );

        ProviderFallbackPreflightPort.Decision decision = adapter.evaluate(
                new ProviderFallbackPreflightPort.Request(
                        "task-1", "stage-1", "CODING_AGENT",
                        "deepseek", "FAILED_VALIDATION", "anthropic", "attempt-2", true
                )
        );

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("tool-operation evidence is missing"));
    }

    @Test
    void blocksSecondProviderWhenToolSideEffectWasRecordedBeforePublication() {
        InMemoryProviderToolOperationStore operations = new InMemoryProviderToolOperationStore();
        operations.record(new ProviderToolOperation(
                "tool-operation-1",
                "task-1",
                "stage-1",
                "attempt-1",
                ProviderToolOperation.Status.COMMITTED,
                "repository tool operation completed before publication",
                1_000L,
                2_000L
        ));
        PublicationProviderSideEffectStatusAdapter adapter =
                new PublicationProviderSideEffectStatusAdapter(
                        new InMemoryRequirementPublicationStore(),
                        operations
                );

        ProviderFallbackPreflightPort.Decision decision = adapter.evaluate(
                new ProviderFallbackPreflightPort.Request(
                        "task-1", "stage-1", "CODING_AGENT",
                        "deepseek", "FAILED_VALIDATION", "anthropic", "attempt-2", true
                )
        );

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("COMMITTED"));
    }

    @Test
    void rejectsExecutorEvidenceForAnotherStage() {
        InMemoryProviderToolOperationStore operations = new InMemoryProviderToolOperationStore();
        operations.record(cleanlyAbortedOperation("task-1", "stage-1", "attempt-1"));
        PublicationProviderSideEffectStatusAdapter adapter =
                new PublicationProviderSideEffectStatusAdapter(
                        new InMemoryRequirementPublicationStore(),
                        operations
                );

        ProviderFallbackSideEffectSafety safety = adapter.resolve(
                new ProviderSideEffectStatusPort.Request(
                        "task-1", "stage-1", "task-1:CODING_AGENT:1", 1,
                        AgentRole.CODING_AGENT,
                        ProviderFallbackSideEffectSafety.explicitCleanAttempt(
                                "stage-other", "attempt-2"
                        )
                )
        );

        assertFalse(safety.isExplicitlySafe());
        assertTrue(safety.auditReason().contains("does not match"));
    }

    private static RequirementPublication publication(String taskId, String stageRunId) {
        return RequirementPublication.prepared(
                "publication-1",
                "operation-1",
                taskId,
                stageRunId,
                "main",
                "rd/task-1",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                1L
        );
    }

    private static ProviderToolOperation cleanlyAbortedOperation(
            String taskId,
            String stageRunId,
            String attemptId
    ) {
        return new ProviderToolOperation(
                "tool-operation-" + attemptId,
                taskId,
                stageRunId,
                attemptId,
                ProviderToolOperation.Status.CLEANLY_ABORTED,
                "host reconciled the previous tool operation with no retained side effect",
                1_000L,
                2_000L
        );
    }
}
