package com.wish.rd.engine.agent.model;

import java.util.List;

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
 * @param projectId            来源项目 ID；历史记录可为空
 * @param repositoryFingerprint 规范化仓库指纹；历史记录可为空
 * @param intentId             来源意图或项目 key
 * @param tags                 可检索标签
 * @param sourceRevision       来源提交或分支修订
 * @param evidenceQuality      证据质量，范围 0..1
 * @param applicableRoles      允许复用该经验的角色
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
        long createdAtEpochMillis,
        String projectId,
        String repositoryFingerprint,
        String intentId,
        List<String> tags,
        String sourceRevision,
        double evidenceQuality,
        List<AgentRole> applicableRoles
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
        projectId = safe(projectId);
        repositoryFingerprint = safe(repositoryFingerprint).toLowerCase(java.util.Locale.ROOT);
        intentId = safe(intentId);
        tags = tags == null ? List.of() : tags.stream().map(WorkflowExperienceEntry::safe)
                .filter(value -> !value.isBlank()).distinct().toList();
        sourceRevision = safe(sourceRevision);
        if (Double.isNaN(evidenceQuality) || Double.isInfinite(evidenceQuality)) {
            evidenceQuality = 0.0d;
        }
        evidenceQuality = Math.max(0.0d, Math.min(1.0d, evidenceQuality));
        applicableRoles = applicableRoles == null ? List.of() : applicableRoles.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
    }

    /** Backward-compatible constructor for rows created before scoped experience metadata. */
    public WorkflowExperienceEntry(
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
        this(
                experienceId, taskId, stageRunId, sourceArtifactId, role, experienceType,
                title, summary, contentJson, reusable, failure, redacted, createdAtEpochMillis,
                "", "", "", List.of(), "", failure ? 0.0d : 1.0d, List.of(role)
        );
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
