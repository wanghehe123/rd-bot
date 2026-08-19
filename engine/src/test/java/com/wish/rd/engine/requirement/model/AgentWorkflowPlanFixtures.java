package com.wish.rd.engine.requirement.model;

import com.wish.rd.engine.agent.model.AgentRole;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排测试用的 {@link AgentWorkflowPlan} 形态样本。
 *
 * <p>覆盖生产计划之外的角色/开关组合，用来验证编排器在缺少 QA_AGENT、关闭检索或关闭宿主验证
 * 廉价返工时的行为。生产路径只有 {@link AgentWorkflowPlan#production()} 一种计划，这些精简形态
 * 仅存在于测试装置中，不得回流到生产代码。
 */
public final class AgentWorkflowPlanFixtures {

    private AgentWorkflowPlanFixtures() {
    }

    /** 仅 CODING_AGENT：无检索、无 QA 修复、无宿主验证廉价返工。 */
    public static AgentWorkflowPlan codingOnly() {
        return new AgentWorkflowPlan(
                List.of(AgentRole.CODING_AGENT),
                false,
                false,
                AgentWorkflowPlan.DEFAULT_QA_REMEDIATION_PASSES,
                false,
                AgentWorkflowPlan.DEFAULT_HOST_VERIFY_REMEDIATION_PASSES,
                ledgerOf(Map.of(AgentRole.CODING_AGENT, 0.56d)),
                "CODING_ONLY");
    }

    /** REVIEWER + ARCHITECT + CODING_AGENT，关闭检索、无 QA 修复、无宿主验证廉价返工。 */
    public static AgentWorkflowPlan reviewArchitectCoding() {
        return reviewArchitectCoding(false, "REVIEW_ARCHITECT_CODING");
    }

    /** REVIEWER + ARCHITECT + CODING_AGENT，开启检索、无 QA 修复、无宿主验证廉价返工。 */
    public static AgentWorkflowPlan reviewArchitectCodingWithRetrieval() {
        return reviewArchitectCoding(true, "REVIEW_ARCHITECT_CODING_RETRIEVAL");
    }

    private static AgentWorkflowPlan reviewArchitectCoding(boolean retrievalEnabled, String source) {
        return new AgentWorkflowPlan(
                List.of(AgentRole.REQUIREMENT_REVIEWER, AgentRole.SOLUTION_ARCHITECT, AgentRole.CODING_AGENT),
                retrievalEnabled,
                false,
                AgentWorkflowPlan.DEFAULT_QA_REMEDIATION_PASSES,
                false,
                AgentWorkflowPlan.DEFAULT_HOST_VERIFY_REMEDIATION_PASSES,
                ledgerOf(Map.of(
                        AgentRole.REQUIREMENT_REVIEWER, 0.08d,
                        AgentRole.SOLUTION_ARCHITECT, 0.16d,
                        AgentRole.CODING_AGENT, 0.56d)),
                source);
    }

    private static Map<AgentRole, Double> ledgerOf(Map<AgentRole, Double> ledger) {
        return new LinkedHashMap<>(ledger);
    }
}
