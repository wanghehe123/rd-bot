package com.wish.rd.skill;

/**
 * Skill 使用策略决策。
 *
 * @param allowed   是否允许
 * @param action    策略动作
 * @param riskLevel 风险等级
 * @param reason    决策原因
 */
public record SkillPolicyDecision(
        boolean allowed,
        String action,
        SkillRiskLevel riskLevel,
        String reason
) {

    public SkillPolicyDecision {
        action = safe(action);
        riskLevel = riskLevel == null ? SkillRiskLevel.UNKNOWN : riskLevel;
        reason = safe(reason);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
