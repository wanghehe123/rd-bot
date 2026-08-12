package com.wish.rd.engine.requirement;

import com.wish.rd.engine.requirement.job.model.CommandDisposition;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.job.model.RequirementTaskMutation;
import com.wish.rd.engine.requirement.model.RequirementContextPackage;
import com.wish.rd.engine.requirement.model.RequirementPlan;
import com.wish.rd.engine.requirement.model.RequirementBranchPublication;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationProposal;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementOperationId;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementDeliveryStageProposalTest {

    @Test
    void materialCollectingProposesOneEdgeWithoutChangingTheTask() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        assertSimplePlan(fixture, fixture.task(), "MATERIAL_COLLECTING", RdTaskStatus.CREATED,
                RdTaskStatus.MATERIAL_COLLECTING, "MATERIAL_READY", "");
    }

    @Test
    void materialReadyProposesOneEdgeWithoutChangingTheTask() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = advance(fixture, RdTaskStatus.MATERIAL_COLLECTING);
        assertSimplePlan(fixture, task, "MATERIAL_READY", RdTaskStatus.MATERIAL_COLLECTING,
                RdTaskStatus.MATERIAL_READY, "CONTEXT_BUILDING", "");
    }

    @Test
    void contextBuildingProposesOneEdgeWithoutChangingTheTask() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = advance(fixture, RdTaskStatus.MATERIAL_COLLECTING, RdTaskStatus.MATERIAL_READY);
        assertSimplePlan(fixture, task, "CONTEXT_BUILDING", RdTaskStatus.MATERIAL_READY,
                RdTaskStatus.CONTEXT_BUILDING, "CONTEXT_READY", "");
    }

    @Test
    void contextReadyProposesBuiltContextWithoutChangingTheTask() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = advance(fixture, RdTaskStatus.MATERIAL_COLLECTING, RdTaskStatus.MATERIAL_READY,
                RdTaskStatus.CONTEXT_BUILDING);
        String contextJson = new RequirementContextBuilder().build(task, List.of()).toJson();
        assertSimplePlan(fixture, task, "CONTEXT_READY", RdTaskStatus.CONTEXT_BUILDING,
                RdTaskStatus.CONTEXT_READY, "PLAN_GENERATING", contextJson);
    }

    @Test
    void planGeneratingProposesOneEdgeWithoutChangingTheTask() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = advance(fixture, RdTaskStatus.MATERIAL_COLLECTING, RdTaskStatus.MATERIAL_READY,
                RdTaskStatus.CONTEXT_BUILDING, RdTaskStatus.CONTEXT_READY);
        assertSimplePlan(fixture, task, "PLAN_GENERATING", RdTaskStatus.CONTEXT_READY,
                RdTaskStatus.PLAN_GENERATING, "PLAN_GENERATED", "");
    }

    @Test
    void planGeneratedProposesBuiltPlanWithoutChangingTheTask() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = advance(fixture, RdTaskStatus.MATERIAL_COLLECTING, RdTaskStatus.MATERIAL_READY,
                RdTaskStatus.CONTEXT_BUILDING, RdTaskStatus.CONTEXT_READY, RdTaskStatus.PLAN_GENERATING);
        String planJson = new RequirementPlanGenerator().generate(task,
                new RequirementContextBuilder().build(task, List.of())).toJson();
        assertSimplePlan(fixture, task, "PLAN_GENERATED", RdTaskStatus.PLAN_GENERATING,
                RdTaskStatus.PLAN_GENERATED, "POLICY_EVALUATE", planJson);
    }

    @Test
    void policyEvaluationUsesTheFrozenPlanWithoutWritingOrRegeneratingIt() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask generating = advance(fixture, RdTaskStatus.MATERIAL_COLLECTING,
                RdTaskStatus.MATERIAL_READY, RdTaskStatus.CONTEXT_BUILDING, RdTaskStatus.CONTEXT_READY,
                RdTaskStatus.PLAN_GENERATING);
        String frozenPlan = new RequirementPlan(
                generating.taskId(), List.of("frozen-only-step"), List.of("frozen acceptance"),
                List.of("./mvnw test")).toJson();
        RdRequirementTask planned = fixture.registry().transitionRequirementFenced(
                generating, RdTaskStatus.PLAN_GENERATED, "", frozenPlan, "", "");
        Snapshot before = Snapshot.capture(fixture, planned);

        RequirementPolicyEvaluationProposal proposal = fixture.engine().evaluateFrozenPolicy(
                command(planned, "POLICY_EVALUATE"));

        assertEquals(planned.taskId(), proposal.taskId());
        assertEquals(planned.version(), proposal.taskVersion());
        assertEquals(planned.fencingToken(), proposal.fencingToken());
        assertTrue(proposal.planJson().contains("frozen-only-step"));
        assertEquals("ALLOWED", proposal.policyAction());
        assertTrue(proposal.policyJson().startsWith("{\"policyAction\""));
        assertUnchanged(fixture, before);
    }

    @Test
    void policyEvaluationRejectsInvalidOrStaleFrozenPlanWithoutWriting() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask planned = advanceToPlanGenerated(fixture);
        Snapshot before = Snapshot.capture(fixture, planned);

        assertThrows(IllegalStateException.class, () -> fixture.engine().evaluateFrozenPolicy(
                command(planned, "POLICY_EVALUATE")));
        assertThrows(IllegalStateException.class, () -> fixture.engine().evaluateFrozenPolicy(
                RequirementStageCommand.pending("stale-policy", planned.taskId(), planned.version() + 1L,
                        planned.fencingToken(), "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", 0, 3, 0L,
                        ScheduleResourceClass.GENERIC, "_default", "", planned.priority(),
                        System.currentTimeMillis())));

        assertUnchanged(fixture, before);
    }

    @Test
    void allowedPolicyProposesBothEdgesAndFirstRoleContinuationWithoutChangingTheTask() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = advanceToPlanGenerated(fixture);
        Snapshot before = Snapshot.capture(fixture, task);

        RequirementStageExecutionPlan plan = fixture.engine().planStage(command(task, "POLICY"));

        assertEquals(List.of(
                mutation(RdTaskStatus.PLAN_GENERATED, RdTaskStatus.WAITING_POLICY,
                        "", "{\"policyAction\":\"ALLOWED\",\"riskLevel\":\"LOW\",\"reason\":\"低风险需求，允许进入沙箱执行\"}", ""),
                mutation(RdTaskStatus.WAITING_POLICY, RdTaskStatus.EXECUTING,
                        expectedAllowedPrompt(task, List.of()), "", "")
        ), plan.mutations());
        assertEquals(CommandDisposition.SUCCEEDED, plan.commandDisposition());
        assertEquals(new ContinuationSpec("REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER"),
                plan.continuation());
        assertUnchanged(fixture, before);
    }

    @Test
    void approvalPolicyProposesBothEdgesAndTerminalContinuationWithoutChangingTheTask() {
        Fixture fixture = fixture("payment migration", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = advanceToPlanGenerated(fixture);
        Snapshot before = Snapshot.capture(fixture, task);

        RequirementStageExecutionPlan plan = fixture.engine().planStage(command(task, "POLICY"));

        assertEquals(List.of(
                mutation(RdTaskStatus.PLAN_GENERATED, RdTaskStatus.WAITING_POLICY,
                        "", "{\"policyAction\":\"WAITING_APPROVAL\",\"riskLevel\":\"HIGH\",\"reason\":\"需求涉及高风险操作，等待人工审批：payment\"}", ""),
                mutation(RdTaskStatus.WAITING_POLICY, RdTaskStatus.WAITING_APPROVAL,
                        "", "{\"policyAction\":\"WAITING_APPROVAL\",\"riskLevel\":\"HIGH\",\"reason\":\"需求涉及高风险操作，等待人工审批：payment\"}", "")
        ), plan.mutations());
        assertEquals(CommandDisposition.SUCCEEDED, plan.commandDisposition());
        assertEquals(ContinuationSpec.terminal(), plan.continuation());
        assertUnchanged(fixture, before);
    }

    @Test
    void deniedPolicyProposesBothEdgesAndTerminalFailureWithoutChangingTheTask() {
        Fixture fixture = fixture("生产数据迁移", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = advanceToPlanGenerated(fixture);
        Snapshot before = Snapshot.capture(fixture, task);

        RequirementStageExecutionPlan plan = fixture.engine().planStage(command(task, "POLICY"));

        String decision = "{\"policyAction\":\"UNSAFE\",\"riskLevel\":\"HIGH\",\"reason\":\"需求包含生产数据或密钥相关高危操作：生产数据\"}";
        String blocked = "{\"status\":\"UNSAFE\",\"riskLevel\":\"HIGH\",\"errorMessage\":\"需求包含生产数据或密钥相关高危操作：生产数据\",\"policyDecision\":" + decision + "}";
        assertEquals(List.of(
                mutation(RdTaskStatus.PLAN_GENERATED, RdTaskStatus.WAITING_POLICY, "", decision, ""),
                mutation(RdTaskStatus.WAITING_POLICY, RdTaskStatus.FAILED_NEEDS_HUMAN,
                        "", blocked, "需求包含生产数据或密钥相关高危操作：生产数据")
        ), plan.mutations());
        assertEquals(CommandDisposition.TERMINAL_FAILURE, plan.commandDisposition());
        assertEquals(ContinuationSpec.terminal(), plan.continuation());
        assertUnchanged(fixture, before);
    }

    @Test
    void staleVersionOrFencingIsRejectedBeforeAProposalCanChangeTheTask() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = fixture.task();
        Snapshot before = Snapshot.capture(fixture, task);

        assertThrows(IllegalStateException.class, () -> fixture.engine().planStage(RequirementStageCommand.pending(
                "stale-version", task.taskId(), task.version() + 1L, task.fencingToken(),
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 0, 3, 0L,
                ScheduleResourceClass.GENERIC, "_default", "", "P2", System.currentTimeMillis())));
        assertThrows(IllegalStateException.class, () -> fixture.engine().planStage(RequirementStageCommand.pending(
                "stale-fence", task.taskId(), task.version(), task.fencingToken() + 1L,
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 0, 3, 0L,
                ScheduleResourceClass.GENERIC, "_default", "", "P2", System.currentTimeMillis())));

        assertUnchanged(fixture, before);
    }

    @Test
    void versionZeroWithPositiveFencePlansButZeroFenceFailsClosedWithoutWriting() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = fixture.task();
        Snapshot before = Snapshot.capture(fixture, task);

        assertEquals(0L, task.version());
        assertEquals(1L, task.fencingToken());
        assertEquals(RdTaskStatus.MATERIAL_COLLECTING,
                fixture.engine().planStage(command(task, "MATERIAL_COLLECTING")).postStatus());
        RequirementStageCommand legacyZeroFence = new RequirementStageCommand(
                "zero-fence", task.taskId(), task.version(), 0L,
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 0, 3, 0L,
                ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC), "_default", "", 1,
                RequirementStageCommand.Status.PENDING, "", 0L, System.currentTimeMillis(), "",
                System.currentTimeMillis(), System.currentTimeMillis());
        assertThrows(IllegalStateException.class, () -> fixture.engine().planStage(legacyZeroFence));

        assertUnchanged(fixture, before);
    }

    @Test
    void unsupportedProposalStageDoesNotWriteTheTaskOrTimeline() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        Snapshot before = Snapshot.capture(fixture, fixture.task());

        assertThrows(IllegalArgumentException.class, () -> fixture.engine().planStage(command(fixture.task(), "ROLE_EXECUTION")));

        assertUnchanged(fixture, before);
    }

    @Test
    void materialDerivedContextAndPolicyRemainReadOnly() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        fixture.materialStore().save(material(fixture.task().taskId(), "material preview with secret"));
        RdRequirementTask contextBuilding = advance(fixture, RdTaskStatus.MATERIAL_COLLECTING,
                RdTaskStatus.MATERIAL_READY, RdTaskStatus.CONTEXT_BUILDING);
        Snapshot beforeContext = Snapshot.capture(fixture, contextBuilding);

        RequirementStageExecutionPlan contextPlan = fixture.engine().planStage(command(contextBuilding, "CONTEXT_READY"));

        assertTrue(contextPlan.mutations().getFirst().executionResultJson().contains("material preview with secret"));
        assertUnchanged(fixture, beforeContext);

        Fixture policyFixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        policyFixture.materialStore().save(material(policyFixture.task().taskId(), "material preview with secret"));
        RdRequirementTask planned = advanceToPlanGenerated(policyFixture);
        Snapshot beforePolicy = Snapshot.capture(policyFixture, planned);
        RequirementStageExecutionPlan policyPlan = policyFixture.engine().planStage(command(planned, "POLICY"));

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, policyPlan.postStatus());
        assertUnchanged(policyFixture, beforePolicy);
    }

    @Test
    void blankResultMutationsPreservePreviouslyPlannedContextAcrossSequentialStages() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask contextBuilding = advance(fixture, RdTaskStatus.MATERIAL_COLLECTING,
                RdTaskStatus.MATERIAL_READY, RdTaskStatus.CONTEXT_BUILDING);
        RequirementStageExecutionPlan contextPlan = fixture.engine().planStage(command(contextBuilding, "CONTEXT_READY"));
        String contextJson = contextPlan.mutations().getFirst().executionResultJson();
        RdRequirementTask contextReady = fixture.registry().transitionRequirementFenced(
                contextBuilding, RdTaskStatus.CONTEXT_READY, "", contextJson, "", "");

        RequirementStageExecutionPlan generatingPlan = fixture.engine().planStage(command(contextReady, "PLAN_GENERATING"));

        assertEquals("", generatingPlan.mutations().getFirst().executionResultJson());
        assertEquals(contextJson, preserved(contextReady.executionResultJson(),
                generatingPlan.mutations().getFirst().executionResultJson()));
        RdRequirementTask planGenerated = advanceFrom(fixture, contextReady,
                RdTaskStatus.PLAN_GENERATING, RdTaskStatus.PLAN_GENERATED);
        RequirementStageExecutionPlan allowedPolicy = fixture.engine().planStage(command(planGenerated, "POLICY"));
        assertEquals(allowedPolicy.mutations().getFirst().executionResultJson(), preserved(
                allowedPolicy.mutations().getFirst().executionResultJson(),
                allowedPolicy.mutations().get(1).executionResultJson()));
    }

    @Test
    void deterministicReviewProposesItsApprovedOutcomeWithoutWritingTaskState() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = advanceToExecuting(fixture, successfulDeliveryJson());
        Snapshot before = Snapshot.capture(fixture, task);

        RequirementStageExecutionPlan plan = fixture.engine().planStage(command(task, "DETERMINISTIC_REVIEW"));

        assertEquals(RdTaskStatus.VALIDATING, plan.postStatus());
        assertEquals(CommandDisposition.SUCCEEDED, plan.commandDisposition());
        assertEquals(new ContinuationSpec("REQUIREMENT_DELIVERY", "AI_REVIEW"), plan.continuation());
        assertTrue(plan.mutations().getLast().executionResultJson().contains("\"approved\":true"));
        assertUnchanged(fixture, before);
    }

    @Test
    void publicationWaitReconcileFailureCarriesNonFinalizablePublicationReceipt() {
        PublicationFixture publication = publicationFixture();
        Fixture fixture = publication.fixture();
        RdRequirementTask validating = fixture.registry().transitionRequirementFenced(
                advanceToExecuting(fixture, successfulDeliveryJson()), RdTaskStatus.VALIDATING,
                "", successfulDeliveryJson(), "", "");
        String patchSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String workBranch = "requirement/" + validating.taskId();
        String operationId = RequirementOperationId.of(
                validating.taskId(), validating.baseBranch(), workBranch, patchSha);
        publication.ledger().prepare(new RequirementPublicationPrepareCommand(
                operationId, validating.taskId(), "stage-seed", validating.baseBranch(), workBranch, patchSha));
        publication.ledger().markUnknownRemoteResult(operationId, "GitHub POST timed out");

        RequirementStageExecutionPlan plan = fixture.engine().planStage(command(validating, "PUBLICATION"));

        assertEquals(RdTaskStatus.FAILED_RETRYABLE, plan.postStatus());
        assertEquals(CommandDisposition.RETRYABLE_TECHNICAL_FAILURE, plan.commandDisposition());
        assertEquals(ExternalEffectReceipt.Kind.PUBLICATION, plan.externalEffectReceipt().kind());
        assertEquals(operationId, plan.externalEffectReceipt().operationId());
        assertEquals("UNKNOWN_REMOTE_RESULT", plan.externalEffectReceipt().durableState());
        assertFalse(plan.externalEffectReceipt().isFinalizable());
    }

    @Test
    void publicationNeedsHumanFailureCarriesNonFinalizablePublicationReceipt() {
        PublicationFixture publication = publicationFixture();
        Fixture fixture = publication.fixture();
        RdRequirementTask validating = fixture.registry().transitionRequirementFenced(
                advanceToExecuting(fixture, successfulDeliveryJson()), RdTaskStatus.VALIDATING,
                "", successfulDeliveryJson(), "", "");
        String patchSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String workBranch = "requirement/" + validating.taskId();
        String operationId = RequirementOperationId.of(
                validating.taskId(), validating.baseBranch(), workBranch, patchSha);
        publication.ledger().prepare(new RequirementPublicationPrepareCommand(
                operationId, validating.taskId(), "stage-seed", validating.baseBranch(), workBranch, patchSha));
        publication.ledger().markNeedsHuman(operationId, "publication requires human review before remote replay");

        RequirementStageExecutionPlan plan = fixture.engine().planStage(command(validating, "PUBLICATION"));

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, plan.postStatus());
        assertEquals(CommandDisposition.TERMINAL_FAILURE, plan.commandDisposition());
        assertEquals(ExternalEffectReceipt.Kind.PUBLICATION, plan.externalEffectReceipt().kind());
        assertEquals(operationId, plan.externalEffectReceipt().operationId());
        assertEquals("NEEDS_HUMAN", plan.externalEffectReceipt().durableState());
        assertFalse(plan.externalEffectReceipt().isFinalizable());
    }

    @Test
    void remainingTailStagesAreNonMutatingAndPublicationReturnsAFinalizableReceipt() {
        PublicationFixture publicationFixture = publicationFixture();
        Fixture fixture = publicationFixture.fixture();
        RdRequirementTask validating = fixture.registry().transitionRequirementFenced(
                advanceToExecuting(fixture, successfulDeliveryJson()), RdTaskStatus.VALIDATING,
                "", successfulDeliveryJson(), "", "");
        Snapshot validatingBefore = Snapshot.capture(fixture, validating);

        RequirementStageExecutionPlan ai = fixture.engine().planStage(command(validating, "AI_REVIEW"));
        assertEquals(new ContinuationSpec("REQUIREMENT_DELIVERY", "PUBLICATION"), ai.continuation());
        assertUnchanged(fixture, validatingBefore);

        RequirementStageExecutionPlan publication = fixture.engine().planStage(command(validating, "PUBLICATION"));
        assertEquals(RdTaskStatus.COMMITTED, publication.postStatus());
        assertEquals("PUBLICATION", publication.externalEffectReceipt().kind().name());
        assertTrue(publication.externalEffectReceipt().isFinalizable());
        assertTrue(publication.externalEffectReceipt().receiptJson().contains(
                "\"taskId\":\"" + validating.taskId() + "\""));
        assertUnchanged(fixture, validatingBefore);

        RdRequirementTask committed = advanceFrom(fixture, validating,
                RdTaskStatus.PR_CREATING, RdTaskStatus.COMMITTED);
        Snapshot committedBefore = Snapshot.capture(fixture, committed);
        RequirementStageExecutionPlan reporting = fixture.engine().planStage(command(committed, "REPORTING"));
        assertEquals(RdTaskStatus.REPORTING, reporting.postStatus());
        assertEquals(new ContinuationSpec("REQUIREMENT_DELIVERY", "COMPLETION"), reporting.continuation());
        assertUnchanged(fixture, committedBefore);

        RdRequirementTask reportingTask = fixture.registry().transitionRequirementFenced(
                committed, RdTaskStatus.REPORTING, "", "", "", "");
        Snapshot reportingBefore = Snapshot.capture(fixture, reportingTask);
        RequirementStageExecutionPlan completion = fixture.engine().planStage(command(reportingTask, "COMPLETION"));
        assertEquals(RdTaskStatus.COMPLETED, completion.postStatus());
        assertTrue(completion.continuation().isTerminal());
        assertUnchanged(fixture, reportingBefore);
    }

    @Test
    void retryableTechnicalFailureAlwaysRecordsItsFullFailureMutation() {
        Fixture fixture = fixture("normal delivery", "deliver the change", List.of("the change is accepted"));
        RdRequirementTask task = advanceToExecuting(fixture, successfulDeliveryJson());
        RequirementTaskMutation failure = mutation(
                RdTaskStatus.EXECUTING, RdTaskStatus.FAILED_RETRYABLE, "", "{\"status\":\"FAILED\"}",
                "provider unavailable");
        RequirementStageCommand firstAttempt = RequirementStageCommand.pending(
                "retryable-first", task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 0, 2, 0L,
                ScheduleResourceClass.PROVIDER, "_default", "provider", task.priority(), System.currentTimeMillis())
                .claimed("worker", System.currentTimeMillis() + 60_000L, System.currentTimeMillis());

        RequirementStageExecutionPlan firstPlan = invokePlan(
                fixture.engine(), task, firstAttempt, List.of(failure), CommandDisposition.RETRYABLE_TECHNICAL_FAILURE);

        assertEquals(List.of(failure), firstPlan.mutations());
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, firstPlan.postStatus());
        RequirementStageCommand finalAttempt = firstAttempt.failed("provider unavailable", System.currentTimeMillis())
                .claimed("worker", System.currentTimeMillis() + 60_000L, System.currentTimeMillis());
        RequirementStageExecutionPlan finalPlan = invokePlan(
                fixture.engine(), task, finalAttempt, List.of(failure), CommandDisposition.RETRYABLE_TECHNICAL_FAILURE);

        assertEquals(firstPlan.mutations(), finalPlan.mutations());
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, finalPlan.postStatus());
    }

    private static void assertSimplePlan(
            Fixture fixture,
            RdRequirementTask task,
            String stage,
            RdTaskStatus from,
            RdTaskStatus to,
            String continuationStage,
            String resultJson
    ) {
        Snapshot before = Snapshot.capture(fixture, task);

        RequirementStageExecutionPlan plan = fixture.engine().planStage(command(task, stage));

        assertEquals(List.of(mutation(from, to, "", resultJson, "")), plan.mutations());
        assertEquals(CommandDisposition.SUCCEEDED, plan.commandDisposition());
        assertEquals(new ContinuationSpec("REQUIREMENT_DELIVERY", continuationStage), plan.continuation());
        assertUnchanged(fixture, before);
    }

    private static RequirementTaskMutation mutation(
            RdTaskStatus from,
            RdTaskStatus to,
            String prompt,
            String resultJson,
            String error
    ) {
        return RequirementTaskMutation.statusTransition(from, to, prompt, resultJson, "", error, error);
    }

    private static String expectedAllowedPrompt(RdRequirementTask task, List<TaskMaterial> materials) {
        RequirementContextPackage context = new RequirementContextBuilder().build(task, materials);
        RequirementPlan plan = new RequirementPlanGenerator().generate(task, context);
        return """
                你是 RD-Bot 的需求交付执行器。请在受控仓库中完成需求编码、测试，并准备可审查 PR。

                # 任务
                - taskId: %s
                - title: %s
                - priority: %s

                # 仓库
                - repositoryUrl: %s
                - repoOwner: %s
                - repoName: %s
                - baseBranch: %s

                # 预期结果
                %s

                # 验收标准
                %s

                # 需求摘要
                %s

                # 实现计划
                %s

                # 策略决策
                - policyAction: ALLOWED
                - riskLevel: LOW
                - reason: 低风险需求，允许进入沙箱执行

                # 建议验证命令
                %s

                # 需求材料
                %s

                # 输出要求
                - 修改代码后运行必要的测试或构建命令。
                - 返回结构化 JSON，status 使用 SUCCESS/FAILED/NEED_INFO/UNSAFE。
                - 成功时提供 summary、changedFiles、testSummary 和 prBody。
                """.formatted(
                task.taskId(), task.title(), task.priority(), task.repositoryUrl(), task.repoOwner(), task.repoName(),
                task.baseBranch(), task.expectedResult(), task.acceptanceCriteriaJson(), context.requirementSummary(),
                plan.implementationSteps(), context.suggestedValidationCommands(), materialPrompt(materials)).strip();
    }

    private static String materialPrompt(List<TaskMaterial> materials) {
        return materials == null || materials.isEmpty() ? "" : materials.stream()
                .map(material -> "- " + material.title() + "\n" + material.contentPreview())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String preserved(String existing, String proposed) {
        return proposed == null || proposed.isBlank() ? existing : proposed;
    }

    private static TaskMaterial material(String taskId, String preview) {
        return new TaskMaterial("material-1", taskId, TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT, "material", "", "text/plain", "hash", preview,
                "", "", "", "{}", System.currentTimeMillis(), System.currentTimeMillis());
    }

    private static Fixture fixture(String title, String expectedResult, List<String> acceptanceCriteria) {
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), events, SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                title, "P2", "https://github.com/example/repo", "example", "repo", "main",
                expectedResult, acceptanceCriteria, false));
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        return new Fixture(registry, events, materialStore, task,
                new RequirementDeliveryEngine(registry, materialStore, request -> null));
    }

    private static PublicationFixture publicationFixture() {
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), events, SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "normal delivery", "P2", "https://github.com/example/repo", "example", "repo", "main",
                "deliver the change", List.of("the change is accepted"), false));
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry, materialStore, request -> null,
                command -> RequirementPullRequestPublication.success(
                        command.taskId(), "https://example.invalid/pull/1", "1", "{}"));
        engine.setBranchPublisher(command -> RequirementBranchPublication.success(
                command.taskId(), "branch-sha", "{}"));
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(new InMemoryRequirementPublicationStore());
        engine.setPublicationLedger(ledger);
        return new PublicationFixture(new Fixture(registry, events, materialStore, task, engine), ledger);
    }

    private record PublicationFixture(Fixture fixture, RequirementPublicationLedger ledger) {
    }

    private static RdRequirementTask advanceToPlanGenerated(Fixture fixture) {
        return advance(fixture, RdTaskStatus.MATERIAL_COLLECTING, RdTaskStatus.MATERIAL_READY,
                RdTaskStatus.CONTEXT_BUILDING, RdTaskStatus.CONTEXT_READY, RdTaskStatus.PLAN_GENERATING,
                RdTaskStatus.PLAN_GENERATED);
    }

    private static RdRequirementTask advanceToExecuting(Fixture fixture, String executionResultJson) {
        RdRequirementTask task = advanceToPlanGenerated(fixture);
        task = fixture.registry().transitionRequirementFenced(
                task, RdTaskStatus.WAITING_POLICY, "", "", "", "");
        return fixture.registry().transitionRequirementFenced(
                task, RdTaskStatus.EXECUTING, "", executionResultJson, "", "");
    }

    private static String successfulDeliveryJson() {
        return """
                {"multiAgentStatus":"SUCCESS","multiAgentStages":[
                {"role":"REQUIREMENT_REVIEWER","success":true},
                {"role":"SOLUTION_ARCHITECT","success":true},
                {"role":"CODING_AGENT","success":true,"candidatePatch":{"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","bytes":1,"artifactUri":"s3://patch"},"resultJson":{"prBody":"body"}},
                {"role":"QA_AGENT","success":true,"resultJson":{"status":"PASSED","failureCategory":"NONE","retryRecommendation":"NONE",
                "browserValidation":{"required":false,"performed":false,"decisionSource":"NOT_APPLICABLE","baseUrl":"","browser":"chromium","viewports":[]},
                "acceptanceResults":[{"criteria":"current","scope":"CURRENT","command":"test","status":"PASSED","exitCode":0,"durationMillis":0,"logArtifactId":"current.log","evidenceArtifactIds":["current.log"]},{"criteria":"regression","scope":"REGRESSION","command":"test","status":"PASSED","exitCode":0,"durationMillis":0,"logArtifactId":"regression.log","evidenceArtifactIds":["regression.log"]}],"evidenceManifestArtifactId":"manifest.json"}}]}
                """;
    }

    private static RdRequirementTask advance(Fixture fixture, RdTaskStatus... statuses) {
        return advanceFrom(fixture, fixture.registry().getRequirementTask(fixture.task().taskId()), statuses);
    }

    private static RdRequirementTask advanceFrom(
            Fixture fixture,
            RdRequirementTask task,
            RdTaskStatus... statuses
    ) {
        for (RdTaskStatus status : statuses) {
            task = fixture.registry().transitionRequirementFenced(task, status, "", "", "", "");
        }
        return task;
    }

    private static RequirementStageCommand command(RdRequirementTask task, String stage) {
        return RequirementStageCommand.pending(
                "proposal-" + stage, task.taskId(), task.version(), task.fencingToken(),
                "REQUIREMENT_DELIVERY", stage, 0, 3, 0L,
                ScheduleResourceClass.GENERIC, "_default", "", task.priority(), System.currentTimeMillis());
    }

    private static RequirementStageExecutionPlan invokePlan(
            RequirementDeliveryEngine engine,
            RdRequirementTask task,
            RequirementStageCommand command,
            List<RequirementTaskMutation> mutations,
            CommandDisposition disposition
    ) {
        try {
            Method method = RequirementDeliveryEngine.class.getDeclaredMethod(
                    "plan", RdRequirementTask.class, RequirementStageCommand.class, List.class,
                    CommandDisposition.class, ContinuationSpec.class);
            method.setAccessible(true);
            return (RequirementStageExecutionPlan) method.invoke(
                    engine, task, command, mutations, disposition, ContinuationSpec.terminal());
        } catch (InvocationTargetException exception) {
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertUnchanged(Fixture fixture, Snapshot before) {
        assertEquals(before.task(), fixture.registry().getRequirementTask(before.task().taskId()));
        assertEquals(before.timelineSize(), fixture.events().listByTask(before.task().taskId()).size());
    }

    private record Fixture(
            RagStreamTaskRegistry registry,
            InMemoryRdTaskStatusEventStore events,
            InMemoryTaskMaterialStore materialStore,
            RdRequirementTask task,
            RequirementDeliveryEngine engine
    ) {
    }

    private record Snapshot(RdRequirementTask task, int timelineSize) {
        private static Snapshot capture(Fixture fixture, RdRequirementTask task) {
            return new Snapshot(task, fixture.events().listByTask(task.taskId()).size());
        }
    }
}
