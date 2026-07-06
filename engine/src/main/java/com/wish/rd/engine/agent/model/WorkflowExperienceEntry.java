package com.wish.rd.engine.agent.model;

/**
 * 多 Agent 工作流经验条目。
 *
 * @param experienceId         经验条目 ID
 * @param taskId               RD 任务 ID
 * @param stageRunId           阶段运行 ID
 * @param sourceArtifactId     来源阶段产物 ID
 * @param role                 Agent 角色
 * @param experienceType       经验类型
 * @param title                标题
 * @param summary              摘要
 * @param contentJson          结构化内容
 * @param reusable             是否可复用
 * @param failure              是否来自失败路径
 * @param redacted             是否已经脱敏
 * @param createdAtEpochMillis 创建时间
 */
public record WorkflowExperienceEntry(
        String experienceId,
        String taskId,
        String stageRunId,
        String sourceArtifactId,
        AgentRole role,
        WorkflowExperienceType experienceType,
        String title,
        String summary,
        String contentJson,
        boolean reusable,
        boolean failure,
        boolean redacted,
        long createdAtEpochMillis
) {

    public WorkflowExperienceEntry {
        experienceId = requireText(experienceId, "experienceId");
        taskId = requireText(taskId, "taskId");
        stageRunId = safe(stageRunId);
        sourceArtifactId = safe(sourceArtifactId);
        role = role == null ? AgentRole.REQUIREMENT_REVIEWER : role;
        experienceType = experienceType == null ? WorkflowExperienceType.DELIVERY_REPORT : experienceType;
        title = safe(title);
        summary = safe(summary);
        contentJson = contentJson == null || contentJson.isBlank() ? "{}" : contentJson.strip();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
