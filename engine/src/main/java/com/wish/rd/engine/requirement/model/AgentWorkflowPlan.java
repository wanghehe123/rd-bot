package com.wish.rd.engine.requirement.model;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 需求交付 Agent 工作流计划。
 *
 * <p>定义一次需求交付（或编码基准评测）所执行的 Agent 角色序列，以及检索、QA 修复回路、
 * 宿主 BUILD/STATIC 廉价返工、Token 预算账本等受 governance 约束的开关。固定为不可变 record，
 * 所有构造路径都要经过防御性校验，避免运行时出现"无名角色异构比例"或"误开 QA/宿主验证修复回路"
 * 这类 spec §4.3/§5.2 明确禁止的配置漂移。
 *
 * <p>生产路径使用的 {@link #production()} 计划严格等价于 {@link CodingBenchmarkArm#D} 的 plan，
 * 其余三个 arm（A/B/C）由 {@link #codingBenchmark(CodingBenchmarkArm)} 工厂给出，主要用于编码
 * 消融评测。
 */
public record AgentWorkflowPlan(
        List<AgentRole> roles,
        boolean retrievalEnabled,
        boolean qaRemediationEnabled,
        int qaMaxRemediationPasses,
        boolean hostVerifyRemediationEnabled,
        int hostVerifyMaxRemediationPasses,
        Map<AgentRole, Double> budgetLedger,
        String source
) {

    /** 默认 QA 修复回路深度上限（spec §5.2：一次性修复回路）。 */
    public static final int DEFAULT_QA_REMEDIATION_PASSES = 1;

    /** 默认宿主验证廉价返工次数上限（生产与 arm D：两次廉价 Coding 返工）。 */
    public static final int DEFAULT_HOST_VERIFY_REMEDIATION_PASSES = 2;

    /** 同名角色等额比例的最大合法和（不可超过 1.0，超出的比例保持不可用，不回流，spec §4.3）。 */
    public static final double MAX_BUDGET_SUM = 1.0d;

    private static final double EPSILON = 1.0e-9d;

    public AgentWorkflowPlan {
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(budgetLedger, "budgetLedger");
        Objects.requireNonNull(source, "source");
        roles = List.copyOf(roles);
        budgetLedger = Collections.unmodifiableMap(new LinkedHashMap<>(budgetLedger));
        validate(
                roles,
                retrievalEnabled,
                qaRemediationEnabled,
                qaMaxRemediationPasses,
                hostVerifyRemediationEnabled,
                hostVerifyMaxRemediationPasses,
                budgetLedger,
                source);
    }

    /**
     * 生产路径默认计划：四角色全开 + 检索 + QA 一次性修复 + 两次宿主验证廉价返工，等价于 {@code codingBenchmark(D)}。
     */
    public static AgentWorkflowPlan production() {
        return codingBenchmark(CodingBenchmarkArm.D);
    }

    /**
     * 按编码基准评测 arm 返回对应的 workflow plan。
     *
     * <ul>
     *     <li>A: 仅 CODING_AGENT，无检索、无 QA、无宿主验证廉价返工</li>
     *     <li>B/C: REVIEWER + ARCHITECT + CODING_AGENT；B 无检索，C 开启检索；均无 QA / 宿主验证廉价返工</li>
     *     <li>D: REVIEWER + ARCHITECT + CODING_AGENT + QA_AGENT；开启检索 + 一次性 QA 修复 + 两次宿主验证廉价返工</li>
     * </ul>
     *
     * @param arm 评测 arm
     * @return 该 arm 对应的 plan
     */
    public static AgentWorkflowPlan codingBenchmark(CodingBenchmarkArm arm) {
        Objects.requireNonNull(arm, "arm");
        return switch (arm) {
            case A -> new AgentWorkflowPlan(
                    List.of(AgentRole.CODING_AGENT),
                    false,
                    false,
                    DEFAULT_QA_REMEDIATION_PASSES,
                    false,
                    DEFAULT_HOST_VERIFY_REMEDIATION_PASSES,
                    ledgerOf(Map.of(AgentRole.CODING_AGENT, 0.56d)),
                    "CODING_BENCHMARK_ARM_A");
            case B -> new AgentWorkflowPlan(
                    List.of(AgentRole.REQUIREMENT_REVIEWER, AgentRole.SOLUTION_ARCHITECT, AgentRole.CODING_AGENT),
                    false,
                    false,
                    DEFAULT_QA_REMEDIATION_PASSES,
                    false,
                    DEFAULT_HOST_VERIFY_REMEDIATION_PASSES,
                    ledgerOf(Map.of(
                            AgentRole.REQUIREMENT_REVIEWER, 0.08d,
                            AgentRole.SOLUTION_ARCHITECT, 0.16d,
                            AgentRole.CODING_AGENT, 0.56d)),
                    "CODING_BENCHMARK_ARM_B");
            case C -> new AgentWorkflowPlan(
                    List.of(AgentRole.REQUIREMENT_REVIEWER, AgentRole.SOLUTION_ARCHITECT, AgentRole.CODING_AGENT),
                    true,
                    false,
                    DEFAULT_QA_REMEDIATION_PASSES,
                    false,
                    DEFAULT_HOST_VERIFY_REMEDIATION_PASSES,
                    ledgerOf(Map.of(
                            AgentRole.REQUIREMENT_REVIEWER, 0.08d,
                            AgentRole.SOLUTION_ARCHITECT, 0.16d,
                            AgentRole.CODING_AGENT, 0.56d)),
                    "CODING_BENCHMARK_ARM_C");
            case D -> new AgentWorkflowPlan(
                    List.of(AgentRole.REQUIREMENT_REVIEWER,
                            AgentRole.SOLUTION_ARCHITECT,
                            AgentRole.CODING_AGENT,
                            AgentRole.QA_AGENT),
                    true,
                    true,
                    DEFAULT_QA_REMEDIATION_PASSES,
                    true,
                    DEFAULT_HOST_VERIFY_REMEDIATION_PASSES,
                    ledgerOf(Map.of(
                            AgentRole.REQUIREMENT_REVIEWER, 0.08d,
                            AgentRole.SOLUTION_ARCHITECT, 0.16d,
                            AgentRole.CODING_AGENT, 0.56d,
                            AgentRole.QA_AGENT, 0.08d)),
                    "CODING_BENCHMARK_ARM_D");
        };
    }

    private static Map<AgentRole, Double> ledgerOf(Map<AgentRole, Double> ledger) {
        return new LinkedHashMap<>(ledger);
    }

    private static void validate(
            List<AgentRole> roles,
            boolean retrievalEnabled,
            boolean qaRemediationEnabled,
            int qaMaxRemediationPasses,
            boolean hostVerifyRemediationEnabled,
            int hostVerifyMaxRemediationPasses,
            Map<AgentRole, Double> budgetLedger,
            String source
    ) {
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("AgentWorkflowPlan roles must not be empty: " + source);
        }
        if (qaMaxRemediationPasses < 0) {
            throw new IllegalArgumentException("qaMaxRemediationPasses must be non-negative: " + source);
        }
        if (qaRemediationEnabled && qaMaxRemediationPasses == 0) {
            throw new IllegalArgumentException(
                    "qaRemediationEnabled=true requires qaMaxRemediationPasses >= 1: " + source);
        }
        if (qaRemediationEnabled && !roles.contains(AgentRole.QA_AGENT)) {
            throw new IllegalArgumentException(
                    "qaRemediationEnabled requires QA_AGENT in roles: " + source);
        }
        if (!qaRemediationEnabled && qaMaxRemediationPasses > DEFAULT_QA_REMEDIATION_PASSES) {
            // QA 修复回路关闭时不允许配置非默认上限，避免"配置漂移"屏蔽实际开关。
            throw new IllegalArgumentException(
                    "qaMaxRemediationPasses > 1 requires qaRemediationEnabled=true: " + source);
        }
        if (hostVerifyMaxRemediationPasses < 0) {
            throw new IllegalArgumentException("hostVerifyMaxRemediationPasses must be non-negative: " + source);
        }
        if (hostVerifyRemediationEnabled && hostVerifyMaxRemediationPasses == 0) {
            throw new IllegalArgumentException(
                    "hostVerifyRemediationEnabled=true requires hostVerifyMaxRemediationPasses >= 1: " + source);
        }
        if (hostVerifyRemediationEnabled && !roles.contains(AgentRole.CODING_AGENT)) {
            throw new IllegalArgumentException(
                    "hostVerifyRemediationEnabled requires CODING_AGENT in roles: " + source);
        }
        if (!hostVerifyRemediationEnabled
                && hostVerifyMaxRemediationPasses > DEFAULT_HOST_VERIFY_REMEDIATION_PASSES) {
            // 宿主验证廉价返工关闭时不允许抬高上限，避免"配置漂移"屏蔽实际开关。
            throw new IllegalArgumentException(
                    "hostVerifyMaxRemediationPasses > "
                            + DEFAULT_HOST_VERIFY_REMEDIATION_PASSES
                            + " requires hostVerifyRemediationEnabled=true: "
                            + source);
        }
        Map<AgentRole, Double> required = new LinkedHashMap<>();
        for (AgentRole role : roles) {
            required.putIfAbsent(role, null);
        }
        for (Map.Entry<AgentRole, Double> entry : budgetLedger.entrySet()) {
            AgentRole role = entry.getKey();
            Double share = entry.getValue();
            if (share == null || Double.isNaN(share) || share < 0d || share > 1d) {
                throw new IllegalArgumentException(
                        "budgetLedger share must be in [0, 1] for role " + role + ": " + source);
            }
            if (!required.containsKey(role)) {
                throw new IllegalArgumentException(
                        "budgetLedger contains role not in roles: " + role + " source=" + source);
            }
            required.put(role, share);
        }
        for (Map.Entry<AgentRole, Double> entry : required.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "budgetLedger missing share for role " + entry.getKey() + ": " + source);
            }
        }
        double sum = required.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum > MAX_BUDGET_SUM + EPSILON) {
            throw new IllegalArgumentException(
                    "budgetLedger sum must be <= " + MAX_BUDGET_SUM + " (got " + sum + "): " + source);
        }
    }

    /**
     * 取指定角色的预算占比。缺失或为 0 视为不可用，不回流（spec §4.3）。
     */
    public double budgetShare(AgentRole role) {
        Double share = budgetLedger.get(role);
        return share == null ? 0d : share;
    }

    /**
     * 是否启用 QA 修复回路，且当前 plan 包含 QA_AGENT 角色。
     *
     * @return {@code true} 当 QA 修复回路对当前角色集生效
     */
    public boolean qaRemediationAllowed() {
        return qaRemediationEnabled && roles.contains(AgentRole.QA_AGENT);
    }

    /**
     * 是否启用宿主验证廉价返工，且当前 plan 包含 CODING_AGENT。
     *
     * @return {@code true} 当宿主验证失败可以新建 Coding attempt
     */
    public boolean hostVerifyRemediationAllowed() {
        return hostVerifyRemediationEnabled && roles.contains(AgentRole.CODING_AGENT);
    }
}
