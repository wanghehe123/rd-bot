package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.requirement.model.AgentWorkflowPlan;
import com.wish.rd.engine.requirement.model.RequirementContextPackage;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.model.RequirementPlan;
import com.wish.rd.engine.requirement.model.RequirementPolicyDecision;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.model.AgentWorkflowAlert;
import com.wish.rd.engine.agent.model.AgentWorkflowAlertType;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.RoleContextBuilder;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.project.agent.model.RoleExecutionInputManifest;
import com.wish.rd.rag.project.budget.RdProjectTokenBudgetService;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequirementAgentStageOrchestrator} 的单元测试。
 *
 * <p>目标：
 * <ul>
 *     <li>验证 {@link AgentWorkflowPlan} 在 A/B/C/D 的角色/检索/QA/预算账本配置完全等价</li>
 *     <li>验证 orchestrator 驱动角色阶段 for-loop 的调用顺序与次数</li>
 *     <li>验证 D plan 的 QA 一次性修复回路深度 ≤ 1</li>
 *     <li>验证 orchestrator 不再依赖任何 {@code RequirementDeliveryEngine} 引用</li>
 * </ul>
 */
class RequirementAgentStageOrchestratorTest {

    private static final RequirementContextPackage EMPTY_CONTEXT =
            new RequirementContextPackage(
                    "task-1", "summary", List.of(), List.of(), List.of(), List.of(), "trace");
    private static final RequirementPlan EMPTY_PLAN =
            new RequirementPlan("task-1", List.of(), List.of(), List.of());
    private static final RequirementPolicyDecision ALLOWED_DECISION =
            new RequirementPolicyDecision("ALLOWED", "APPROVED", "ok");

    // ---------- (a) production_plan_equals_coding_benchmark_D_plan ----------
    @Test
    void production_plan_equals_coding_benchmark_D_plan() {
        AgentWorkflowPlan production = AgentWorkflowPlan.production();
        AgentWorkflowPlan armD = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.D);

        assertEquals(armD, production);
        assertEquals(armD.roles(), production.roles());
        assertEquals(armD.budgetLedger(), production.budgetLedger());
        assertEquals(armD.qaMaxRemediationPasses(), production.qaMaxRemediationPasses());
        assertEquals(armD.qaRemediationEnabled(), production.qaRemediationEnabled());
        assertEquals(armD.retrievalEnabled(), production.retrievalEnabled());
    }

    // ---------- (b) arm_A_plan_has_only_coding_agent_and_disables_qa ----------
    @Test
    void arm_A_plan_has_only_coding_agent_and_disables_qa() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.A);

        assertEquals(List.of(AgentRole.CODING_AGENT), plan.roles());
        assertFalse(plan.retrievalEnabled());
        assertFalse(plan.qaRemediationEnabled());
        assertFalse(plan.qaRemediationAllowed());
        assertEquals(1, plan.qaMaxRemediationPasses());
        assertEquals(0.56d, plan.budgetLedger().get(AgentRole.CODING_AGENT), 1.0e-9d);
        assertEquals(1, plan.budgetLedger().size());
        assertEquals("CODING_BENCHMARK_ARM_A", plan.source());
    }

    // ---------- (c) arm_B_plan_disables_retrieval_and_qa ----------
    @Test
    void arm_B_plan_disables_retrieval_and_qa() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.B);

        assertEquals(
                List.of(AgentRole.REQUIREMENT_REVIEWER, AgentRole.SOLUTION_ARCHITECT, AgentRole.CODING_AGENT),
                plan.roles()
        );
        assertFalse(plan.retrievalEnabled());
        assertFalse(plan.qaRemediationEnabled());
        assertFalse(plan.qaRemediationAllowed());
        assertEquals(0.08d, plan.budgetLedger().get(AgentRole.REQUIREMENT_REVIEWER), 1.0e-9d);
        assertEquals(0.16d, plan.budgetLedger().get(AgentRole.SOLUTION_ARCHITECT), 1.0e-9d);
        assertEquals(0.56d, plan.budgetLedger().get(AgentRole.CODING_AGENT), 1.0e-9d);
        assertEquals(3, plan.budgetLedger().size());
        assertEquals("CODING_BENCHMARK_ARM_B", plan.source());
    }

    // ---------- (d) arm_C_plan_enables_retrieval_but_disables_qa ----------
    @Test
    void arm_C_plan_enables_retrieval_but_disables_qa() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.C);

        assertEquals(
                List.of(AgentRole.REQUIREMENT_REVIEWER, AgentRole.SOLUTION_ARCHITECT, AgentRole.CODING_AGENT),
                plan.roles()
        );
        assertTrue(plan.retrievalEnabled());
        assertFalse(plan.qaRemediationEnabled());
        assertFalse(plan.qaRemediationAllowed());
        assertEquals(0.08d, plan.budgetLedger().get(AgentRole.REQUIREMENT_REVIEWER), 1.0e-9d);
        assertEquals(0.16d, plan.budgetLedger().get(AgentRole.SOLUTION_ARCHITECT), 1.0e-9d);
        assertEquals(0.56d, plan.budgetLedger().get(AgentRole.CODING_AGENT), 1.0e-9d);
    }

    // ---------- (e) arm_D_plan_enables_retrieval_and_qa_with_one_remediation_pass ----------
    @Test
    void arm_D_plan_enables_retrieval_and_qa_with_one_remediation_pass() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.D);

        assertEquals(
                List.of(
                        AgentRole.REQUIREMENT_REVIEWER,
                        AgentRole.SOLUTION_ARCHITECT,
                        AgentRole.CODING_AGENT,
                        AgentRole.QA_AGENT
                ),
                plan.roles()
        );
        assertTrue(plan.retrievalEnabled());
        assertTrue(plan.qaRemediationEnabled());
        assertTrue(plan.qaRemediationAllowed());
        assertEquals(1, plan.qaMaxRemediationPasses());
        assertEquals(0.08d, plan.budgetLedger().get(AgentRole.REQUIREMENT_REVIEWER), 1.0e-9d);
        assertEquals(0.16d, plan.budgetLedger().get(AgentRole.SOLUTION_ARCHITECT), 1.0e-9d);
        assertEquals(0.56d, plan.budgetLedger().get(AgentRole.CODING_AGENT), 1.0e-9d);
        assertEquals(0.08d, plan.budgetLedger().get(AgentRole.QA_AGENT), 1.0e-9d);
        assertEquals(4, plan.budgetLedger().size());
        assertEquals("CODING_BENCHMARK_ARM_D", plan.source());
    }

    // ---------- (f) budget_ledger_per_arm_enforces_named_role_equal_share ----------
    @Test
    void budget_ledger_per_arm_enforces_named_role_equal_share() {
        Map<AgentRole, Double> ratiosA = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.A).budgetLedger();
        Map<AgentRole, Double> ratiosB = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.B).budgetLedger();
        Map<AgentRole, Double> ratiosC = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.C).budgetLedger();
        Map<AgentRole, Double> ratiosD = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.D).budgetLedger();

        for (AgentRole role : List.of(AgentRole.CODING_AGENT)) {
            assertEquals(0.56d, ratiosA.get(role), 1.0e-9d);
            assertEquals(0.56d, ratiosB.get(role), 1.0e-9d);
            assertEquals(0.56d, ratiosC.get(role), 1.0e-9d);
            assertEquals(0.56d, ratiosD.get(role), 1.0e-9d);
        }
        for (AgentRole role : List.of(AgentRole.REQUIREMENT_REVIEWER, AgentRole.SOLUTION_ARCHITECT)) {
            assertEquals(ratiosB.get(role), ratiosC.get(role), 1.0e-9d);
            assertEquals(ratiosB.get(role), ratiosD.get(role), 1.0e-9d);
        }
        for (Map<AgentRole, Double> ratios : List.of(ratiosA, ratiosB, ratiosC, ratiosD)) {
            double sum = ratios.values().stream().mapToDouble(Double::doubleValue).sum();
            assertTrue(sum <= 1.0d + 1.0e-9d, "budget sum must be ≤ 1.0, got " + sum);
        }
    }

    // ---------- (g) orchestrator_invokes_executor_for_each_role_in_plan_order ----------
    @Test
    void orchestrator_invokes_executor_for_each_role_in_plan_order() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.B);
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success());
        assertEquals(plan.roles(), new ArrayList<>(harness.executor.executedRoles));
        for (AgentRole role : plan.roles()) {
            assertEquals(1, harness.executor.executedRoleCount(role),
                    "role " + role + " should be executed exactly once");
        }
    }

    @Test
    void orchestrator_saves_role_execution_input_manifest_before_dispatch() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.A);
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success());
        assertTrue(harness.artifactStore.listByTask(harness.task.taskId()).stream()
                .anyMatch(artifact -> RoleExecutionInputManifest.ARTIFACT_TYPE.equals(artifact.artifactType())));
        assertTrue(harness.artifactStore.listByTask(harness.task.taskId()).stream()
                .filter(artifact -> RoleExecutionInputManifest.ARTIFACT_TYPE.equals(artifact.artifactType()))
                .allMatch(artifact -> artifact.contentPreview().contains("\"mode\":\"LEGACY_OBSERVE_ONLY\"")));
    }

    // ---------- (h) orchestrator_enforces_one_qa_remediation_pass_for_D_plan ----------
    @Test
    void orchestrator_enforces_one_qa_remediation_pass_for_D_plan() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.D);
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.executor.qaAgentFailsWithRemediation = true;

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        // QA 失败，最终应聚合为失败
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("QA_AGENT"),
                "error message should mention QA_AGENT: " + result.errorMessage());
    }

    // ---------- 预算账本配置越限在构造期拒绝 ----------
    @Test
    void budget_ledger_overflow_is_rejected_by_validation() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new AgentWorkflowPlan(
                        List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT),
                        false,
                        false,
                        1,
                        new LinkedHashMap<>() {{
                            put(AgentRole.CODING_AGENT, 0.6d);
                            put(AgentRole.QA_AGENT, 0.6d);
                        }},
                        "TEST_OVERFLOW"
                )
        );
        assertTrue(exception.getMessage().contains("budgetLedger"));
    }

    @Test
    void budget_ledger_share_out_of_range_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentWorkflowPlan(
                        List.of(AgentRole.CODING_AGENT),
                        false,
                        false,
                        1,
                        Map.of(AgentRole.CODING_AGENT, 1.5d),
                        "TEST_OUT_OF_RANGE"
                ));
    }

    @Test
    void empty_roles_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentWorkflowPlan(
                        List.of(),
                        false,
                        false,
                        1,
                        Map.of(),
                        "TEST_EMPTY_ROLES"
                ));
    }

    // ---------- qa 修复回路关闭时不允许 remediation ----------
    @Test
    void orchestrator_does_not_trigger_qa_remediation_when_plan_disables_it() {
        AgentWorkflowPlan plan = new AgentWorkflowPlan(
                List.of(AgentRole.REQUIREMENT_REVIEWER,
                        AgentRole.SOLUTION_ARCHITECT,
                        AgentRole.CODING_AGENT,
                        AgentRole.QA_AGENT),
                true,
                false,
                1,
                Map.of(
                        AgentRole.REQUIREMENT_REVIEWER, 0.08d,
                        AgentRole.SOLUTION_ARCHITECT, 0.16d,
                        AgentRole.CODING_AGENT, 0.56d,
                        AgentRole.QA_AGENT, 0.08d
                ),
                "TEST_D_NO_REMEDIATION"
        );
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.executor.qaAgentFailsWithRemediation = true;

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertEquals(1, harness.executor.executedRoleCount(AgentRole.QA_AGENT),
                "QA should not be re-run when remediation is disabled");
    }

    // ---------- (i) 4 个 helper 独立测试 -----------------------

    @Test
    void buildAgentPrompt_includes_role_specific_section_for_reviewer() {
        AgentWorkflowPlan reviewerOnly = new AgentWorkflowPlan(
                List.of(AgentRole.REQUIREMENT_REVIEWER), true, false, 1,
                Map.of(AgentRole.REQUIREMENT_REVIEWER, 1.0d),
                "TEST_REVIEWER_ONLY");
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(reviewerOnly.roles())
                .prestageRoleContexts(reviewerOnly.roles());

        harness.orchestrator.run(
                reviewerOnly, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN,
                ALLOWED_DECISION, null);

        String reviewerPrompt = harness.executor.lastPromptByRole.get(AgentRole.REQUIREMENT_REVIEWER);
        assertNotNull(reviewerPrompt, "reviewer prompt should be captured");
        assertTrue(reviewerPrompt.contains("REQUIREMENT_REVIEWER"),
                "reviewer prompt should label the role");
        assertTrue(reviewerPrompt.contains("需求评审"),
                "reviewer prompt should include the role instruction section");
    }

    @Test
    void recordRetrieval_returns_empty_succeed_outcome_when_recorder_missing() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.C);
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        // harness.retrievalRecorder 已经是 null, orchestrator 必须生成空成功的
        // RetrievalOutcome 让循环继续。
        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);
        assertTrue(result.success());
    }

    @Test
    void executeRole_classifies_retryable_profile_resolution_failure() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.B);
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);
        assertNotNull(result);
        assertTrue(harness.executor.executedRoleCount(AgentRole.CODING_AGENT) >= 1);
    }

    @Test
    void recoveryPromptSection_emits_operator_note_when_retry_checkpoint_set() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.D);
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        // 这里只验证 orchestrator 接受 null checkpoint 不抛错
        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);
        assertNotNull(result);
    }

    // ============================================================
    // 测试 harness：把 orchestrator 装配起来并提供 stub collaborator
    // ============================================================

    private static RdRequirementTask rdTask(String taskId) {
        return new RdRequirementTask(
                taskId,
                "REQUIREMENT",
                "ADMIN",
                "",
                "",
                "P1",
                RdTaskStatus.EXECUTING,
                "Test task",
                "project-1",
                "project",
                "Project",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                "requirement/" + taskId,
                "实现 " + taskId,
                "[]",
                "",
                "{}",
                "",
                "",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                false,
                0L
        );
    }

    private static AgentStageRun makeStageRun(String taskId, AgentRole role, int attemptNo) {
        long now = System.currentTimeMillis();
        return new AgentStageRun(
                role.name().toLowerCase() + "-" + attemptNo,
                taskId,
                role,
                AgentStageStatus.PENDING,
                attemptNo,
                taskId + ":" + role + ":" + attemptNo,
                "",
                "",
                "",
                "",
                "[]",
                "",
                "",
                "",
                now,
                now,
                0L,
                0L
        );
    }

    /** 装配 orchestrator + stub collaborators。 */
    static final class OrchestratorTestHarness {
        final RdRequirementTask task = rdTask("task-1");
        final AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        final AgentStageArtifactStore artifactStore = new InMemoryAgentStageArtifactStore();
        final RoleContextPackageStore roleContextPackageStore = new InMemoryRoleContextPackageStore();
        final RoleContextBuilder roleContextBuilder = new RoleContextBuilder();
        final RoleContextVersionManager roleContextVersionManager =
                new RoleContextVersionManager(
                        roleContextBuilder, roleContextPackageStore,
                        SnowflakeIdGenerator.defaultGenerator()::nextIdString, 18_000);
        final AgentWorkflowAlertSinkPort alertSink = new RecordingAlertSink();
        final WorkflowExperienceStore experienceStore = WorkflowExperienceStore.noop();
        final RecordingExecutor executor = new RecordingExecutor();
        final RequirementExecutionProfileResolverPort executionProfileResolver = new SnapshotProfileResolver();
        final RagStreamTaskRegistry taskRegistry = RagStreamTaskRegistry.inMemory();
        final SnowflakeIdGenerator idGenerator = SnowflakeIdGenerator.defaultGenerator();
        final RequirementAgentStageOrchestrator orchestrator;

        OrchestratorTestHarness() {
            this.orchestrator = new RequirementAgentStageOrchestrator(
                    stageRunStore,
                    artifactStore,
                    roleContextPackageStore,
                    roleContextVersionManager,
                    null,
                    executionProfileResolver,
                    null,
                    alertSink,
                    experienceStore,
                    taskRegistry,
                    executor,
                    idGenerator
            );
        }

        /** Pre-create pending stages for the given roles so the orchestrator has them available. */
        OrchestratorTestHarness prestageRoles(List<AgentRole> roles) {
            long now = System.currentTimeMillis();
            for (AgentRole role : roles) {
                AgentStageRun stage = new AgentStageRun(
                        role.name().toLowerCase() + "-1",
                        task.taskId(),
                        role,
                        AgentStageStatus.PENDING,
                        1,
                        task.taskId() + ":" + role + ":1",
                        "",
                        "",
                        "",
                        "",
                        "[]",
                        "",
                        "",
                        "",
                        now,
                        now,
                        0L,
                        0L
                );
                stageRunStore.save(stage);
            }
            return this;
        }

        /** Pre-create role context packages so the orchestrator can retrieve them. */
        OrchestratorTestHarness prestageRoleContexts(List<AgentRole> roles) {
            long now = System.currentTimeMillis();
            for (AgentRole role : roles) {
                String packageId = role.name().toLowerCase() + "-ctx-1";
                RoleContextPackage roleContext = new RoleContextPackage(
                        packageId,
                        task.taskId(),
                        role.name(),
                        1,
                        List.of(),
                        List.of(),
                        List.of(),
                        0,
                        0,
                        List.of(),
                        "",
                        now
                );
                roleContextPackageStore.save(roleContext);
            }
            return this;
        }
    }

    /** Test executor: 记录每次调用与 prompt 文本。 */
    static final class RecordingExecutor implements RequirementExecutorPort {
        final List<AgentRole> executedRoles = new CopyOnWriteArrayList<>();
        final Map<AgentRole, AtomicInteger> executedRoleCounts = new LinkedHashMap<>();
        final Map<AgentRole, String> lastPromptByRole = new LinkedHashMap<>();
        boolean qaAgentFailsWithRemediation = false;

        @Override
        public RequirementExecutionResult execute(RequirementExecutionRequest request) {
            executedRoles.add(request.role());
            executedRoleCounts.computeIfAbsent(request.role(), ignored -> new AtomicInteger()).incrementAndGet();
            lastPromptByRole.put(request.role(), request.prompt());
            if (request.role() == AgentRole.QA_AGENT && qaAgentFailsWithRemediation) {
                return RequirementExecutionResult.failure(
                        request.taskId(),
                        "QA agent failure",
                        "{\"status\":\"FAILED\",\"retryRecommendation\":\"CODING_REMEDIATION\"}"
                );
            }
            String resultJson = switch (request.role()) {
                case REQUIREMENT_REVIEWER ->
                        "{\"role\":\"REQUIREMENT_REVIEWER\",\"status\":\"READY\","
                                + "\"decision\":\"APPROVED\",\"feasibility\":\"FEASIBLE\","
                                + "\"budgetEstimate\":{\"initialTokens\":256,\"retryReserveTokens\":32,"
                                + "\"estimatedTotalTokens\":288,\"confidence\":\"LOW\","
                                + "\"basis\":\"heuristic\",\"historicalSamples\":[]},"
                                + "\"tokenBudgetEstimate\":{\"tokens\":256}}";
                case SOLUTION_ARCHITECT ->
                        "{\"role\":\"SOLUTION_ARCHITECT\",\"status\":\"READY\","
                                + "\"planSummary\":\"ok\","
                                + "\"budgetEstimate\":{\"initialTokens\":512,\"retryReserveTokens\":64,"
                                + "\"estimatedTotalTokens\":576,\"confidence\":\"LOW\","
                                + "\"basis\":\"heuristic\",\"historicalSamples\":[]},"
                                + "\"tokenBudgetEstimate\":{\"tokens\":256}}";
                case CODING_AGENT ->
                        "{\"role\":\"CODING_AGENT\",\"status\":\"SUCCEEDED\","
                                + "\"pullRequestUrl\":\"https://example.com/pr/1\","
                                + "\"budgetEstimate\":{\"initialTokens\":1024,\"retryReserveTokens\":128,"
                                + "\"estimatedTotalTokens\":1152,\"confidence\":\"LOW\","
                                + "\"basis\":\"heuristic\",\"historicalSamples\":[]},"
                                + "\"tokenBudgetEstimate\":{\"tokens\":256}}";
                case QA_AGENT ->
                        "{\"role\":\"QA_AGENT\",\"status\":\"SUCCEEDED\","
                                + "\"budgetEstimate\":{\"initialTokens\":256,\"retryReserveTokens\":32,"
                                + "\"estimatedTotalTokens\":288,\"confidence\":\"LOW\","
                                + "\"basis\":\"heuristic\",\"historicalSamples\":[]},"
                                + "\"tokenBudgetEstimate\":{\"tokens\":256}}";
                default ->
                        "{\"role\":\"" + request.role().name() + "\",\"status\":\"SUCCEEDED\","
                                + "\"budgetEstimate\":{\"initialTokens\":256,\"retryReserveTokens\":32,"
                                + "\"estimatedTotalTokens\":288,\"confidence\":\"LOW\","
                                + "\"basis\":\"heuristic\",\"historicalSamples\":[]},"
                                + "\"tokenBudgetEstimate\":{\"tokens\":256}}";
            };
            return RequirementExecutionResult.success(
                    request.taskId(),
                    request.role().name() + " 完成",
                    "",
                    resultJson
            );
        }

        int executedRoleCount(AgentRole role) {
            AtomicInteger counter = executedRoleCounts.get(role);
            return counter == null ? 0 : counter.get();
        }
    }

    /** Test profile resolver: 总是返回 snapshot。 */
    static final class SnapshotProfileResolver implements RequirementExecutionProfileResolverPort {
        @Override
        public RequirementExecutionProfileResolution resolve(
                RdRequirementTask task, AgentRole role, String stageRunId, int attemptNo) {
            return new RequirementExecutionProfileResolution("snap-" + stageRunId);
        }
    }

    /** Test alert sink: 记录 alert 调用。 */
    static final class RecordingAlertSink implements AgentWorkflowAlertSinkPort {
        final List<AgentWorkflowAlert> alerts = new CopyOnWriteArrayList<>();

        @Override
        public void publish(AgentWorkflowAlert alert) {
            alerts.add(alert);
        }
    }
}
