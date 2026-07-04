package com.wish.rd.skill;

/**
 * Skill 安装请求。
 *
 * @param taskId     RD 任务 ID
 * @param stageRunId 阶段运行 ID
 * @param role       Agent 角色
 * @param skillId    Skill ID
 * @param version    期望版本
 */
public record SkillInstallCommand(
        String taskId,
        String stageRunId,
        String role,
        String skillId,
        String version
) {

    public SkillInstallCommand {
        taskId = safe(taskId);
        stageRunId = safe(stageRunId);
        role = safe(role).toUpperCase();
        skillId = safe(skillId);
        version = safe(version);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
