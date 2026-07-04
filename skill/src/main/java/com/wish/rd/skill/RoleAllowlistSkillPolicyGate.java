package com.wish.rd.skill;

/**
 * 基于角色白名单的 Skill 策略门禁。
 *
 * <p>第一版只做确定性策略：角色必须在 allowedRoles 内，高风险 Skill 可配置为等待人工审批。
 */
public final class RoleAllowlistSkillPolicyGate implements SkillPolicyGate {

    private final boolean requireApprovalForHighRisk;

    public RoleAllowlistSkillPolicyGate(boolean requireApprovalForHighRisk) {
        this.requireApprovalForHighRisk = requireApprovalForHighRisk;
    }

    @Override
    public SkillPolicyDecision decide(AgentSkillDescriptor descriptor, String role) {
        if (descriptor == null) {
            return new SkillPolicyDecision(false, "REJECTED", SkillRiskLevel.HIGH, "skill descriptor missing");
        }
        String normalizedRole = role == null ? "" : role.strip().toUpperCase();
        if (!descriptor.allowedRoles().contains(normalizedRole)) {
            return new SkillPolicyDecision(
                    false,
                    "REJECTED",
                    descriptor.riskLevel(),
                    "skill is not allowed for role: " + normalizedRole
            );
        }
        if (requireApprovalForHighRisk && descriptor.riskLevel() == SkillRiskLevel.HIGH) {
            return new SkillPolicyDecision(
                    false,
                    "WAITING_APPROVAL",
                    descriptor.riskLevel(),
                    "high risk skill requires manual approval"
            );
        }
        return new SkillPolicyDecision(true, "ALLOWED", descriptor.riskLevel(), "skill allowed");
    }
}
