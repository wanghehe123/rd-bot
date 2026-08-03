package com.wish.rd.skill.model;

/**
 * 角色与 Skill 的绑定关系。
 *
 * <p>供 Skill Hub 管理面与 Pi 物化路径读取；{@code forceGuide=true} 时物化清单使用
 * 目录条目上的 {@code guidePrompt}。
 *
 * @param role       Agent 角色
 * @param skillId    Skill ID
 * @param sortOrder  物化顺序（升序）
 * @param forceGuide 角色级强制引导覆盖
 */
public record SkillRoleBinding(
        String role,
        String skillId,
        int sortOrder,
        boolean forceGuide
) {

    public SkillRoleBinding {
        role = requireText(role, "role").toUpperCase();
        skillId = requireText(skillId, "skillId");
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
