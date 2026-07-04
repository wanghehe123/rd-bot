package com.wish.rd.engine.requirement;

/**
 * 需求交付复核结果。
 *
 * @param taskId   RD 任务 ID
 * @param approved 是否允许提交为已交付
 * @param reason   拒绝原因
 */
public record RequirementDeliveryReviewResult(String taskId, boolean approved, String reason) {

    public RequirementDeliveryReviewResult {
        taskId = safe(taskId);
        reason = safe(reason);
    }

    /**
     * 创建复核通过结果。
     */
    public static RequirementDeliveryReviewResult approved(String taskId) {
        return new RequirementDeliveryReviewResult(taskId, true, "");
    }

    /**
     * 创建复核拒绝结果。
     */
    public static RequirementDeliveryReviewResult rejected(String taskId, String reason) {
        return new RequirementDeliveryReviewResult(taskId, false, reason);
    }

    /**
     * 输出可落库的结构化 JSON。
     */
    public String toJson() {
        return """
                {"taskId":%s,"reviewer":"DELIVERY_REVIEWER","approved":%s,"reason":%s}
                """.formatted(json(taskId), approved, json(reason)).strip();
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
