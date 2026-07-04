package com.wish.rd.skill;

import java.util.List;

/**
 * 可供 Agent 使用的 Skill 描述。
 *
 * <p>供 registry、策略门禁和安装器共同使用；具体安装命令由 bootstrap 适配层执行。
 *
 * @param skillId        Skill ID
 * @param version        版本或 commit
 * @param sourceUri      来源 URI
 * @param checksum       内容校验和
 * @param allowedRoles   允许使用的 Agent 角色
 * @param riskLevel      风险等级
 * @param installCommand 安装命令描述
 * @param description    说明
 */
public record AgentSkillDescriptor(
        String skillId,
        String version,
        String sourceUri,
        String checksum,
        List<String> allowedRoles,
        SkillRiskLevel riskLevel,
        String installCommand,
        String description
) {

    public AgentSkillDescriptor {
        skillId = requireText(skillId, "skillId");
        version = safe(version);
        sourceUri = safe(sourceUri);
        checksum = safe(checksum);
        allowedRoles = allowedRoles == null
                ? List.of()
                : allowedRoles.stream().map(AgentSkillDescriptor::normalizeRole).filter(value -> !value.isBlank()).toList();
        riskLevel = riskLevel == null ? SkillRiskLevel.UNKNOWN : riskLevel;
        installCommand = safe(installCommand);
        description = safe(description);
    }

    private static String normalizeRole(String value) {
        return safe(value).toUpperCase();
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
