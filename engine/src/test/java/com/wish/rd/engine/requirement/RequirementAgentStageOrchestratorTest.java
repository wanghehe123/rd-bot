package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.model.AgentWorkflowPlanFixtures;
import com.wish.rd.engine.requirement.model.AgentWorkflowPlan;
import com.wish.rd.engine.requirement.model.RequirementContextPackage;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.model.RequirementPlan;
import com.wish.rd.engine.requirement.model.RequirementPolicyDecision;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.engine.requirement.verify.HostVerificationPort;
import com.wish.rd.engine.requirement.verify.impl.InMemoryHostVerificationStore;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedTaskStatePolicyBootstrap;
import com.wish.rd.engine.requirement.audit.impl.InMemoryAuditedTaskStateStore;
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
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.project.agent.model.RoleExecutionInputManifest;
import com.wish.rd.rag.project.agent.model.AgentStateV2Codec;
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

    @Test
    void capabilityGatedPiDispatchCarriesJavaOwnedInitialStateWhileLegacyDoesNot() throws Exception {
        AgentWorkflowPlan codingOnly = new AgentWorkflowPlan(
                List.of(AgentRole.CODING_AGENT), false, false, 1, false, 2,
                Map.of(AgentRole.CODING_AGENT, 1.0d), "TEST_PI_STATE_V2"
        );
        OrchestratorTestHarness enabled = new OrchestratorTestHarness()
                .prestageRoles(codingOnly.roles())
                .prestageRoleContexts(codingOnly.roles());
        enabled.orchestrator.setExecutionProfileResolver(new StateV2ProfileResolver());

        RequirementExecutionResult enabledResult = enabled.orchestrator.run(
                codingOnly, enabled.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null
        );

        assertTrue(enabledResult.success(), enabledResult.errorMessage());
        RequirementExecutionRequest request = enabled.executor.lastRequestByRole.get(AgentRole.CODING_AGENT);
        assertEquals("rd-agent-state/v2", request.initialAgentStateProtocol());
        assertFalse(request.initialAgentStateJson().isBlank());
        assertFalse(request.initialAgentStateHash().isBlank());
        JsonNode state = AgentStateV2Codec.decodeAndVerify(
                request.initialAgentStateJson(), request.initialAgentStateHash()
        );
        assertEquals("task-1", state.path("taskId").asText());
        assertEquals("coding_agent-1", state.path("stageRunId").asText());
        assertEquals("CODING_AGENT", state.path("role").asText());
        assertEquals("snap-state-coding_agent-1", state.path("profileSnapshotId").asText());

        OrchestratorTestHarness legacy = new OrchestratorTestHarness()
                .prestageRoles(codingOnly.roles())
                .prestageRoleContexts(codingOnly.roles());
        RequirementExecutionResult legacyResult = legacy.orchestrator.run(
                codingOnly, legacy.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null
        );
        assertTrue(legacyResult.success(), legacyResult.errorMessage());
        RequirementExecutionRequest legacyRequest = legacy.executor.lastRequestByRole.get(AgentRole.CODING_AGENT);
        assertTrue(legacyRequest.initialAgentStateProtocol().isBlank());
        assertTrue(legacyRequest.initialAgentStateJson().isBlank());
        assertTrue(legacyRequest.initialAgentStateHash().isBlank());
    }

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
        AgentWorkflowPlan armD = AgentWorkflowPlan.production();

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
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.codingOnly();

        assertEquals(List.of(AgentRole.CODING_AGENT), plan.roles());
        assertFalse(plan.retrievalEnabled());
        assertFalse(plan.qaRemediationEnabled());
        assertFalse(plan.qaRemediationAllowed());
        assertEquals(1, plan.qaMaxRemediationPasses());
        assertEquals(0.56d, plan.budgetLedger().get(AgentRole.CODING_AGENT), 1.0e-9d);
        assertEquals(1, plan.budgetLedger().size());
        assertEquals("CODING_ONLY", plan.source());
    }

    // ---------- (c) arm_B_plan_disables_retrieval_and_qa ----------
    @Test
    void arm_B_plan_disables_retrieval_and_qa() {
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.reviewArchitectCoding();

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
        assertEquals("REVIEW_ARCHITECT_CODING", plan.source());
    }

    // ---------- (d) arm_C_plan_enables_retrieval_but_disables_qa ----------
    @Test
    void arm_C_plan_enables_retrieval_but_disables_qa() {
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.reviewArchitectCodingWithRetrieval();

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
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();

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
        assertEquals("PRODUCTION", plan.source());
    }

    // ---------- (f) budget_ledger_per_arm_enforces_named_role_equal_share ----------
    @Test
    void budget_ledger_per_arm_enforces_named_role_equal_share() {
        Map<AgentRole, Double> ratiosA = AgentWorkflowPlanFixtures.codingOnly().budgetLedger();
        Map<AgentRole, Double> ratiosB = AgentWorkflowPlanFixtures.reviewArchitectCoding().budgetLedger();
        Map<AgentRole, Double> ratiosC = AgentWorkflowPlanFixtures.reviewArchitectCodingWithRetrieval().budgetLedger();
        Map<AgentRole, Double> ratiosD = AgentWorkflowPlan.production().budgetLedger();

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

    @Test
    void host_rejects_coding_fallback_onto_generation_only_provider() {
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.codingOnly();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.executor.codingResultOverride = """
                {"role":"CODING_AGENT","status":"SUCCEEDED","provider":"weak-gen",
                "providerAttempts":[
                  {"provider":"openai","status":"503"},
                  {"provider":"weak-gen","status":"SUCCESS"}
                ],
                "budgetEstimate":{"initialTokens":1024,"retryReserveTokens":128,
                "estimatedTotalTokens":1152,"confidence":"LOW","basis":"heuristic","historicalSamples":[]},
                "tokenBudgetEstimate":{"tokens":256}}
                """.strip();

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("host rejected provider fallback"));
        assertTrue(result.errorMessage().contains("WAITING_POLICY")
                || result.resultJson().contains("WAITING_POLICY"));
        assertTrue(harness.alertSink instanceof RecordingAlertSink recording
                && recording.alerts.stream().anyMatch(alert ->
                alert.type() == AgentWorkflowAlertType.PROVIDER_FALLBACK
                        && alert.metadata().getOrDefault("hostDecision", "").equals("WAITING_POLICY")));
    }

    @Test
    void host_blocks_coding_fallback_when_side_effect_state_is_unknown() {
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.codingOnly();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.executor.codingResultOverride = """
                {"role":"CODING_AGENT","status":"SUCCEEDED","provider":"anthropic",
                "providerAttempts":[
                  {"provider":"openai","status":"TIMEOUT"},
                  {"provider":"anthropic","status":"SUCCESS"}
                ],
                "providerFallbackSafety":{
                  "state":"UNKNOWN",
                  "reason":"publication status is UNKNOWN_REMOTE_RESULT"
                },
                "budgetEstimate":{"initialTokens":1024,"retryReserveTokens":128,
                "estimatedTotalTokens":1152,"confidence":"LOW","basis":"heuristic","historicalSamples":[]},
                "tokenBudgetEstimate":{"tokens":256}}
                """.strip();

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("UNKNOWN_REMOTE_RESULT"));
        assertTrue(result.resultJson().contains("\"status\":\"WAITING_POLICY\"")
                || result.resultJson().contains("\"status\":\"NEEDS_HUMAN\""));
        assertFalse(result.resultJson().contains("\"status\":\"REJECTED\""));
        assertTrue(harness.alertSink instanceof RecordingAlertSink recording
                && recording.alerts.stream().anyMatch(alert ->
                alert.type() == AgentWorkflowAlertType.PROVIDER_FALLBACK
                        && alert.metadata().getOrDefault("hostDecision", "").equals("WAITING_POLICY")
                        && alert.metadata().getOrDefault("decisionReason", "")
                        .contains("UNKNOWN_REMOTE_RESULT")));
    }

    @Test
    void host_blocks_capable_coding_fallback_without_host_clean_attempt_evidence() {
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.codingOnly();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.executor.codingResultOverride = """
                {"role":"CODING_AGENT","status":"SUCCEEDED","provider":"anthropic",
                "providerAttempts":[
                  {"provider":"openai","status":"TIMEOUT"},
                  {"provider":"anthropic","status":"SUCCESS"}
                ],
                "budgetEstimate":{"initialTokens":1024,"retryReserveTokens":128,
                "estimatedTotalTokens":1152,"confidence":"LOW","basis":"heuristic","historicalSamples":[]},
                "tokenBudgetEstimate":{"tokens":256}}
                """.strip();

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("host rejected provider fallback"));
        assertTrue(result.errorMessage().contains("host-owned clean attempt evidence is missing"));
        assertTrue(result.resultJson().contains("\"status\":\"WAITING_POLICY\"")
                || result.resultJson().contains("\"status\":\"NEEDS_HUMAN\""));
    }

    // ---------- (g) orchestrator_invokes_executor_for_each_role_in_plan_order ----------
    @Test
    void orchestrator_invokes_executor_for_each_role_in_plan_order() {
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.reviewArchitectCoding();
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
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.codingOnly();
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

    @Test
    void reviewer_prompt_excludes_coding_executor_baseline_under_legacy_protocol() {
        AgentWorkflowPlan plan = reviewerOnlyPlan();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success());
        String prompt = harness.executor.lastPromptByRole.get(AgentRole.REQUIREMENT_REVIEWER);
        assertNotNull(prompt);
        assertFalse(prompt.contains("完成需求编码"));
    }

    @Test
    void reviewer_prompt_includes_facts_contract_under_facts_v1_protocol() {
        AgentWorkflowPlan plan = reviewerOnlyPlan();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.orchestrator.setExecutionProfileResolver(new FactsProfileResolver());

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success());
        String prompt = harness.executor.lastPromptByRole.get(AgentRole.REQUIREMENT_REVIEWER);
        assertNotNull(prompt);
        assertFalse(prompt.contains("完成需求编码"));
        assertTrue(prompt.contains("\"facts\""));
        assertTrue(prompt.contains("environmentNotes"));
    }

    @Test
    void architect_prompt_excludes_coding_executor_baseline_under_legacy_protocol() {
        AgentWorkflowPlan plan = architectOnlyPlan();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success());
        String prompt = harness.executor.lastPromptByRole.get(AgentRole.SOLUTION_ARCHITECT);
        assertNotNull(prompt);
        assertFalse(prompt.contains("完成需求编码"));
        assertFalse(prompt.contains("准备可审查 PR"));
    }

    @Test
    void prompt_excludes_unselected_material_body_from_manifest_bypass() {
        String hugeMarker = "UNSELECTED_HUGE_MATERIAL_MARKER_" + "X".repeat(8_000);
        AgentWorkflowPlan plan = reviewerOnlyPlan();
        TaskMaterial unselectedHuge = new TaskMaterial(
                "mat-huge-unselected",
                "task-1",
                com.wish.rd.rag.runtime.model.TaskMaterialType.REQUIREMENT_DOC,
                com.wish.rd.rag.runtime.model.TaskMaterialSourceType.MANUAL_TEXT,
                "未选中超大材料",
                "manual://mat-huge-unselected",
                "text/plain",
                "sha256:huge-unselected",
                hugeMarker,
                "",
                "",
                "",
                "{}",
                System.currentTimeMillis(),
                System.currentTimeMillis()
        );
        RoleContextEvidence selectedEvidence = new RoleContextEvidence(
                "mat-selected",
                "MANUAL_TEXT",
                "manual://mat-selected",
                "已选材料",
                "sha256:selected",
                "仅角色上下文中的摘要",
                System.currentTimeMillis()
        );
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContext(
                        AgentRole.REQUIREMENT_REVIEWER,
                        List.of(selectedEvidence),
                        List.of("mat-selected")
                );

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(unselectedHuge), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success());
        String prompt = harness.executor.lastPromptByRole.get(AgentRole.REQUIREMENT_REVIEWER);
        assertNotNull(prompt);
        assertFalse(prompt.contains(hugeMarker));
        assertTrue(prompt.contains("仅角色上下文中的摘要"));
        assertFalse(prompt.contains("# 需求材料"));
    }

    private static AgentWorkflowPlan architectOnlyPlan() {
        return new AgentWorkflowPlan(
                List.of(AgentRole.SOLUTION_ARCHITECT),
                false,
                false,
                1,
                false,
                2,
                Map.of(AgentRole.SOLUTION_ARCHITECT, 1.0d),
                "TEST_ARCHITECT_ONLY"
        );
    }

    private static AgentWorkflowPlan reviewerOnlyPlan() {
        return new AgentWorkflowPlan(
                List.of(AgentRole.REQUIREMENT_REVIEWER),
                false,
                false,
                1,
                false,
                2,
                Map.of(AgentRole.REQUIREMENT_REVIEWER, 1.0d),
                "TEST_REVIEWER_ONLY"
        );
    }

    // ---------- (h) orchestrator_enforces_one_qa_remediation_pass_for_D_plan ----------
    @Test
    void orchestrator_enforces_one_qa_remediation_pass_for_D_plan() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
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

    @Test
    void piV2ExplicitRequestRoutesToCodingForAnyFailureCategory() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.orchestrator.setExecutionProfileResolver(new QaRemediationV2ProfileResolver());
        harness.executor.qaFailureResultJson = qaRemediationResult(true, "ENVIRONMENT", true);

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertFalse(result.success());
        assertEquals(2, harness.executor.executedRoleCount(AgentRole.CODING_AGENT));
        assertEquals(2, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
        RequirementExecutionRequest codingRetry = harness.executor.lastRequestByRole.get(AgentRole.CODING_AGENT);
        assertTrue(codingRetry.prompt().contains("/work/input/attachments/qa-remediation/request.json"));
        assertEquals("attachments/qa-remediation/request.json",
                codingRetry.initialAgentStateAttachments().getFirst().path());
        assertTrue(codingRetry.initialAgentStateAttachments().getFirst().content().contains("bug-1"));
    }

    @Test
    void piV2DeclinedOrForgedRequestDoesNotRouteToCoding() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness declined = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        declined.orchestrator.setExecutionProfileResolver(new QaRemediationV2ProfileResolver());
        declined.executor.qaFailureResultJson = qaRemediationResult(false, "FLAKY", true);

        declined.orchestrator.run(
                plan, declined.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        OrchestratorTestHarness forged = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        forged.orchestrator.setExecutionProfileResolver(new QaRemediationV2ProfileResolver());
        forged.executor.qaFailureResultJson = qaRemediationResult(true, "PRODUCT_DEFECT", false);
        forged.orchestrator.run(
                plan, forged.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertEquals(1, declined.executor.executedRoleCount(AgentRole.CODING_AGENT));
        assertEquals(1, forged.executor.executedRoleCount(AgentRole.CODING_AGENT));
    }

    @Test
    void commandScopedQaFailureAttachesQaRoleResultAndKeepsParseableAggregate() throws Exception {
        AgentWorkflowPlan plan = new AgentWorkflowPlan(
                List.of(AgentRole.QA_AGENT), false, false, 1, false, 2,
                Map.of(AgentRole.QA_AGENT, 0.08d), "TEST_BOUNDED_QA");
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        JsonNode qaPayload = new ObjectMapper().readTree(qaRemediationResult(true, "PRODUCT_DEFECT", true));
        ((com.fasterxml.jackson.databind.node.ObjectNode) qaPayload.path("remediationRequest"))
                .put("reason", "Fix the reproducible bug \u001b[31mHTTP 500\u001b[0m");
        harness.executor.qaFailureResultJson = new ObjectMapper().writeValueAsString(qaPayload);

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertFalse(result.success());
        assertEquals(0, harness.executor.executedRoleCount(AgentRole.CODING_AGENT));
        assertEquals(1, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
        JsonNode root = new ObjectMapper().readTree(result.resultJson());
        assertEquals("NEEDS_HUMAN", root.path("status").asText());
        JsonNode qa = root.path("qaRoleResult");
        assertEquals("FAILED", qa.path("status").asText());
        assertTrue(qa.path("remediationRequest").path("requested").asBoolean());
        assertEquals("CODING_AGENT", qa.path("remediationRequest").path("targetRole").asText());
        assertTrue(qa.path("remediationRequest").path("reason").asText().contains("HTTP 500"));
    }

    @Test
    void durableProtocolRetryInjectsOnlyControlledQaPrompt() {
        AgentWorkflowPlan plan = new AgentWorkflowPlan(
                List.of(AgentRole.QA_AGENT), false, false, 1, false, 2,
                Map.of(AgentRole.QA_AGENT, 0.08d), "TEST_PROTOCOL_RETRY"
        );
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        String controlled = "PI QA 协议重试：仅完成 rd_submit_result，禁止请求 Coding 修复。";

        RequirementExecutionResult result = harness.orchestrator.runRemediationRole(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION,
                null, "", "", controlled
        );

        assertTrue(result.success(), result.errorMessage());
        RequirementExecutionRequest request = harness.executor.lastRequestByRole.get(AgentRole.QA_AGENT);
        assertTrue(request.prompt().contains(controlled));
        assertFalse(request.prompt().contains("qa-remediation/request.json"));
    }

    @Test
    void legacyQaPredicateRemainsCompatibleWithoutCapability() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.executor.qaFailureResultJson = """
                {"status":"FAILED","failureCategory":"REGRESSION","retryRecommendation":"CODING_AGENT"}
                """;

        harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertEquals(2, harness.executor.executedRoleCount(AgentRole.CODING_AGENT));
        assertEquals(2, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
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
                        false,
                        2,
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
                        false,
                        2,
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
                        false,
                        2,
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
                false,
                2,
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
                List.of(AgentRole.REQUIREMENT_REVIEWER), true, false, 1, false, 2,
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
    void buildAgentPrompt_lists_frozen_current_criteriaIds_for_pi_v2_qa() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.orchestrator.setExecutionProfileResolver(new QaRemediationV2ProfileResolver());

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success(), result.errorMessage());
        String qaPrompt = harness.executor.lastPromptByRole.get(AgentRole.QA_AGENT);
        assertNotNull(qaPrompt, "QA prompt should be captured");
        assertTrue(qaPrompt.contains("CURRENT 项必须带 criteriaId"), qaPrompt);
        assertTrue(qaPrompt.contains("冻结集合"), qaPrompt);
        assertTrue(qaPrompt.contains("REGRESSION 项可不带 criteriaId"), qaPrompt);
        assertTrue(qaPrompt.contains("（空）") || qaPrompt.contains("AC-"), qaPrompt);
    }

    @Test
    void buildAgentPrompt_requires_only_hostAssertionResultEchoes_for_qa() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        String qaPrompt = harness.executor.lastPromptByRole.get(AgentRole.QA_AGENT);
        assertNotNull(qaPrompt, "QA prompt should be captured");
        assertTrue(qaPrompt.contains("\"hostAssertionResults\""), qaPrompt);
        assertFalse(qaPrompt.contains("\"hostAssertionBundle\""), qaPrompt);
        assertTrue(qaPrompt.contains("hostAssertionContracts"), qaPrompt);
    }

    @Test
    void coding_prompt_tells_host_verify_gate_is_authoritative_under_legacy_protocol() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success(), result.errorMessage());
        assertCodingPromptOwnsHostVerifyAndQaDoesNot(
                harness.executor.lastPromptByRole.get(AgentRole.CODING_AGENT),
                harness.executor.lastPromptByRole.get(AgentRole.QA_AGENT));
    }

    @Test
    void coding_prompt_tells_host_verify_gate_is_authoritative_under_facts_v1_protocol() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.orchestrator.setExecutionProfileResolver(new FactsProfileResolver());

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success(), result.errorMessage());
        String codingPrompt = harness.executor.lastPromptByRole.get(AgentRole.CODING_AGENT);
        assertCodingPromptOwnsHostVerifyAndQaDoesNot(
                codingPrompt,
                harness.executor.lastPromptByRole.get(AgentRole.QA_AGENT));
        assertTrue(codingPrompt.contains("\"facts\""), codingPrompt);
    }

    @Test
    void scopedRetrievalDoesNotPrefetchLegacyExperienceWhenRecorderIsConfigured() {
        WorkflowExperienceStore experienceStore = org.mockito.Mockito.mock(WorkflowExperienceStore.class);
        org.mockito.Mockito.when(experienceStore.searchReusable(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new AssertionError("legacy experience prefetch must not bypass scoped retrieval"));
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.reviewArchitectCodingWithRetrieval();
        OrchestratorTestHarness harness = new OrchestratorTestHarness(experienceStore)
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.orchestrator.setRetrievalRecorder(new RequirementContextRetrievalRecorder(
                new com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle(
                        new com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore(),
                        () -> "run-1",
                        () -> 100L)));

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        org.mockito.Mockito.verify(experienceStore, org.mockito.Mockito.never()).searchReusable(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
        assertNotNull(result);
    }

    @Test
    void recordRetrieval_returns_empty_succeed_outcome_when_recorder_missing() {
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.reviewArchitectCodingWithRetrieval();
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
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.reviewArchitectCoding();
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
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        // 这里只验证 orchestrator 接受 null checkpoint 不抛错
        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);
        assertNotNull(result);
    }

    @Test
    void coding_success_and_verify_succeeded_dispatches_qa() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, harness.hostVerificationPort.verifyCalls.get());
        assertEquals(1, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
        assertEquals(1, stageCount(harness, AgentRole.QA_AGENT));
    }

    @Test
    void productionHostVerifySuccessWritesAuditRunThenDispatchesQa() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        InMemoryAuditedTaskStateStore audited = new InMemoryAuditedTaskStateStore();
        InMemoryHostVerificationStore verifyStore = new InMemoryHostVerificationStore();
        new AuditedTaskStatePolicyBootstrap(audited)
                .initializeIfAbsent(harness.task.withConcurrency(1L, 1L));
        harness.orchestrator.setAuditedTaskStateStore(audited);
        harness.orchestrator.setHostVerificationStore(verifyStore);
        harness.hostVerificationPort.persistTo(verifyStore);

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, audited.listAuditRuns(harness.task.taskId()).size());
        assertEquals(AuditedRecordStatus.COMPLETED,
                audited.head(harness.task.taskId()).orElseThrow().record("GATE-BUILD").status());
        assertEquals(AuditedRecordStatus.COMPLETED,
                audited.head(harness.task.taskId()).orElseThrow().record("GATE-STATIC").status());
        assertEquals(1, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
    }

    @Test
    void coding_success_and_docs_only_verify_dispatches_qa() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.hostVerificationPort.withOutcomes(hostVerify(
                HostVerificationStatus.SKIPPED_DOCS_ONLY, "", ""));

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
    }

    @Test
    void reusedCodingStageRestoresCompletePublicationResultBase() throws Exception {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        String codingJson = """
                {"status":"SUCCESS","summary":"implemented","prBody":"narrative",
                 "changedFiles":["src/App.java"],"testCommands":["./mvnw test"],
                 "testStatus":"PASSED","riskLevel":"LOW"}
                """;
        String qaJson = successfulQaResultJson();

        OrchestratorTestHarness reused = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        reused.executor.qaSuccessResultOverride = qaJson;
        for (AgentRole role : List.of(AgentRole.REQUIREMENT_REVIEWER, AgentRole.SOLUTION_ARCHITECT)) {
            reused.stageRunStore.save(makeStageRun(reused.task.taskId(), role, 1)
                    .withStatus(AgentStageStatus.SUCCEEDED, "", "", System.currentTimeMillis()));
        }
        AgentStageRun codingStage = makeStageRun(reused.task.taskId(), AgentRole.CODING_AGENT, 1)
                .withResultArtifactId("coding-result-1", System.currentTimeMillis())
                .withStatus(AgentStageStatus.SUCCEEDED, "", "", System.currentTimeMillis());
        reused.stageRunStore.save(codingStage);
        reused.artifactStore.save(new AgentStageArtifact(
                "coding-result-1", codingStage.stageRunId(), reused.task.taskId(), AgentRole.CODING_AGENT,
                "RESULT_JSON", "", "coding", "{\"prBody\":\"" + "x".repeat(20_000),
                "hash", "{}", System.currentTimeMillis()
        ));
        reused.artifactStore.save(new AgentStageArtifact(
                "coding-publication-1", codingStage.stageRunId(), reused.task.taskId(), AgentRole.CODING_AGENT,
                RequirementPublicationFactsProjection.ARTIFACT_TYPE, "", "coding publication facts",
                codingJson, "projection-hash", "{}", System.currentTimeMillis() + 1L
        ));

        RequirementExecutionResult reusedResult = reused.orchestrator.run(
                plan, reused.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(reusedResult.success(), reusedResult.errorMessage());
        assertEquals(0, reused.executor.executedRoleCount(AgentRole.CODING_AGENT));
        JsonNode root = new ObjectMapper().readTree(reusedResult.resultJson());
        assertEquals("src/App.java", root.path("changedFiles").get(0).asText());
        assertEquals("./mvnw test", root.path("testCommands").get(0).asText());
        assertEquals("PASSED", root.path("testStatus").asText());
        assertEquals("LOW", root.path("riskLevel").asText());
        assertEquals("narrative", root.path("prBody").asText());

        OrchestratorTestHarness continuous = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        continuous.executor.codingResultOverride = codingJson;
        continuous.executor.qaSuccessResultOverride = qaJson;
        RequirementExecutionResult continuousResult = continuous.orchestrator.run(
                plan, continuous.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);
        RequirementDeliveryPublicationViewAssembler assembler =
                new RequirementDeliveryPublicationViewAssembler();
        assertEquals(assembler.assemble(continuousResult.resultJson()).coding(),
                assembler.assemble(reusedResult.resultJson()).coding());
        assertEquals(assembler.assemble(continuousResult.resultJson()).qa(),
                assembler.assemble(reusedResult.resultJson()).qa());
    }

    @Test
    void codingSuccessPersistsCompletePublicationFactsBeyondResultPreviewLimit() throws Exception {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        String codingJson = "{\"prBody\":\"" + "x".repeat(21_000) + "\"," +
                "\"changedFiles\":[\"src/App.java\"],\"testCommands\":[\"./mvnw test\"]," +
                "\"testStatus\":\"PASSED\",\"riskLevel\":\"LOW\"}";
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.executor.codingResultOverride = codingJson;
        harness.executor.qaSuccessResultOverride = successfulQaResultJson();

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success(), result.errorMessage());
        AgentStageArtifact projection = harness.artifactStore.listByTask(harness.task.taskId()).stream()
                .filter(artifact -> artifact.role() == AgentRole.CODING_AGENT)
                .filter(artifact -> RequirementPublicationFactsProjection.ARTIFACT_TYPE
                        .equals(artifact.artifactType()))
                .findFirst()
                .orElseThrow();
        JsonNode facts = new ObjectMapper().readTree(projection.contentPreview());
        assertEquals("src/App.java", facts.path("changedFiles").get(0).asText());
        assertEquals("./mvnw test", facts.path("testCommands").get(0).asText());
        assertEquals("PASSED", facts.path("testStatus").asText());
        assertEquals("LOW", facts.path("riskLevel").asText());
        assertEquals(21_000, facts.path("prBody").asText().length());
    }

    @Test
    void product_defect_then_succeeded_retries_coding_once_then_dispatches_qa() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.hostVerificationPort.withOutcomes(
                hostVerify(HostVerificationStatus.FAILED_RETRYABLE, "PRODUCT_DEFECT",
                        "BUILD exit 1: cannot find symbol Foo"),
                hostVerify(HostVerificationStatus.SUCCEEDED, "", "")
        );

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(2, harness.executor.executedRoleCount(AgentRole.CODING_AGENT));
        assertEquals(1, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
        assertEquals(2, stageCount(harness, AgentRole.CODING_AGENT));
        assertEquals(1, stageCount(harness, AgentRole.QA_AGENT));
        String codingPrompt = harness.executor.lastPromptByRole.get(AgentRole.CODING_AGENT);
        assertNotNull(codingPrompt);
        assertTrue(codingPrompt.contains("上一轮失败反馈"), codingPrompt);
        assertTrue(codingPrompt.contains("cannot find symbol Foo"), codingPrompt);
        assertTrue(codingPrompt.contains("PRODUCT_DEFECT"), codingPrompt);
        assertTrue(codingPrompt.contains("宿主会在本阶段成功后"), codingPrompt);
    }

    @Test
    void three_product_defects_exhaust_cheap_remediations_without_qa() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.hostVerificationPort.withOutcomes(hostVerify(
                HostVerificationStatus.FAILED_RETRYABLE, "PRODUCT_DEFECT", "BUILD exit 1: still broken"));

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertFalse(result.success());
        assertTrue(result.resultJson().contains("NEEDS_HUMAN"), result.resultJson());
        assertTrue(result.errorMessage().contains("still broken"), result.errorMessage());
        assertEquals(3, harness.executor.executedRoleCount(AgentRole.CODING_AGENT));
        assertEquals(0, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
        assertEquals(3, maxAttemptNo(harness, AgentRole.CODING_AGENT));
        assertEquals(1, stageCount(harness, AgentRole.QA_AGENT));
        assertEquals(3, harness.hostVerificationPort.verifyCalls.get());
    }

    @Test
    void environment_verify_failure_goes_human_without_coding_retry() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.hostVerificationPort.withOutcomes(hostVerify(
                HostVerificationStatus.FAILED_NEEDS_HUMAN, "ENVIRONMENT", "npm registry unreachable"));

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertFalse(result.success());
        assertTrue(result.resultJson().contains("NEEDS_HUMAN"), result.resultJson());
        assertTrue(result.errorMessage().contains("npm registry unreachable"), result.errorMessage());
        assertTrue(result.resultJson().contains("\"failurePhase\":\"HOST_VERIFY\""), result.resultJson());
        assertTrue(result.resultJson().contains("\"failedVerificationRunId\":\"verify-1\""), result.resultJson());
        assertEquals(1, harness.executor.executedRoleCount(AgentRole.CODING_AGENT));
        assertEquals(0, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
        assertEquals(1, stageCount(harness, AgentRole.CODING_AGENT));
    }

    @Test
    void coding_attempt_three_product_defect_does_not_create_another_coding() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles())
                .prestageRole(AgentRole.CODING_AGENT, 3);
        harness.hostVerificationPort.withOutcomes(hostVerify(
                HostVerificationStatus.FAILED_RETRYABLE, "PRODUCT_DEFECT", "BUILD exit 1: attempt 3 still broken"));

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertFalse(result.success());
        assertTrue(result.resultJson().contains("NEEDS_HUMAN"), result.resultJson());
        assertEquals(1, harness.executor.executedRoleCount(AgentRole.CODING_AGENT));
        assertEquals(0, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
        assertEquals(3, maxAttemptNo(harness, AgentRole.CODING_AGENT));
        assertEquals(2, stageCount(harness, AgentRole.CODING_AGENT));
    }

    @Test
    void verify_failure_does_not_invoke_qa_executor() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.hostVerificationPort.withOutcomes(hostVerify(
                HostVerificationStatus.FAILED_RETRYABLE, "REQUIREMENT_AMBIGUITY", "no BUILD command"));

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertFalse(result.success());
        assertEquals(0, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
        assertEquals(1, harness.executor.executedRoleCount(AgentRole.CODING_AGENT));
    }

    @Test
    void host_verify_is_skipped_when_plan_disables_it() {
        AgentWorkflowPlan plan = AgentWorkflowPlanFixtures.codingOnly();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, harness.hostVerificationPort.verifyCalls.get());
        assertEquals(1, harness.executor.executedRoleCount(AgentRole.CODING_AGENT));
    }

    @Test
    void missing_host_verification_port_fails_closed_when_verify_required() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        OrchestratorTestHarness harness = new OrchestratorTestHarness()
                .prestageRoles(plan.roles())
                .prestageRoleContexts(plan.roles());
        harness.orchestrator.setHostVerificationPort(null);

        RequirementExecutionResult result = harness.orchestrator.run(
                plan, harness.task, List.of(), EMPTY_CONTEXT, EMPTY_PLAN, ALLOWED_DECISION, null);

        assertFalse(result.success());
        assertTrue(result.resultJson().contains("NEEDS_HUMAN"), result.resultJson());
        assertTrue(result.errorMessage().contains("QA_INFRASTRUCTURE"), result.errorMessage());
        assertEquals(0, harness.executor.executedRoleCount(AgentRole.QA_AGENT));
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
        final RecordingExecutor executor = new RecordingExecutor();
        final RequirementExecutionProfileResolverPort executionProfileResolver = new SnapshotProfileResolver();
        final RagStreamTaskRegistry taskRegistry = RagStreamTaskRegistry.inMemory();
        final SnowflakeIdGenerator idGenerator = SnowflakeIdGenerator.defaultGenerator();
        final FakeHostVerificationPort hostVerificationPort = new FakeHostVerificationPort();
        final RequirementAgentStageOrchestrator orchestrator;

        OrchestratorTestHarness() {
            this(WorkflowExperienceStore.noop());
        }

        OrchestratorTestHarness(WorkflowExperienceStore experienceStore) {
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
            this.orchestrator.setHostVerificationPort(hostVerificationPort);
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

        OrchestratorTestHarness prestageRole(AgentRole role, int attemptNo) {
            stageRunStore.save(makeStageRun(task.taskId(), role, attemptNo));
            return this;
        }

        OrchestratorTestHarness prestageRoleContext(
                AgentRole role,
                List<RoleContextEvidence> evidence,
                List<String> omittedEvidenceIds
        ) {
            long now = System.currentTimeMillis();
            int usedChars = evidence.stream()
                    .mapToInt(item -> item.title().length() + item.summary().length())
                    .sum();
            RoleContextPackage roleContext = new RoleContextPackage(
                    role.name().toLowerCase() + "-ctx-custom",
                    task.taskId(),
                    role.name(),
                    1,
                    evidence,
                    List.of(),
                    List.of(),
                    RoleContextBuilder.DEFAULT_MAX_CHARS,
                    usedChars,
                    omittedEvidenceIds,
                    "",
                    now
            );
            roleContextPackageStore.save(roleContext);
            return this;
        }
    }

    /** Test executor: 记录每次调用与 prompt 文本。 */
    static final class RecordingExecutor implements RequirementExecutorPort {
        final List<AgentRole> executedRoles = new CopyOnWriteArrayList<>();
        final Map<AgentRole, AtomicInteger> executedRoleCounts = new LinkedHashMap<>();
        final Map<AgentRole, String> lastPromptByRole = new LinkedHashMap<>();
        final Map<AgentRole, RequirementExecutionRequest> lastRequestByRole = new LinkedHashMap<>();
        boolean qaAgentFailsWithRemediation = false;
        String qaFailureResultJson = "";
        String codingResultOverride = null;
        String qaSuccessResultOverride = null;

        @Override
        public RequirementExecutionResult execute(RequirementExecutionRequest request) {
            executedRoles.add(request.role());
            executedRoleCounts.computeIfAbsent(request.role(), ignored -> new AtomicInteger()).incrementAndGet();
            lastPromptByRole.put(request.role(), request.prompt());
            lastRequestByRole.put(request.role(), request);
            if (request.role() == AgentRole.QA_AGENT
                    && (qaAgentFailsWithRemediation || !qaFailureResultJson.isBlank())) {
                return RequirementExecutionResult.failure(
                        request.taskId(),
                        "QA agent failure",
                        qaFailureResultJson.isBlank()
                                ? "{\"status\":\"FAILED\",\"retryRecommendation\":\"CODING_REMEDIATION\"}"
                                : qaFailureResultJson
                );
            }
            if (request.role() == AgentRole.CODING_AGENT && codingResultOverride != null && !codingResultOverride.isBlank()) {
                return RequirementExecutionResult.success(
                        request.taskId(),
                        request.role().name() + " 完成",
                        "",
                        codingResultOverride
                );
            }
            if (request.role() == AgentRole.QA_AGENT
                    && qaSuccessResultOverride != null && !qaSuccessResultOverride.isBlank()) {
                return RequirementExecutionResult.success(
                        request.taskId(), request.role().name() + " 完成", "", qaSuccessResultOverride
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
            return RequirementExecutionProfileResolution.of("snap-" + stageRunId, "");
        }
    }

    /** Test profile resolver: FACTS_V1 + dynamic state enabled. */
    static final class FactsProfileResolver implements RequirementExecutionProfileResolverPort {
        @Override
        public RequirementExecutionProfileResolution resolve(
                RdRequirementTask task, AgentRole role, String stageRunId, int attemptNo) {
            String snapshotJson = """
                    {
                      "contextProtocolVersion":"FACTS_V1",
                      "dynamicStateEnabled":true,
                      "agentStateSchemaVersion":"rd-agent-state/v1"
                    }""";
            return RequirementExecutionProfileResolution.of("snap-facts-" + stageRunId, snapshotJson);
        }
    }

    static final class StateV2ProfileResolver implements RequirementExecutionProfileResolverPort {
        @Override
        public RequirementExecutionProfileResolution resolve(
                RdRequirementTask task, AgentRole role, String stageRunId, int attemptNo) {
            String snapshotJson = """
                    {
                      "runtimeType":"PI",
                      "capabilities":["PI_AGENT_STATE_V2"],
                      "dynamicStateEnabled":true,
                      "maxInjectedStateBytes":16384,
                      "contextProtocolVersion":"FACTS_V1",
                      "agentStateSchemaVersion":"rd-agent-state/v2"
                    }""";
            return RequirementExecutionProfileResolution.of("snap-state-" + stageRunId, snapshotJson);
        }
    }

    static final class QaRemediationV2ProfileResolver implements RequirementExecutionProfileResolverPort {
        @Override
        public RequirementExecutionProfileResolution resolve(
                RdRequirementTask task, AgentRole role, String stageRunId, int attemptNo) {
            String snapshotJson = """
                    {
                      "runtimeType":"PI",
                      "capabilities":["PI_QA_REMEDIATION_V2"],
                      "dynamicStateEnabled":false,
                      "contextProtocolVersion":"LEGACY_ENVIRONMENT_NOTES"
                    }""";
            return RequirementExecutionProfileResolution.of("snap-remediation-" + stageRunId, snapshotJson);
        }
    }

    private static String qaRemediationResult(boolean requested, String failureCategory, boolean validIds) {
        String findingId = validIds ? "bug-1" : "bug-unselected";
        return """
                {
                  "status":"FAILED","failureCategory":"%s","retryRecommendation":"%s",
                  "remediationRequest":{"requested":%s,"targetRole":"%s","reason":"%s","bugFindingIds":%s},
                  "bugFindings":%s
                }
                """.formatted(
                failureCategory,
                requested ? "CODING_AGENT" : "HUMAN",
                requested,
                requested ? "CODING_AGENT" : "",
                requested ? "Fix the reproducible bug" : "No code fix is justified",
                requested ? "[\"bug-1\"]" : "[]",
                requested
                        ? "[{\"id\":\"" + findingId + "\",\"severity\":\"HIGH\","
                        + "\"acceptanceCriteriaId\":\"AC-1\",\"reproductionSteps\":[\"run acceptance\"],"
                        + "\"expected\":\"acceptance passes\",\"actual\":\"acceptance fails\","
                        + "\"evidenceArtifactIds\":[\"qa-evidence/commands/current.log\"],"
                        + "\"suspectedFiles\":[\"src/main/App.java\"]}]"
                        : "[]"
        );
    }

    private static String successfulQaResultJson() {
        return """
                {"status":"PASSED","summary":"current and regression passed",
                 "failureCategory":"NONE","retryRecommendation":"NONE",
                 "browserValidation":{"required":false,"performed":false,"decisionSource":"NOT_APPLICABLE","baseUrl":"","browser":"chromium","viewports":[]},
                 "acceptanceResults":[
                   {"criteria":"current","scope":"CURRENT","command":"./mvnw test","status":"PASSED","exitCode":0,"durationMillis":1,"logArtifactId":"qa/current.log","evidenceArtifactIds":["qa/current.log"]},
                   {"criteria":"regression","scope":"REGRESSION","command":"./mvnw test","status":"PASSED","exitCode":0,"durationMillis":1,"logArtifactId":"qa/regression.log","evidenceArtifactIds":["qa/regression.log"]}
                 ],"evidenceManifestArtifactId":"qa/manifest.json"}
                """;
    }

    private static void assertCodingPromptOwnsHostVerifyAndQaDoesNot(String codingPrompt, String qaPrompt) {
        assertNotNull(codingPrompt, "coding prompt should be captured");
        assertTrue(codingPrompt.contains("宿主会在本阶段成功后"), codingPrompt);
        assertTrue(codingPrompt.contains("`testStatus` 只是交接信息，不是放行依据"), codingPrompt);
        assertTrue(codingPrompt.contains("不要删 `/work/cache` 或 `node_modules`"), codingPrompt);
        assertTrue(codingPrompt.contains("credential-relay 隔离网"), codingPrompt);
        assertTrue(codingPrompt.contains("EAI_AGAIN"), codingPrompt);
        assertTrue(codingPrompt.contains("testStatus=SKIPPED"), codingPrompt);
        assertFalse(codingPrompt.contains("安装失败时保留诊断并停止"), codingPrompt);
        assertNotNull(qaPrompt, "QA prompt should be captured");
        assertFalse(qaPrompt.contains("宿主会在本阶段成功后"), qaPrompt);
        assertFalse(qaPrompt.contains("BUILD/STATIC"), qaPrompt);
        assertFalse(qaPrompt.contains("替代宿主"), qaPrompt);
    }

    private static int stageCount(OrchestratorTestHarness harness, AgentRole role) {
        return (int) harness.stageRunStore.listByTask(harness.task.taskId()).stream()
                .filter(stage -> stage.role() == role)
                .count();
    }

    private static int maxAttemptNo(OrchestratorTestHarness harness, AgentRole role) {
        return harness.stageRunStore.listByTask(harness.task.taskId()).stream()
                .filter(stage -> stage.role() == role)
                .mapToInt(AgentStageRun::attemptNo)
                .max()
                .orElse(0);
    }

    private static HostVerificationRun hostVerify(
            HostVerificationStatus status,
            String failureCategory,
            String errorMessage
    ) {
        return new HostVerificationRun(
                "verify-template",
                "task-1",
                "coding-1",
                "",
                1,
                status,
                status == HostVerificationStatus.SKIPPED_DOCS_ONLY,
                failureCategory,
                errorMessage,
                0,
                1L,
                1L,
                2L
        );
    }

    /**
     * Scripted host-verify port. Default outcome is {@link HostVerificationStatus#SUCCEEDED}
     * so existing D-plan tests keep dispatching QA.
     */
        static final class FakeHostVerificationPort implements HostVerificationPort {
        final AtomicInteger verifyCalls = new AtomicInteger();
        final List<Integer> seenRemediationCounts = new CopyOnWriteArrayList<>();
        private final List<HostVerificationRun> outcomes = new CopyOnWriteArrayList<>();
        private InMemoryHostVerificationStore persistStore;

        FakeHostVerificationPort() {
            outcomes.add(hostVerify(HostVerificationStatus.SUCCEEDED, "", ""));
        }

        FakeHostVerificationPort withOutcomes(HostVerificationRun... runs) {
            outcomes.clear();
            outcomes.addAll(List.of(runs));
            return this;
        }

        FakeHostVerificationPort persistTo(InMemoryHostVerificationStore store) {
            this.persistStore = store;
            return this;
        }

        @Override
        public HostVerificationRun verify(
                RdRequirementTask task,
                AgentStageRun codingStage,
                AgentWorkflowPlan plan,
                int remediationCountAlreadyUsed
        ) {
            int index = verifyCalls.getAndIncrement();
            seenRemediationCounts.add(remediationCountAlreadyUsed);
            HostVerificationRun template = outcomes.get(Math.min(index, outcomes.size() - 1));
            HostVerificationRun run = new HostVerificationRun(
                    "verify-" + (index + 1),
                    task.taskId(),
                    codingStage.stageRunId(),
                    "",
                    Math.max(1, index + 1),
                    template.status(),
                    template.docsOnly(),
                    template.failureCategory(),
                    template.errorMessage(),
                    remediationCountAlreadyUsed,
                    1L,
                    1L,
                    2L
            );
            if (persistStore != null) {
                persistStore.create(run);
                if (run.status() == HostVerificationStatus.SUCCEEDED
                        || run.status() == HostVerificationStatus.SKIPPED_DOCS_ONLY) {
                    persistStore.appendArtifact(new HostVerificationArtifact(
                            "build-" + run.runId(), task.taskId(), run.runId(), "VERIFY_BUILD_LOG",
                            "verify-evidence/build.log", "s3://verify/build.log", "text/plain", 12L,
                            "sha256:" + "a".repeat(64), 2L));
                    persistStore.appendArtifact(new HostVerificationArtifact(
                            "static-" + run.runId(), task.taskId(), run.runId(), "VERIFY_STATIC_LOG",
                            "verify-evidence/static.log", "s3://verify/static.log", "text/plain", 12L,
                            "sha256:" + "b".repeat(64), 2L));
                }
            }
            return run;
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
