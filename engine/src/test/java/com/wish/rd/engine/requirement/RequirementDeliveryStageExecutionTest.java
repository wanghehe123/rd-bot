package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.AgentStagePlanner;
import com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.job.model.CommandDisposition;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                title, "P1", "https://github.com/example/repo", "example", "repo", "main",
                "result", List.of(), false));
        String plan = "{\"taskId\":\"" + task.taskId() + "\",\"implementationSteps\":[\"frozen\"],"
                + "\"acceptanceCriteria\":[],\"suggestedValidationCommands\":[]}";
        for (RdTaskStatus status : List.of(
                RdTaskStatus.MATERIAL_COLLECTING, RdTaskStatus.MATERIAL_READY,
                RdTaskStatus.CONTEXT_BUILDING, RdTaskStatus.CONTEXT_READY,
                RdTaskStatus.PLAN_GENERATING, RdTaskStatus.PLAN_GENERATED,
                RdTaskStatus.WAITING_POLICY, RdTaskStatus.EXECUTING)) {
            task = registry.transitionRequirementFenced(task, status, "", plan, "", "");
        }
        return task;
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
