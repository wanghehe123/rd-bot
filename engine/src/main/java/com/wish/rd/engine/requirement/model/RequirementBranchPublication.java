package com.wish.rd.engine.requirement.model;

/**
 * 需求交付工作分支推送结果。
 *
 * @param taskId       RD 任务 ID
 * @param success      是否成功（skipped 也视为成功，不阻断后续 PR 创建）
 * @param skipped      是否被跳过（未配置分支推送端口时保持旧行为）
 * @param commitSha    推送后的提交 SHA
 * @param metadataJson 仓库操作元数据 JSON
 * @param errorMessage 失败原因
 */
public record RequirementBranchPublication(
        String taskId,
        boolean success,
        boolean skipped,
        String commitSha,
        String metadataJson,
        String errorMessage
) {

    public RequirementBranchPublication {
        taskId = safe(taskId);
        commitSha = safe(commitSha);
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson.strip();
        errorMessage = safe(errorMessage);
    }

    /** 创建成功推送结果。 */
    public static RequirementBranchPublication success(String taskId, String commitSha, String metadataJson) {
        return new RequirementBranchPublication(taskId, true, false, commitSha, metadataJson, "");
    }

    /**
     * 创建跳过结果：未配置真实分支推送端口时返回，保持旧交付行为不变
     * （由 PR 创建阶段自行处理分支缺失的后果）。
     */
    public static RequirementBranchPublication skipped(String taskId) {
        return new RequirementBranchPublication(taskId, true, true, "", "{}", "");
    }

    /** 创建失败推送结果。 */
    public static RequirementBranchPublication failure(String taskId, String errorMessage) {
        return new RequirementBranchPublication(taskId, false, false, "", "{}", errorMessage);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
