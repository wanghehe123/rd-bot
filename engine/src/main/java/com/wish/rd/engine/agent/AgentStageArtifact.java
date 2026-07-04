package com.wish.rd.engine.agent;

/**
 * 多 Agent 阶段产物元数据。
 *
 * @param artifactId           产物 ID
 * @param stageRunId           阶段运行 ID
 * @param taskId               RD 任务 ID
 * @param role                 Agent 角色
 * @param artifactType         产物类型
 * @param artifactUri          产物 URI
 * @param summary              摘要
 * @param contentPreview       可审计内容预览
 * @param contentHash          内容 hash
 * @param metadataJson         元数据 JSON
 * @param createdAtEpochMillis 创建时间
 */
public record AgentStageArtifact(
        String artifactId,
        String stageRunId,
        String taskId,
        AgentRole role,
        String artifactType,
        String artifactUri,
        String summary,
        String contentPreview,
        String contentHash,
        String metadataJson,
        long createdAtEpochMillis
) {

    public AgentStageArtifact {
        artifactId = requireText(artifactId, "artifactId");
        stageRunId = requireText(stageRunId, "stageRunId");
        taskId = requireText(taskId, "taskId");
        role = role == null ? AgentRole.REQUIREMENT_REVIEWER : role;
        artifactType = requireText(artifactType, "artifactType");
        artifactUri = safe(artifactUri);
        summary = safe(summary);
        contentPreview = safe(contentPreview);
        contentHash = safe(contentHash);
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson.strip();
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
