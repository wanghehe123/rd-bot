package com.wish.rd.skill.model;

/**
 * Skill 安装结果。
 *
 * @param installed  是否已安装
 * @param skillId    Skill ID
 * @param version    安装版本
 * @param installPath 安装路径
 * @param message    结果消息
 * @param policyJson 策略决策 JSON
 */
public record SkillInstallResult(
        boolean installed,
        String skillId,
        String version,
        String installPath,
        String message,
        String policyJson
) {

    public SkillInstallResult {
        skillId = safe(skillId);
        version = safe(version);
        installPath = safe(installPath);
        message = safe(message);
        policyJson = policyJson == null || policyJson.isBlank() ? "{}" : policyJson.strip();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
