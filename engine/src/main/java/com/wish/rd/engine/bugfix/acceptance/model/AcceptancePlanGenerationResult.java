package com.wish.rd.engine.bugfix.acceptance.model;

/**
 * 验收计划生成结果。
 *
 * @param plan 生成的计划
 */
public record AcceptancePlanGenerationResult(
        AcceptancePlan plan
) {

    public AcceptancePlanGenerationResult {
        plan = plan == null
                ? AcceptancePlan.disabled("", "", "acceptance planner returned no plan")
                : plan;
    }
}
