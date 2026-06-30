package com.wish.rd.engine.requirement;

/**
 * 需求策略门禁决策。
 *
 * @param action    ALLOWED / WAITING_APPROVAL / NEED_INFO / UNSAFE
 * @param riskLevel 风险等级
 * @param reason    决策原因
 */
public record RequirementPolicyDecision(
        String action,
        String riskLevel,
        String reason
) {

    public RequirementPolicyDecision {
        action = normalize(action, "NEED_INFO");
        riskLevel = normalize(riskLevel, "MEDIUM");
        reason = reason == null ? "" : reason.strip();
    }

    /**
     * 是否允许直接进入执行器。
     *
     * @return true 表示允许执行
     */
    public boolean allowed() {
        return "ALLOWED".equals(action);
    }

    /**
     * 是否等待人工审批。
     *
     * @return true 表示等待审批
     */
    public boolean waitingApproval() {
        return "WAITING_APPROVAL".equals(action);
    }

    /**
     * 返回 JSON 表达。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return """
                {"policyAction":%s,"riskLevel":%s,"reason":%s}
                """.formatted(
                RequirementContextPackage.json(action),
                RequirementContextPackage.json(riskLevel),
                RequirementContextPackage.json(reason)
        ).strip();
    }

    private static String normalize(String value, String fallback) {
        String safe = value == null ? "" : value.strip().toUpperCase();
        return safe.isBlank() ? fallback : safe;
    }
}
