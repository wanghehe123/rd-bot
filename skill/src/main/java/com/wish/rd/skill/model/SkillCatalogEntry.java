package com.wish.rd.skill.model;

import java.time.Instant;
import java.util.List;

/**
 * Skill Hub 目录条目。
 *
 * <p>供目录仓储、管理面与 Pi 物化路径共用；安装门禁仍走
 * {@link com.wish.rd.skill.SkillInstallationEngine} / {@link AgentSkillDescriptor}。
 *
 * @param skillId      Skill ID
 * @param version      版本
 * @param description  说明
 * @param guidePrompt  引导提示词
 * @param forceGuide   是否强制注入引导提示词
 * @param riskLevel    风险等级
 * @param checksum     内容校验和
 * @param sourceUri    来源 URI
 * @param installPath  已安装快照路径
 * @param status       目录状态
 * @param allowedRoles 允许绑定的角色
 * @param updatedAt    最近更新时间
 */
public record SkillCatalogEntry(
        String skillId,
        String version,
        String description,
        String guidePrompt,
        boolean forceGuide,
        SkillRiskLevel riskLevel,
        String checksum,
        String sourceUri,
        String installPath,
        SkillCatalogStatus status,
        List<String> allowedRoles,
        Instant updatedAt
) {

    public SkillCatalogEntry {
        skillId = requireText(skillId, "skillId");
        version = safe(version);
        description = safe(description);
        guidePrompt = safe(guidePrompt);
        riskLevel = riskLevel == null ? SkillRiskLevel.UNKNOWN : riskLevel;
        checksum = safe(checksum);
        sourceUri = safe(sourceUri);
        installPath = safe(installPath);
        status = status == null ? SkillCatalogStatus.DISABLED : status;
        allowedRoles = allowedRoles == null
                ? List.of()
                : allowedRoles.stream().map(SkillCatalogEntry::normalizeRole).filter(value -> !value.isBlank()).toList();
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
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
