package com.wish.rd.engine.requirement.model;

/**
 * 需求交付复核结果。
 *
 * @param taskId   RD 任务 ID
 * @param approved 是否允许提交为已交付
 * @param reason   拒绝原因
 * @param factsHash 审核绑定的规范化发布事实指纹
 */
public record RequirementDeliveryReviewResult(
        String taskId,
        boolean approved,
        String reason,
        String factsHash
) {

    public RequirementDeliveryReviewResult {
        taskId = safe(taskId);
        reason = safe(reason);
        factsHash = safe(factsHash);
    }

    /** 源码兼容构造器；空事实指纹不能用于发布或审核恢复。 */
    public RequirementDeliveryReviewResult(String taskId, boolean approved, String reason) {
        this(taskId, approved, reason, "");
    }

    /**
     * 创建复核通过结果。
     */
    public static RequirementDeliveryReviewResult approved(String taskId) {
        return new RequirementDeliveryReviewResult(taskId, true, "", "");
    }

    /** 创建绑定规范化发布事实的复核通过结果。 */
    public static RequirementDeliveryReviewResult approved(String taskId, String factsHash) {
        return new RequirementDeliveryReviewResult(taskId, true, "", factsHash);
    }

    /**
     * 创建复核拒绝结果。
     */
    public static RequirementDeliveryReviewResult rejected(String taskId, String reason) {
        return new RequirementDeliveryReviewResult(taskId, false, reason, "");
    }

    /** 返回绑定指定发布事实指纹的新结果。 */
    public RequirementDeliveryReviewResult withFactsHash(String value) {
        return new RequirementDeliveryReviewResult(taskId, approved, reason, value);
    }

    /**
     * 输出可落库的结构化 JSON。
     */
    public String toJson() {
        return """
                {"taskId":%s,"reviewer":"DELIVERY_REVIEWER","approved":%s,"reason":%s,"factsHash":%s}
                """.formatted(json(taskId), approved, json(reason), json(factsHash)).strip();
    }

    private static String json(String value) {
        return "\"" + safe(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
