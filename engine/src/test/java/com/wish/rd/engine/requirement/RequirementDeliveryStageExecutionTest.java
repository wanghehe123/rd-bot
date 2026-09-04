package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.AgentStagePlanner;
import com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.audit.AuditCompletion;
import com.wish.rd.engine.requirement.audit.AuditIntegrity;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateCodec;
import com.wish.rd.engine.requirement.audit.AuditedTaskStatePolicyBootstrap;
import com.wish.rd.engine.requirement.audit.AuditedWritebackGateMode;
import com.wish.rd.engine.requirement.audit.CompletionBinding;
import com.wish.rd.engine.requirement.audit.ContractAuditVerdict;
import com.wish.rd.engine.requirement.audit.EvidenceRef;
import com.wish.rd.engine.requirement.audit.EvidenceSourceKind;
import com.wish.rd.engine.requirement.audit.impl.InMemoryAuditedTaskStateStore;
import com.wish.rd.engine.requirement.job.model.CommandDisposition;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.engine.requirement.verify.HostVerificationPort;
import com.wish.rd.engine.requirement.verify.impl.InMemoryHostVerificationStore;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.RoleContextBuilder;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentManifestCanonicalJson;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementDeliveryStageExecutionTest {

    @Test
    void roleExecutionProposalRunsTheBoundedRoleWithoutMutatingTheTaskOrTimeline() {
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), events, SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask legacyTask = executingTask(registry, "legacy role execution");
        RdRequirementTask proposalTask = executingTask(registry, "planned role execution");
        RequirementPolicyRun legacyAuthorization = appliedRun(
                "legacy-policy", legacyTask.taskId(), legacyTask.version(), legacyTask.fencingToken());
        RequirementPolicyRun proposalAuthorization = appliedRun(
                "proposal-policy", proposalTask.taskId(), proposalTask.version(), proposalTask.fencingToken());
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById(legacyAuthorization.id())).thenReturn(Optional.of(legacyAuthorization));
        when(policies.findById(proposalAuthorization.id())).thenReturn(Optional.of(proposalAuthorization));
        RequirementAgentStageOrchestrator orchestrator = mock(RequirementAgentStageOrchestrator.class);
        when(orchestrator.run(any(), any(), anyList(), any(), any(), any(), isNull())).thenAnswer(invocation -> {
            RdRequirementTask invokedTask = invocation.getArgument(1);
            return RequirementExecutionResult.success(
                    invokedTask.taskId(), "role complete", "https://example.test/pr/42", "{\"role\":\"reviewer\"}");
        });
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> {
                    throw new AssertionError("the bounded role orchestrator must own execution");
                });
        engine.setRequirementPolicyRunStore(policies);
        engine.setStageOrchestrator(orchestrator);

        RequirementStageCommand legacyCommand = roleCommand(legacyTask, legacyAuthorization.id(),
                AgentRole.REQUIREMENT_REVIEWER, "legacy-role-command");
        RequirementStageCommand proposalCommand = roleCommand(proposalTask, proposalAuthorization.id(),
                AgentRole.REQUIREMENT_REVIEWER, "proposal-role-command");

        engine.executeStage(legacyCommand);
        assertEquals(legacyTask.version() + 1L, registry.getRequirementTask(legacyTask.taskId()).version());

        int proposalTimelineBefore = events.listByTask(proposalTask.taskId()).size();
        RequirementStageExecutionPlan proposal = engine.planStage(proposalCommand);

        assertEquals(proposalTask, registry.getRequirementTask(proposalTask.taskId()));
        assertEquals(proposalTimelineBefore, events.listByTask(proposalTask.taskId()).size());
        assertEquals(1, proposal.mutations().size());
        assertEquals(RdTaskStatus.EXECUTING, proposal.mutations().getFirst().fromStatus());
        assertEquals(RdTaskStatus.EXECUTING, proposal.mutations().getFirst().toStatus());
        assertEquals("{\"role\":\"reviewer\"}", proposal.mutations().getFirst().executionResultJson());
        assertEquals("https://example.test/pr/42", proposal.mutations().getFirst().pullRequestUrl());
        assertEquals("SOLUTION_ARCHITECT", proposal.continuation().role());
        assertEquals("ROLE_EXECUTION:SOLUTION_ARCHITECT", proposal.continuation().stage());
        verify(orchestrator, org.mockito.Mockito.times(2)).run(
                any(), any(), anyList(), any(), any(), any(), isNull());
    }

    @Test
    void roleExecutionSuccessPlanWritesUntrustedClaimsWithoutCompletedPromotions() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = executingTask(registry, "coding claims writeback");
        RequirementPolicyRun authorization = appliedRun(
                "claims-policy", task.taskId(), task.version(), task.fencingToken());
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById(authorization.id())).thenReturn(Optional.of(authorization));
        RequirementAgentStageOrchestrator orchestrator = mock(RequirementAgentStageOrchestrator.class);
        when(orchestrator.run(any(), any(), anyList(), any(), any(), any(), isNull()))
                .thenReturn(RequirementExecutionResult.success(
                        task.taskId(),
                        "coding complete",
                        "",
                        "{\"status\":\"SUCCESS\",\"testStatus\":\"PASSED\",\"environmentNotes\":[\"jdk 21\"],\"facts\":[{\"kind\":\"DECLARED\",\"statement\":\"tests green\"}]}"));
        InMemoryAuditedTaskStateStore audited = new InMemoryAuditedTaskStateStore();
        new AuditedTaskStatePolicyBootstrap(audited).initializeIfAbsent(task);
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> {
                    throw new AssertionError("the bounded role orchestrator must own execution");
                });
        engine.setRequirementPolicyRunStore(policies);
        engine.setStageOrchestrator(orchestrator);
        engine.setAuditedTaskStateStore(audited);

        RequirementStageExecutionPlan proposal = engine.planStage(
                roleCommand(task, authorization.id(), AgentRole.CODING_AGENT, "coding-claims-command"));

        assertNotNull(proposal.auditedStateMutation());
        assertEquals(2L, proposal.auditedStateMutation().expectedStateVersion());
        assertEquals(AuditedRecordStatus.PENDING,
                proposal.auditedStateMutation().nextState().record("GATE-BUILD").status());
        assertEquals(AuditedRecordStatus.PENDING,
                proposal.auditedStateMutation().nextState().record("GATE-STATIC").status());
        assertTrue(proposal.auditedStateMutation().nextState().records().stream().noneMatch(record ->
                record.status() == AuditedRecordStatus.COMPLETED));
        assertTrue(proposal.auditedStateMutation().nextState().records().stream().anyMatch(record ->
                record.status() == AuditedRecordStatus.UNTRUSTED
                        && record.text().contains("testStatus=PASSED")));
        assertTrue(proposal.auditedStateMutation().auditRun().untrusted().size() >= 1);
        assertEquals("REQUIREMENT_DELIVERY", proposal.continuation().role());
        assertEquals("HOST_VERIFY", proposal.continuation().stage());
    }

    @Test
    void hostVerifySucceededWritesBuildStaticGatesAndContinuesToQa() {
        HostVerifyFixture fixture = hostVerifyFixture(
                HostVerificationStatus.SUCCEEDED, false, "", "", 1, true);
        RequirementStageExecutionPlan proposal = fixture.engine.planStage(fixture.command);

        assertEquals(CommandDisposition.SUCCEEDED, proposal.commandDisposition());
        assertEquals("QA_AGENT", proposal.continuation().role());
        assertEquals("ROLE_EXECUTION:QA_AGENT", proposal.continuation().stage());
        assertEquals(AuditedRecordStatus.COMPLETED,
                proposal.auditedStateMutation().nextState().record("GATE-BUILD").status());
        assertEquals(AuditedRecordStatus.COMPLETED,
                proposal.auditedStateMutation().nextState().record("GATE-STATIC").status());
        assertTrue(proposal.auditedStateMutation().nextState().record("GATE-BUILD").evidenceRefs().getFirst()
                .uri().startsWith("host-verification://artifacts/"));
    }

    @Test
    void hostVerifyDocsOnlySkipCompletesGatesAndContinuesToQa() {
        HostVerifyFixture fixture = hostVerifyFixture(
                HostVerificationStatus.SKIPPED_DOCS_ONLY, true, "", "", 1, true);
        RequirementStageExecutionPlan proposal = fixture.engine.planStage(fixture.command);

        assertEquals("ROLE_EXECUTION:QA_AGENT", proposal.continuation().stage());
        assertEquals(AuditedRecordStatus.COMPLETED,
                proposal.auditedStateMutation().nextState().record("GATE-BUILD").status());
        assertEquals(AuditedRecordStatus.COMPLETED,
                proposal.auditedStateMutation().nextState().record("GATE-STATIC").status());
    }

    @Test
    void hostVerifyProductDefectWithBudgetFreezesHostVerifyFixWithoutQaContinuation() {
        HostVerifyFixture fixture = hostVerifyFixture(
                HostVerificationStatus.FAILED_RETRYABLE, false, "PRODUCT_DEFECT",
                "BUILD exit 1: cannot find symbol Foo", 1, false);
        fixture.engine.setExecutionProfileResolver(new CommandScopedPiRemediationResolver());
        RequirementStageExecutionPlan proposal = fixture.engine.planStage(fixture.command);

        assertEquals(CommandDisposition.SUCCEEDED, proposal.commandDisposition());
        assertTrue(proposal.continuation().isTerminal());
        assertEquals(AgentRemediationKind.HOST_VERIFY_FIX, proposal.piQaRemediationIntent().kind());
        assertEquals(1, proposal.piQaRemediationIntent().remediationNo());
        assertEquals(2, proposal.piQaRemediationIntent().targetCodingAttemptNo());
        assertEquals("", proposal.piQaRemediationIntent().targetQaStageRunId());
        assertEquals(0, proposal.piQaRemediationIntent().targetQaAttemptNo());
        assertEquals("verify-1", proposal.piQaRemediationIntent().hostVerificationRunId());
        assertEquals(RdTaskStatus.EXECUTING, proposal.mutations().getFirst().toStatus());
        assertEquals(AuditedRecordStatus.PENDING,
                proposal.auditedStateMutation().nextState().record("GATE-BUILD").status());
    }

    @Test
    void hostVerifyProductDefectAfterTwoFixesGoesHuman() {
        HostVerifyFixture fixture = hostVerifyFixture(
                HostVerificationStatus.FAILED_RETRYABLE, false, "PRODUCT_DEFECT",
                "BUILD exit 1: still broken", 3, false);
        fixture.store.create(new HostVerificationRun(
                "verify-prior-1", fixture.task.taskId(), "coding-1", "", 1,
                HostVerificationStatus.FAILED_RETRYABLE, false, "PRODUCT_DEFECT", "first", 0, 1L, 1L, 1L));
        fixture.store.create(new HostVerificationRun(
                "verify-prior-2", fixture.task.taskId(), "coding-1", "verify-prior-1", 2,
                HostVerificationStatus.FAILED_RETRYABLE, false, "PRODUCT_DEFECT", "second", 1, 2L, 2L, 2L));
        RequirementStageExecutionPlan proposal = fixture.engine.planStage(fixture.command);

        assertEquals(CommandDisposition.TERMINAL_FAILURE, proposal.commandDisposition());
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, proposal.mutations().getFirst().toStatus());
        assertTrue(proposal.mutations().getFirst().executionResultJson().contains("\"failurePhase\":\"HOST_VERIFY\""));
        assertTrue(proposal.mutations().getFirst().executionResultJson().contains("\"failedVerificationRunId\":\"verify-1\""));
        assertTrue(proposal.continuation().isTerminal());
        assertEquals(null, proposal.piQaRemediationIntent());
    }

    @Test
    void hostVerifyEnvironmentFailureGoesHumanWithProvenance() {
        HostVerifyFixture fixture = hostVerifyFixture(
                HostVerificationStatus.FAILED_NEEDS_HUMAN, false, "ENVIRONMENT",
                "npm registry unreachable", 1, false);
        RequirementStageExecutionPlan proposal = fixture.engine.planStage(fixture.command);

        assertEquals(CommandDisposition.TERMINAL_FAILURE, proposal.commandDisposition());
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, proposal.mutations().getFirst().toStatus());
        assertTrue(proposal.mutations().getFirst().executionResultJson().contains("\"failurePhase\":\"HOST_VERIFY\""));
        assertTrue(proposal.mutations().getFirst().executionResultJson().contains("\"failedVerificationRunId\":\"verify-1\""));
        assertTrue(proposal.continuation().isTerminal());
    }

    @Test
    void checkpointBoundHostVerifyTransitionsRecoveringTaskBeforeOutcome() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask executing = executingTask(registry, "checkpoint host verify retry");
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        long now = System.currentTimeMillis();
        stages.save(AgentStageRun.pending("coding-1", executing.taskId(), AgentRole.CODING_AGENT, 1,
                        executing.taskId() + ":CODING_AGENT:1", now)
                .withStatus(AgentStageStatus.SUCCEEDED, "", "", now));
        registry.markRequirementFailedNeedsHuman(
                executing.taskId(), "cannot detect safe BUILD commands from the repository",
                executing.executionResultJson());
        RdRequirementTask recovering = (RdRequirementTask) registry.markRecovering(
                executing.taskId(), "checkpoint-bound retry: HOST_VERIFY");
        InMemoryHostVerificationStore store = new InMemoryHostVerificationStore();
        HostVerificationRun run = new HostVerificationRun(
                "verify-retry-1", recovering.taskId(), "coding-1", "", 2,
                HostVerificationStatus.FAILED_NEEDS_HUMAN, false,
                "REQUIREMENT_AMBIGUITY", "cannot detect", 0, now, now, now);
        store.create(run);
        InMemoryAuditedTaskStateStore audited = new InMemoryAuditedTaskStateStore();
        new AuditedTaskStatePolicyBootstrap(audited).initializeIfAbsent(recovering);
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> {
                    throw new AssertionError("HOST_VERIFY must not call the role executor");
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(SnowflakeIdGenerator.defaultGenerator()::nextIdString),
                stages);
        engine.setHostVerificationPort((ignoredTask, ignoredCoding, ignoredPlan, ignoredUsed) -> run);
        engine.setHostVerificationStore(store);
        engine.setAuditedTaskStateStore(audited);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "checkpoint-host-verify-command", recovering.taskId(), recovering.version(),
                recovering.fencingToken(), "REQUIREMENT_DELIVERY", "HOST_VERIFY", 0, 3, now + 60_000L,
                ScheduleResourceClass.DOCKER,
                Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER),
                "_default", "provider", "P1", "policy-1", "101", 101L, "", now);

        RequirementStageExecutionPlan proposal = engine.planStage(command);

        assertEquals(List.of(RdTaskStatus.RECOVERING, RdTaskStatus.EXECUTING), proposal.mutations().stream()
                .map(com.wish.rd.engine.requirement.job.model.RequirementTaskMutation::fromStatus).toList());
        assertEquals(List.of(RdTaskStatus.EXECUTING, RdTaskStatus.FAILED_NEEDS_HUMAN), proposal.mutations().stream()
                .map(com.wish.rd.engine.requirement.job.model.RequirementTaskMutation::toStatus).toList());
        assertEquals(CommandDisposition.TERMINAL_FAILURE, proposal.commandDisposition());
        assertEquals(recovering, registry.getRequirementTask(recovering.taskId()));
    }

    @Test
    void checkpointBoundRoleProposalTransitionsRecoveringTaskToExecutingBeforeItsRoleSnapshot() {
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), events, SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask executing = executingTask(registry, "checkpoint role retry");
        registry.markRequirementFailedRetryable(
                executing.taskId(), "coding provider unavailable", executing.executionResultJson());
        RdRequirementTask recovering = (RdRequirementTask) registry.markRecovering(
                executing.taskId(), "retry coding role");
        RequirementPolicyRun authorization = appliedRun(
                "retry-role-policy", executing.taskId(), executing.version(), executing.fencingToken());
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById(authorization.id())).thenReturn(Optional.of(authorization));
        RequirementAgentStageOrchestrator orchestrator = mock(RequirementAgentStageOrchestrator.class);
        when(orchestrator.run(any(), any(), anyList(), any(), any(), any(), isNull()))
                .thenReturn(RequirementExecutionResult.success(
                        recovering.taskId(), "coding complete", "", "{\"role\":\"coding\"}"));
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> null);
        engine.setRequirementPolicyRunStore(policies);
        engine.setStageOrchestrator(orchestrator);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "checkpoint-coding-command", recovering.taskId(), recovering.version(), recovering.fencingToken(),
                AgentRole.CODING_AGENT.name(), "ROLE_EXECUTION:CODING_AGENT", 0, 3,
                System.currentTimeMillis() + 60_000L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER,
                Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER),
                "_default", "provider", "P1", authorization.id(), "101", 101L,
                "retry-coding-binding", System.currentTimeMillis());

        RequirementStageExecutionPlan proposal = engine.planStage(command);

        assertEquals(List.of(RdTaskStatus.RECOVERING, RdTaskStatus.EXECUTING), proposal.mutations().stream()
                .map(com.wish.rd.engine.requirement.job.model.RequirementTaskMutation::fromStatus).toList());
        assertEquals(List.of(RdTaskStatus.EXECUTING, RdTaskStatus.EXECUTING), proposal.mutations().stream()
                .map(com.wish.rd.engine.requirement.job.model.RequirementTaskMutation::toStatus).toList());
        assertEquals("REQUIREMENT_DELIVERY", proposal.continuation().role());
        assertEquals("HOST_VERIFY", proposal.continuation().stage());
        assertEquals(recovering, registry.getRequirementTask(recovering.taskId()));
    }

    @Test
    void checkpointBoundFirstRoleRetryInheritsTheAppliedPolicyOnTheRecoveringSnapshot() {
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), events, SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask executing = executingTask(registry, "checkpoint first role retry");
        RequirementPolicyRun authorization = appliedRun(
                "retry-reviewer-policy", executing.taskId(), executing.version(), executing.fencingToken());
        registry.markRequirementFailedNeedsHuman(
                executing.taskId(), "Pi session settled without rd_submit_result", executing.executionResultJson());
        RdRequirementTask recovering = (RdRequirementTask) registry.markRecovering(
                executing.taskId(), "checkpoint-bound retry: ROLE_EXECUTION:REQUIREMENT_REVIEWER");
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById(authorization.id())).thenReturn(Optional.of(authorization));
        RequirementAgentStageOrchestrator orchestrator = mock(RequirementAgentStageOrchestrator.class);
        when(orchestrator.run(any(), any(), anyList(), any(), any(), any(), isNull()))
                .thenReturn(RequirementExecutionResult.success(
                        recovering.taskId(), "reviewer complete", "", "{\"decision\":\"APPROVED\"}"));
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> null);
        engine.setRequirementPolicyRunStore(policies);
        engine.setStageOrchestrator(orchestrator);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "7495153128925433856", recovering.taskId(), recovering.version(), recovering.fencingToken(),
                AgentRole.REQUIREMENT_REVIEWER.name(), "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 0, 3,
                System.currentTimeMillis() + 60_000L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER,
                Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER),
                "_default", "provider", "P1", authorization.id(), "101", 101L,
                "retry-reviewer-binding", System.currentTimeMillis());

        RequirementStageExecutionPlan proposal = engine.planStage(command);

        assertTrue(recovering.version() > executing.version());
        assertTrue(recovering.fencingToken() > executing.fencingToken());
        assertEquals(List.of(RdTaskStatus.RECOVERING, RdTaskStatus.EXECUTING), proposal.mutations().stream()
                .map(com.wish.rd.engine.requirement.job.model.RequirementTaskMutation::fromStatus).toList());
        assertEquals(List.of(RdTaskStatus.EXECUTING, RdTaskStatus.EXECUTING), proposal.mutations().stream()
                .map(com.wish.rd.engine.requirement.job.model.RequirementTaskMutation::toStatus).toList());
        assertEquals("SOLUTION_ARCHITECT", proposal.continuation().role());
        assertEquals(recovering, registry.getRequirementTask(recovering.taskId()));
    }

    @Test
    void firstRoleCommandWithoutARetryCheckpointMustMatchTheAppliedPolicyGeneration() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask executing = executingTask(registry, "stale first role generation");
        RequirementPolicyRun authorization = appliedRun(
                "original-apply-policy", executing.taskId(),
                Math.subtractExact(executing.version(), 2L), Math.subtractExact(executing.fencingToken(), 2L));
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById(authorization.id())).thenReturn(Optional.of(authorization));
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> {
                    throw new AssertionError("unbound first role command must not execute");
                });
        engine.setRequirementPolicyRunStore(policies);
        RequirementStageCommand command = roleCommand(
                executing, authorization.id(), AgentRole.REQUIREMENT_REVIEWER, "unbound-first-role");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> engine.planStage(command));

        assertTrue(failure.getMessage().contains("first role command is not bound to the applied policy generation"));
        assertEquals(executing, registry.getRequirementTask(executing.taskId()));
    }

    @Test
    void lastRoleExecutionProposalContinuesToDeterministicReview() {
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), events, SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = executingTask(registry, "last planned role");
        RequirementPolicyRun authorization = appliedRun(
                "last-role-policy", task.taskId(), task.version(), task.fencingToken());
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById(authorization.id())).thenReturn(Optional.of(authorization));
        RequirementAgentStageOrchestrator orchestrator = mock(RequirementAgentStageOrchestrator.class);
        when(orchestrator.run(any(), any(), anyList(), any(), any(), any(), isNull()))
                .thenReturn(RequirementExecutionResult.success(task.taskId(), "qa complete", "", "{\"role\":\"qa\"}"));
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> null);
        engine.setRequirementPolicyRunStore(policies);
        engine.setStageOrchestrator(orchestrator);
        RequirementStageCommand command = roleCommand(task, authorization.id(), AgentRole.QA_AGENT, "last-role-command");

        RequirementStageExecutionPlan proposal = engine.planStage(command);

        assertEquals(task, registry.getRequirementTask(task.taskId()));
        assertEquals(1, proposal.mutations().size());
        assertEquals("DETERMINISTIC_REVIEW", proposal.continuation().stage());
        assertEquals("REQUIREMENT_DELIVERY", proposal.continuation().role());
    }

    @Test
    void failedRoleExecutionProposalsAreSingleTerminalMutationsWithoutTaskWrites() {
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), events, SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = executingTask(registry, "failed planned role");
        RequirementPolicyRun authorization = appliedRun(
                "failed-role-policy", task.taskId(), task.version(), task.fencingToken());
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById(authorization.id())).thenReturn(Optional.of(authorization));
        RequirementAgentStageOrchestrator orchestrator = mock(RequirementAgentStageOrchestrator.class);
        when(orchestrator.run(any(), any(), anyList(), any(), any(), any(), isNull()))
                .thenReturn(
                        RequirementExecutionResult.failure(task.taskId(), "provider unavailable", "{\"status\":\"FAILED_RETRYABLE\"}"),
                        RequirementExecutionResult.failure(task.taskId(), "manual decision required", "{\"status\":\"NEEDS_HUMAN\"}"));
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> null);
        engine.setRequirementPolicyRunStore(policies);
        engine.setStageOrchestrator(orchestrator);
        RequirementStageCommand retryableCommand = roleCommand(
                task, authorization.id(), AgentRole.REQUIREMENT_REVIEWER, "retryable-role-command");
        RequirementStageCommand humanCommand = roleCommand(
                task, authorization.id(), AgentRole.REQUIREMENT_REVIEWER, "human-role-command");
        int timelineBefore = events.listByTask(task.taskId()).size();

        RequirementStageExecutionPlan retryable = engine.planStage(retryableCommand);
        RequirementStageExecutionPlan human = engine.planStage(humanCommand);

        assertEquals(task, registry.getRequirementTask(task.taskId()));
        assertEquals(timelineBefore, events.listByTask(task.taskId()).size());
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, retryable.mutations().getFirst().toStatus());
        assertEquals(com.wish.rd.engine.requirement.job.model.CommandDisposition.RETRYABLE_TECHNICAL_FAILURE,
                retryable.commandDisposition());
        assertTrue(retryable.continuation().isTerminal());
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, human.mutations().getFirst().toStatus());
        assertEquals(com.wish.rd.engine.requirement.job.model.CommandDisposition.TERMINAL_FAILURE,
                human.commandDisposition());
        assertTrue(human.continuation().isTerminal());
    }

    @Test
    void commandScopedQaProductDefectMustPlanCodingBounceInsteadOfHuman() throws Exception {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = executingTask(registry, "qa product bounce");
        RequirementPolicyRun authorization = appliedRun(
                "qa-bounce-policy", task.taskId(), task.version(), task.fencingToken());
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById(authorization.id())).thenReturn(Optional.of(authorization));
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(AgentStageRun.pending(
                "201", task.taskId(), AgentRole.QA_AGENT, 2, task.taskId() + ":QA_AGENT:2", 10L
        ).withStatus(AgentStageStatus.FAILED_NEEDS_HUMAN, "AGENT_RESULT_REJECTED", "qa failed", 20L));
        stages.save(AgentStageRun.pending(
                "101", task.taskId(), AgentRole.CODING_AGENT, 2, task.taskId() + ":CODING_AGENT:2", 8L
        ).withStatus(AgentStageStatus.SUCCEEDED, "", "", 9L));

        String qaJson = """
                {"status":"FAILED","failureCategory":"PRODUCT_DEFECT","retryRecommendation":"CODING_AGENT",
                  "remediationRequest":{"requested":true,"targetRole":"CODING_AGENT",
                    "reason":"two reproducible backend 500s block AC-003 and AC-013",
                    "bugFindingIds":["bf-ac003-status-500"]},
                  "bugFindings":[{"id":"bf-ac003-status-500","severity":"HIGH","acceptanceCriteriaId":"AC-003",
                    "reproductionSteps":["PATCH /api/merchants/manage/status"],
                    "expected":"200 closed","actual":"HTTP 500 no such column now",
                    "evidenceArtifactIds":["qa-evidence/commands/bug-repro.log"],
                    "suspectedFiles":["server/src/routes/merchants.ts"]}]}
                """.replaceAll("\\s+", "");
        String aggregate = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "status", "NEEDS_HUMAN",
                "pullRequestUrl", "",
                "stages", java.util.List.of(java.util.Map.of(
                        "role", "QA_AGENT",
                        "success", false,
                        "summary", "QA failed acceptance",
                        "pullRequestUrl", "",
                        "errorMessage", "QA_AGENT failed acceptance",
                        "resultJson", qaJson
                ))
        ));
        RequirementAgentStageOrchestrator orchestrator = mock(RequirementAgentStageOrchestrator.class);
        when(orchestrator.run(any(), any(), anyList(), any(), any(), any(), isNull()))
                .thenReturn(RequirementExecutionResult.failure(
                        task.taskId(), "QA_AGENT failed: QA_AGENT failed acceptance", aggregate));

        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                new InMemoryTaskMaterialStore(),
                request -> {
                    throw new AssertionError("orchestrator owns role execution");
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(SnowflakeIdGenerator.defaultGenerator()::nextIdString),
                stages,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                RequirementPullRequestPublisherPort.unavailable()
        );
        engine.setRequirementPolicyRunStore(policies);
        engine.setStageOrchestrator(orchestrator);
        engine.setExecutionProfileResolver(new CommandScopedPiRemediationResolver());

        RequirementStageExecutionPlan plan = engine.planStage(
                roleCommand(task, authorization.id(), AgentRole.QA_AGENT, "qa-2-command"));

        assertEquals(CommandDisposition.SUCCEEDED, plan.commandDisposition());
        assertNotNull(plan.piQaRemediationIntent(), "command-scoped QA product bounce must mint a Host intent");
        assertEquals(AgentRemediationKind.QA_PRODUCT_FIX, plan.piQaRemediationIntent().kind());
        assertEquals("201", plan.piQaRemediationIntent().sourceStageRunId());
        assertEquals(3, plan.piQaRemediationIntent().targetCodingAttemptNo());
        assertEquals(3, plan.piQaRemediationIntent().targetQaAttemptNo());
    }

    @Test
    void commandScopedQaProductDefectPrefersQaRoleResultWhenNestedResultJsonIsUnparseable() throws Exception {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = executingTask(registry, "qa role result sibling bounce");
        RequirementPolicyRun authorization = appliedRun(
                "qa-sibling-policy", task.taskId(), task.version(), task.fencingToken());
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById(authorization.id())).thenReturn(Optional.of(authorization));
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(AgentStageRun.pending(
                "201", task.taskId(), AgentRole.QA_AGENT, 2, task.taskId() + ":QA_AGENT:2", 10L
        ).withStatus(AgentStageStatus.FAILED_NEEDS_HUMAN, "AGENT_RESULT_REJECTED", "qa failed", 20L));
        stages.save(AgentStageRun.pending(
                "101", task.taskId(), AgentRole.CODING_AGENT, 2, task.taskId() + ":CODING_AGENT:2", 8L
        ).withStatus(AgentStageStatus.SUCCEEDED, "", "", 9L));

        String qaJson = """
                {"status":"FAILED","failureCategory":"PRODUCT_DEFECT","retryRecommendation":"CODING_AGENT",
                  "remediationRequest":{"requested":true,"targetRole":"CODING_AGENT",
                    "reason":"two reproducible backend 500s block AC-003 and AC-013",
                    "bugFindingIds":["bf-ac003-status-500"]},
                  "bugFindings":[{"id":"bf-ac003-status-500","severity":"HIGH","acceptanceCriteriaId":"AC-003",
                    "reproductionSteps":["PATCH /api/merchants/manage/status"],
                    "expected":"200 closed","actual":"HTTP 500 no such column now",
                    "evidenceArtifactIds":["qa-evidence/commands/bug-repro.log"],
                    "suspectedFiles":["server/src/routes/merchants.ts"]}]}
                """.replaceAll("\\s+", "");
        String aggregate = """
                {"status":"NEEDS_HUMAN","stages":[{"role":"QA_AGENT","success":false,
                "resultJson":"{status:FAILED"}],"qaRoleResult":%s}
                """.formatted(qaJson);
        RequirementAgentStageOrchestrator orchestrator = mock(RequirementAgentStageOrchestrator.class);
        when(orchestrator.run(any(), any(), anyList(), any(), any(), any(), isNull()))
                .thenReturn(RequirementExecutionResult.failure(
                        task.taskId(), "QA_AGENT failed: QA_AGENT failed acceptance", aggregate));

        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                new InMemoryTaskMaterialStore(),
                request -> {
                    throw new AssertionError("orchestrator owns role execution");
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(SnowflakeIdGenerator.defaultGenerator()::nextIdString),
                stages,
                new RoleContextBuilder(),
                new InMemoryRoleContextPackageStore(),
                AgentWorkflowAlertSinkPort.noop(),
                WorkflowExperienceStore.noop(),
                new RequirementDeliveryReviewer(),
                RequirementPullRequestPublisherPort.unavailable()
        );
        engine.setRequirementPolicyRunStore(policies);
        engine.setStageOrchestrator(orchestrator);
        engine.setExecutionProfileResolver(new CommandScopedPiRemediationResolver());

        RequirementStageExecutionPlan plan = engine.planStage(
                roleCommand(task, authorization.id(), AgentRole.QA_AGENT, "qa-2-command"));

        assertEquals(CommandDisposition.SUCCEEDED, plan.commandDisposition());
        assertNotNull(plan.piQaRemediationIntent());
        assertEquals(AgentRemediationKind.QA_PRODUCT_FIX, plan.piQaRemediationIntent().kind());
    }

    @Test
    void roleCommandWithoutPolicyAuthorizationFailsClosedBeforeExecution() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = executingTask(registry, "missing authorization");
        RequirementStageCommand command = RequirementStageCommand.pending(
                "role-without-policy", task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 0, 3,
                System.currentTimeMillis() + 60_000L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER,
                Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER),
                "_default", "provider", "P1", System.currentTimeMillis());
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> {
                    throw new AssertionError("executor must not run without policy authorization");
                });

        assertThrows(IllegalStateException.class, () -> engine.executeStage(command));
        assertEquals(task, registry.getRequirementTask(task.taskId()));
    }

    @Test
    void roleCommandCannotBorrowAppliedPolicyRunFromAnotherTask() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = executingTask(registry, "wrong authorization");
        RequirementStageCommand command = RequirementStageCommand.pending(
                "role-wrong-policy", task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 0, 3,
                System.currentTimeMillis() + 60_000L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER,
                Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER),
                "_default", "provider", "P1", "wrong-policy", System.currentTimeMillis());
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById("wrong-policy")).thenReturn(Optional.of(appliedRun(
                "wrong-policy", "another-task", task.version(), task.fencingToken())));
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> {
                    throw new AssertionError("executor must not run with another task's policy");
                });
        engine.setRequirementPolicyRunStore(policies);

        assertThrows(IllegalStateException.class, () -> engine.executeStage(command));
        assertEquals(task, registry.getRequirementTask(task.taskId()));
    }

    @Test
    void staleRoleCommandCannotBorrowANewerActivePolicyGeneration() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = executingTask(registry, "stale authorization");
        RequirementStageCommand stale = RequirementStageCommand.pending(
                "stale-role-command", task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 0, 3,
                System.currentTimeMillis() + 60_000L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER,
                Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER),
                "_default", "provider", "P1", "old-policy-run", System.currentTimeMillis());
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById("old-policy-run")).thenReturn(Optional.empty());
        when(policies.findActiveByTask(task.taskId())).thenReturn(Optional.of(appliedRun(
                "new-policy-run", task.taskId(), task.version(), task.fencingToken())));
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> {
                    throw new AssertionError("stale command must not execute under a newer generation");
                });
        engine.setRequirementPolicyRunStore(policies);

        assertThrows(IllegalStateException.class, () -> engine.executeStage(stale));

        verify(policies).findById("old-policy-run");
        verify(policies, never()).findActiveByTask(anyString());
        assertEquals(task, registry.getRequirementTask(task.taskId()));
    }

    @Test
    void roleExecutionUsesFrozenPlanAndPolicyFromItsAppliedGeneration() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = executingTask(registry, "frozen authorization");
        RequirementPolicyRun authorization = appliedRun(
                "frozen-policy", task.taskId(), task.version(), task.fencingToken());
        RequirementStageCommand command = RequirementStageCommand.pending(
                "role-frozen-policy", task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 0, 3,
                System.currentTimeMillis() + 60_000L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER,
                Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER),
                "_default", "provider", "P1", authorization.id(), System.currentTimeMillis());
        RequirementPolicyRunStore policies = mock(RequirementPolicyRunStore.class);
        when(policies.findById(authorization.id())).thenReturn(Optional.of(authorization));
        AtomicReference<RequirementExecutionRequest> captured = new AtomicReference<>();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> {
                    captured.set(request);
                    return RequirementExecutionResult.failure(
                            request.taskId(), "stop after authorization capture", "{}");
                });
        engine.setRequirementPolicyRunStore(policies);

        RequirementDeliveryResult result = engine.executeStage(command);

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, result.status());
        assertTrue(captured.get().prompt().contains("frozen"));
        assertTrue(captured.get().prompt().contains("frozen-policy-reason"));
    }

    @Test
    void reportingCommandAdvancesOnlyTheReportingStep() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "bounded stage", "P2", "", "", "", "", "", "", "https://github.com/example/repo", "example", "repo", "main",
                "result", List.of(), List.of(), false));
        for (RdTaskStatus status : List.of(
                RdTaskStatus.MATERIAL_COLLECTING,
                RdTaskStatus.MATERIAL_READY,
                RdTaskStatus.CONTEXT_BUILDING,
                RdTaskStatus.CONTEXT_READY,
                RdTaskStatus.PLAN_GENERATING,
                RdTaskStatus.PLAN_GENERATED,
                RdTaskStatus.WAITING_POLICY,
                RdTaskStatus.EXECUTING,
                RdTaskStatus.VALIDATING,
                RdTaskStatus.PR_CREATING,
                RdTaskStatus.COMMITTED)) {
            task = registry.transitionRequirementFenced(task, status, "", "", "", "");
        }

        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> null);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "reporting-command", task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_DELIVERY", "REPORTING", 0, 3, 0L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.GENERIC,
                "_default", "", "P2", System.currentTimeMillis());

        RequirementDeliveryResult result = engine.executeStage(command);

        assertEquals(RdTaskStatus.REPORTING, result.status());
        assertEquals(RdTaskStatus.REPORTING, registry.getRequirementTask(task.taskId()).status());
    }

    @Test
    void operationKeyedPublicationCommandResumesRejectedTaskThroughBoundedPublicationStage() {
        RagStreamTaskRegistry registry = spy(new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator()));
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "reconciled publication", "P1", "https://github.com/example/repo", "example", "repo", "main",
                "patch delivery", List.of("不创建 PR"), false));
        for (RdTaskStatus status : List.of(
                RdTaskStatus.MATERIAL_COLLECTING,
                RdTaskStatus.MATERIAL_READY,
                RdTaskStatus.CONTEXT_BUILDING,
                RdTaskStatus.CONTEXT_READY,
                RdTaskStatus.PLAN_GENERATING,
                RdTaskStatus.PLAN_GENERATED,
                RdTaskStatus.WAITING_POLICY,
                RdTaskStatus.EXECUTING,
                RdTaskStatus.VALIDATING,
                RdTaskStatus.PR_CREATING)) {
            task = registry.transitionRequirementFenced(task, status, "", "{}", "", "");
        }
        task = registry.markRequirementRejected(task.taskId(), "publication timed out", task.executionResultJson());
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> null);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "reconciled-publication-command", task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_DELIVERY", "PUBLICATION:sha256:" + "a".repeat(64), 0, 3, 0L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.GENERIC,
                "_default", "", "P1", System.currentTimeMillis());

        RequirementDeliveryResult result = engine.executeStage(command);

        assertEquals(RdTaskStatus.COMMITTED, result.status());
        assertEquals(RdTaskStatus.COMMITTED, registry.getRequirementTask(task.taskId()).status());
        verify(registry, never()).markRequirementPrCreating(anyString(), anyString());
    }

    @Test
    void staleStageCommandCannotWriteAfterTaskSnapshotAdvances() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "stale stage", "P1", "https://github.com/example/repo", "example", "repo", "main",
                "result", List.of(), false));
        task = registry.transitionRequirementFenced(
                task, RdTaskStatus.MATERIAL_COLLECTING, "", "", "", "");
        RequirementStageCommand stale = RequirementStageCommand.pending(
                "stale-material-ready", task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_DELIVERY", "MATERIAL_READY", 0, 3, 0L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.GENERIC,
                "_default", "", "P1", System.currentTimeMillis());

        registry.transitionRequirementFenced(
                task, RdTaskStatus.MATERIAL_READY, "", "", "", "");
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> null);

        assertThrows(IllegalStateException.class, () -> engine.executeStage(stale));
        assertEquals(RdTaskStatus.MATERIAL_READY, registry.getRequirementTask(task.taskId()).status());
    }

    @Test
    void falseSuccessQaPassedButGateBuildPendingRejectsDeterministicReview() {
        ReviewGateFixture fixture = reviewGateFixture(List.of("the change is accepted"), successfulDeliveryJson());

        RequirementStageExecutionPlan proposal = fixture.engine().planStage(
                deliveryCommand(fixture.task(), "DETERMINISTIC_REVIEW", "false-success-review"));

        assertEquals(RdTaskStatus.REJECTED, proposal.postStatus());
        assertEquals(CommandDisposition.TERMINAL_FAILURE, proposal.commandDisposition());
        assertTrue(proposal.continuation().isTerminal());
        assertFalse("AI_REVIEW".equals(proposal.continuation().stage()));
        String reason = proposal.mutations().getLast().errorMessage();
        assertTrue(reason.contains("GATE-BUILD"), reason);
    }

    @Test
    void missingCurrentAcceptanceLeavesAc002PendingAndRejectsDeterministicReview() {
        ReviewGateFixture fixture = reviewGateFixture(
                List.of("登录成功", "下单成功"), successfulDeliveryJson());
        completeBlockingExcept(fixture.audited(), fixture.task(), "AC-002");

        RequirementStageExecutionPlan proposal = fixture.engine().planStage(
                deliveryCommand(fixture.task(), "DETERMINISTIC_REVIEW", "missing-ac-review"));

        assertEquals(RdTaskStatus.REJECTED, proposal.postStatus());
        assertTrue(proposal.continuation().isTerminal());
        assertTrue(proposal.mutations().getLast().errorMessage().contains("AC-002"));
    }

    @Test
    void publicationFailsClosedWithoutPushingWhenCompletionGateRejects() {
        ReviewGateFixture fixture = reviewGateFixture(List.of("the change is accepted"), successfulDeliveryJson());
        RdRequirementTask validating = fixture.registry().transitionRequirementFenced(
                fixture.task(), RdTaskStatus.VALIDATING, "", successfulDeliveryJson(), "", "");
        AtomicBoolean pushed = new AtomicBoolean(false);
        fixture.engine().setBranchPublisher(command -> {
            pushed.set(true);
            return com.wish.rd.engine.requirement.model.RequirementBranchPublication.success(
                    command.taskId(), "branch-sha", "{}");
        });

        RequirementStageExecutionPlan proposal = fixture.engine().planStage(
                deliveryCommand(validating, "PUBLICATION", "gate-blocked-publication"));

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, proposal.postStatus());
        assertEquals(CommandDisposition.TERMINAL_FAILURE, proposal.commandDisposition());
        assertTrue(proposal.continuation().isTerminal());
        assertFalse(pushed.get());
    }

    @Test
    void completionPlanBindsAuditRunAndCompletesArtPrWhenGatePasses() {
        ReviewGateFixture fixture = reviewGateFixture(List.of("the change is accepted"), successfulDeliveryJson());
        completeBlockingExcept(fixture.audited(), fixture.task());
        RdRequirementTask reporting = advanceToReporting(fixture.registry(), fixture.task(),
                successfulDeliveryJson(), "https://example.test/pull/42");

        RequirementStageExecutionPlan proposal = fixture.engine().planStage(
                deliveryCommand(reporting, "COMPLETION", "completion-bound"));

        assertEquals(RdTaskStatus.COMPLETED, proposal.postStatus());
        assertNotNull(proposal.auditedStateMutation());
        assertEquals(AuditedRecordStatus.COMPLETED,
                proposal.auditedStateMutation().nextState().record("ART-PR").status());
        assertEquals(EvidenceSourceKind.PUBLICATION_LEDGER,
                proposal.auditedStateMutation().nextState().record("ART-PR").evidenceRefs().getFirst().sourceKind());
        CompletionBinding binding = proposal.auditedStateMutation().completionBinding();
        assertNotNull(binding);
        assertEquals(proposal.auditedStateMutation().auditRun().auditRunId(), binding.auditRunId());
        assertEquals(proposal.auditedStateMutation().nextState().stateVersion(), binding.stateVersion());
        assertEquals(proposal.auditedStateMutation().nextState().stateHash(), binding.stateHash());
    }

    @Test
    void completionPlanFailsNeedsHumanWhenHeadIsDegraded() {
        ReviewGateFixture fixture = reviewGateFixture(List.of("the change is accepted"), successfulDeliveryJson());
        completeBlockingExcept(fixture.audited(), fixture.task());
        demoteRecord(fixture.audited(), fixture.task(), "GATE-BUILD");
        RdRequirementTask reporting = advanceToReporting(fixture.registry(), fixture.task(),
                successfulDeliveryJson(), "https://example.test/pull/42");

        RequirementStageExecutionPlan proposal = fixture.engine().planStage(
                deliveryCommand(reporting, "COMPLETION", "completion-degraded"));

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, proposal.postStatus());
        assertEquals(CommandDisposition.TERMINAL_FAILURE, proposal.commandDisposition());
        assertTrue(proposal.mutations().getLast().errorMessage().contains("GATE-BUILD"));
    }

    @Test
    void shadowModeKeepsTodaysDeterministicReviewOutcomeAndRecordsBlockers() {
        ReviewGateFixture fixture = reviewGateFixture(List.of("the change is accepted"), successfulDeliveryJson());
        fixture.engine().setAuditedWritebackGateMode(AuditedWritebackGateMode.SHADOW);

        RequirementStageExecutionPlan proposal = fixture.engine().planStage(
                deliveryCommand(fixture.task(), "DETERMINISTIC_REVIEW", "shadow-review"));

        assertEquals(CommandDisposition.SUCCEEDED, proposal.commandDisposition());
        assertEquals(new ContinuationSpec("REQUIREMENT_DELIVERY", "AI_REVIEW"), proposal.continuation());
        assertNotNull(proposal.auditedStateMutation());
        assertTrue(proposal.auditedStateMutation().auditRun().blockers().contains("GATE-BUILD"));
    }

    @Test
    void legacyZeroFenceCommandCannotMutateTheTaskOrTimeline() {
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), events, SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "zero fence", "P1", "https://github.com/example/repo", "example", "repo", "main",
                "result", List.of("accepted"), false));
        RequirementStageCommand legacyRead = new RequirementStageCommand(
                "zero-fence", task.taskId(), task.version(), 0L, "REQUIREMENT_DELIVERY",
                "MATERIAL_COLLECTING", 0, 3, 0L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.GENERIC,
                java.util.Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.GENERIC),
                "_default", "", 1, RequirementStageCommand.Status.PENDING,
                "", 0L, System.currentTimeMillis(), "", System.currentTimeMillis(), System.currentTimeMillis());
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> null);
        int timelineBefore = events.listByTask(task.taskId()).size();

        assertThrows(IllegalStateException.class, () -> engine.executeStage(legacyRead));

        assertEquals(task, registry.getRequirementTask(task.taskId()));
        assertEquals(timelineBefore, events.listByTask(task.taskId()).size());
    }

    @Test
    void legacyZeroFenceSnapshotCannotMutateTheTaskOrTimeline() {
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = spy(new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), events, SnowflakeIdGenerator.defaultGenerator()));
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "zero snapshot fence", "P1", "https://github.com/example/repo", "example", "repo", "main",
                "result", List.of("accepted"), false));
        RdRequirementTask legacySnapshot = task.withConcurrency(task.version(), 0L);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "positive-command-fence", task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 0, 3, 0L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.GENERIC,
                "_default", "", "P1", System.currentTimeMillis());
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> null);
        int timelineBefore = events.listByTask(task.taskId()).size();
        doReturn(legacySnapshot).when(registry).getTask(task.taskId());

        assertThrows(IllegalStateException.class, () -> engine.executeStage(command));

        assertEquals(task, registry.getRequirementTask(task.taskId()));
        assertEquals(timelineBefore, events.listByTask(task.taskId()).size());
        verify(registry, never()).transitionRequirementFenced(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    private static RdRequirementTask executingTask(RagStreamTaskRegistry registry, String title) {
        return executingTask(registry, title, List.of(), "");
    }

    private static RdRequirementTask executingTask(
            RagStreamTaskRegistry registry,
            String title,
            List<String> acceptanceCriteria,
            String executingPayload
    ) {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                title, "P1", "https://github.com/example/repo", "example", "repo", "main",
                "result", acceptanceCriteria, false));
        String plan = "{\"taskId\":\"" + task.taskId() + "\",\"implementationSteps\":[\"frozen\"],"
                + "\"acceptanceCriteria\":[],\"suggestedValidationCommands\":[]}";
        for (RdTaskStatus status : List.of(
                RdTaskStatus.MATERIAL_COLLECTING, RdTaskStatus.MATERIAL_READY,
                RdTaskStatus.CONTEXT_BUILDING, RdTaskStatus.CONTEXT_READY,
                RdTaskStatus.PLAN_GENERATING, RdTaskStatus.PLAN_GENERATED,
                RdTaskStatus.WAITING_POLICY)) {
            task = registry.transitionRequirementFenced(task, status, "", plan, "", "");
        }
        String payload = executingPayload == null || executingPayload.isBlank() ? plan : executingPayload;
        return registry.transitionRequirementFenced(task, RdTaskStatus.EXECUTING, "", payload, "", "");
    }

    private static ReviewGateFixture reviewGateFixture(List<String> criteria, String deliveryJson) {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = executingTask(registry, "audited gate " + criteria.size(), criteria, deliveryJson);
        InMemoryAuditedTaskStateStore audited = new InMemoryAuditedTaskStateStore();
        new AuditedTaskStatePolicyBootstrap(audited).initializeIfAbsent(task);
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> {
                    throw new AssertionError("delivery review must not execute roles");
                });
        engine.setAuditedTaskStateStore(audited);
        return new ReviewGateFixture(engine, registry, task, audited);
    }

    private static RequirementStageCommand deliveryCommand(RdRequirementTask task, String stage, String commandId) {
        return RequirementStageCommand.pending(
                commandId, task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_DELIVERY", stage, 0, 3,
                System.currentTimeMillis() + 60_000L,
                ScheduleResourceClass.GENERIC,
                Set.of(ScheduleResourceClass.GENERIC),
                "_default", "", "P1", System.currentTimeMillis());
    }

    private static void completeBlockingExcept(
            InMemoryAuditedTaskStateStore store,
            RdRequirementTask task,
            String... exceptIds
    ) {
        Set<String> skip = Set.of(exceptIds);
        AuditedTaskState head = store.head(task.taskId()).orElseThrow();
        EvidenceRef evidence = new EvidenceRef(
                "audit-seed-" + task.taskId(),
                EvidenceSourceKind.HOST_VERIFICATION,
                "host-verification://artifacts/seed-" + task.taskId(),
                "sha256:" + "a".repeat(64));
        List<AuditedRecord> nextRecords = new ArrayList<>();
        List<String> verified = new ArrayList<>();
        for (AuditedRecord record : head.records()) {
            if (record.blocking() && !skip.contains(record.id())) {
                nextRecords.add(record.withStatus(AuditedRecordStatus.COMPLETED, List.of(evidence), ""));
                verified.add(record.id());
            } else {
                nextRecords.add(record);
            }
        }
        AuditRun run = new AuditRun(
                "audit-seed-" + task.taskId(),
                task.taskId(),
                "",
                "HOST_VERIFY",
                "seed-command-" + task.taskId(),
                skip.isEmpty() ? AuditCompletion.COMPLETE : AuditCompletion.INCOMPLETE,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                verified,
                List.of(exceptIds),
                List.of(),
                List.of(exceptIds),
                List.of(evidence.uri()),
                1L);
        AuditedTaskState next = new AuditedTaskStateCodec().seal(new AuditedTaskState(
                head.taskId(),
                head.stateVersion() + 1L,
                "",
                head.contractRef(),
                nextRecords,
                run.auditRunId()));
        store.appendRevision(next.stateVersion(), next, run);
    }

    private static void demoteRecord(InMemoryAuditedTaskStateStore store, RdRequirementTask task, String recordId) {
        AuditedTaskState head = store.head(task.taskId()).orElseThrow();
        List<AuditedRecord> nextRecords = new ArrayList<>();
        for (AuditedRecord record : head.records()) {
            if (record.id().equals(recordId)) {
                nextRecords.add(record.withStatus(AuditedRecordStatus.PENDING, List.of(), ""));
            } else {
                nextRecords.add(record);
            }
        }
        AuditRun run = new AuditRun(
                "audit-demote-" + task.taskId(),
                task.taskId(),
                "",
                "HOST_VERIFY",
                "demote-command-" + task.taskId(),
                AuditCompletion.INCOMPLETE,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                List.of(),
                List.of(recordId),
                List.of(),
                List.of(recordId),
                List.of(),
                2L);
        AuditedTaskState next = new AuditedTaskStateCodec().seal(new AuditedTaskState(
                head.taskId(),
                head.stateVersion() + 1L,
                "",
                head.contractRef(),
                nextRecords,
                run.auditRunId()));
        store.appendRevision(next.stateVersion(), next, run);
    }

    private static RdRequirementTask advanceToReporting(
            RagStreamTaskRegistry registry,
            RdRequirementTask task,
            String deliveryJson,
            String pullRequestUrl
    ) {
        for (RdTaskStatus status : List.of(
                RdTaskStatus.VALIDATING, RdTaskStatus.PR_CREATING, RdTaskStatus.COMMITTED, RdTaskStatus.REPORTING)) {
            task = registry.transitionRequirementFenced(task, status, "", deliveryJson, pullRequestUrl, "");
        }
        return task;
    }

    private static String successfulDeliveryJson() {
        return """
                {"status":"SUCCESS","multiAgentStatus":"SUCCESS",
                "changedFiles":["src/App.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW",
                "multiAgentStages":[
                {"role":"REQUIREMENT_REVIEWER","success":true},
                {"role":"SOLUTION_ARCHITECT","success":true},
                {"role":"CODING_AGENT","success":true,"candidatePatch":{"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","bytes":1,"artifactUri":"s3://patch"},
                 "resultJson":{"summary":"implemented","prBody":"body","changedFiles":["src/App.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW"}},
                {"role":"QA_AGENT","success":true,"resultJson":{"status":"PASSED","failureCategory":"NONE","retryRecommendation":"NONE",
                "browserValidation":{"required":false,"performed":false,"decisionSource":"NOT_APPLICABLE","baseUrl":"","browser":"chromium","viewports":[]},
                "acceptanceResults":[{"criteria":"current","scope":"CURRENT","command":"test","status":"PASSED","exitCode":0,"durationMillis":0,"logArtifactId":"current.log","evidenceArtifactIds":["https://evidence.example/current"]},{"criteria":"regression","scope":"REGRESSION","command":"test","status":"PASSED","exitCode":0,"durationMillis":0,"logArtifactId":"regression.log","evidenceArtifactIds":["regression.log"]}],"evidenceManifestArtifactId":"manifest.json"}}]}
                """;
    }

    private record ReviewGateFixture(
            RequirementDeliveryEngine engine,
            RagStreamTaskRegistry registry,
            RdRequirementTask task,
            InMemoryAuditedTaskStateStore audited
    ) {
    }

    private static HostVerifyFixture hostVerifyFixture(
            HostVerificationStatus status,
            boolean docsOnly,
            String failureCategory,
            String errorMessage,
            int attemptNo,
            boolean withEvidence
    ) {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = executingTask(registry, "host verify " + status);
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        long now = System.currentTimeMillis();
        stages.save(AgentStageRun.pending("coding-1", task.taskId(), AgentRole.CODING_AGENT, 1,
                        task.taskId() + ":CODING_AGENT:1", now)
                .withStatus(AgentStageStatus.SUCCEEDED, "", "", now));
        InMemoryHostVerificationStore store = new InMemoryHostVerificationStore();
        HostVerificationRun run = new HostVerificationRun(
                "verify-1", task.taskId(), "coding-1", "", attemptNo, status, docsOnly,
                failureCategory, errorMessage, 0, now, now, now);
        store.create(run);
        if (withEvidence) {
            store.appendArtifact(new HostVerificationArtifact(
                    "build-log", task.taskId(), "verify-1", "VERIFY_BUILD_LOG",
                    "verify-evidence/build.log", "s3://verify/build.log", "text/plain", 12L,
                    "sha256:" + "a".repeat(64), now));
            store.appendArtifact(new HostVerificationArtifact(
                    "static-log", task.taskId(), "verify-1", "VERIFY_STATIC_LOG",
                    "verify-evidence/static.log", "s3://verify/static.log", "text/plain", 12L,
                    "sha256:" + "b".repeat(64), now));
        }
        InMemoryAuditedTaskStateStore audited = new InMemoryAuditedTaskStateStore();
        new AuditedTaskStatePolicyBootstrap(audited).initializeIfAbsent(task);
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, new InMemoryTaskMaterialStore(), request -> {
                    throw new AssertionError("HOST_VERIFY must not call the role executor");
                },
                new RequirementContextBuilder(),
                new RequirementPlanGenerator(),
                new RuleBasedRequirementPolicyGate(),
                new AgentStagePlanner(SnowflakeIdGenerator.defaultGenerator()::nextIdString),
                stages);
        engine.setHostVerificationPort((ignoredTask, ignoredCoding, ignoredPlan, ignoredUsed) -> run);
        engine.setHostVerificationStore(store);
        engine.setAuditedTaskStateStore(audited);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "host-verify-command", task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_DELIVERY", "HOST_VERIFY", 0, 3, now + 60_000L,
                ScheduleResourceClass.DOCKER,
                Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER),
                "_default", "provider", "P1", now);
        return new HostVerifyFixture(engine, command, task, store);
    }

    private record HostVerifyFixture(
            RequirementDeliveryEngine engine,
            RequirementStageCommand command,
            RdRequirementTask task,
            InMemoryHostVerificationStore store
    ) {
    }

    private static RequirementStageCommand roleCommand(
            RdRequirementTask task, String policyRunId, AgentRole role, String commandId
    ) {
        return RequirementStageCommand.pending(
                commandId, task.taskId(), task.version(), task.fencingToken(),
                role.name(), "ROLE_EXECUTION:" + role.name(), 0, 3,
                System.currentTimeMillis() + 60_000L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER,
                Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER),
                "_default", "provider", "P1", policyRunId, System.currentTimeMillis());
    }

    private static RequirementPolicyRun appliedRun(
            String id, String taskId, long boundVersion, long boundFence
    ) {
        String plan = RequirementPolicyRun.canonicalizeJson(
                "{\"taskId\":\"" + taskId + "\",\"implementationSteps\":[\"frozen\"],"
                        + "\"acceptanceCriteria\":[],\"suggestedValidationCommands\":[]}");
        String policy = RequirementPolicyRun.canonicalizeJson(
                "{\"policyAction\":\"ALLOWED\",\"riskLevel\":\"LOW\","
                        + "\"reason\":\"frozen-policy-reason\"}");
        return new RequirementPolicyRun(
                id, taskId, Math.subtractExact(boundVersion, 2L), Math.subtractExact(boundFence, 2L),
                plan, RequirementPolicyRun.canonicalJsonDigest(plan), policy,
                RequirementPolicyRun.canonicalJsonDigest(policy), "ALLOWED",
                RequirementPolicyRunState.APPLIED, boundVersion, boundFence,
                null, null, "", "", "", 0L, "", "apply-command", 1L, 2L, 1L, 1L);
    }

    private static final class CommandScopedPiRemediationResolver implements RequirementExecutionProfileResolverPort {
        @Override
        public RequirementExecutionProfileResolution resolve(
                RdRequirementTask task, AgentRole role, String stageRunId, int attemptNo
        ) {
            return RequirementExecutionProfileResolution.of(
                    "agent-profile-" + stageRunId, snapshotJson(task, role, stageRunId, attemptNo));
        }

        @Override
        public AgentExecutionProfileSnapshot prepareSnapshot(
                RdRequirementTask task,
                AgentRole role,
                String stageRunId,
                int attemptNo,
                AgentRuntimeCapability requiredCapability
        ) {
            String json = snapshotJson(task, role, stageRunId, attemptNo);
            AgentExecutionProfileSnapshot snapshot = new AgentExecutionProfileSnapshot(
                    "agent-profile-" + stageRunId,
                    stageRunId,
                    task.taskId(),
                    role.name(),
                    attemptNo,
                    AgentRuntimeType.PI,
                    json,
                    AgentExecutionProfileSnapshot.sha256(json),
                    System.currentTimeMillis()
            );
            if (!snapshot.hasCapability(requiredCapability)) {
                throw new IllegalStateException("prepared target profile is not eligible PI runtime");
            }
            return snapshot;
        }

        private static String snapshotJson(
                RdRequirementTask task, AgentRole role, String stageRunId, int attemptNo
        ) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("snapshotVersion", 1);
            value.put("stageRunId", stageRunId);
            value.put("taskId", task.taskId());
            value.put("role", role.name());
            value.put("attemptNo", attemptNo);
            value.put("runtimeType", "PI");
            value.put("profileId", "pi-test-" + role.name().toLowerCase());
            value.put("profileVersion", 1L);
            value.put("capabilities", List.of("PI_AGENT_STATE_V2", "PI_QA_REMEDIATION_V2"));
            return AgentManifestCanonicalJson.canonicalJson(value);
        }
    }
}
