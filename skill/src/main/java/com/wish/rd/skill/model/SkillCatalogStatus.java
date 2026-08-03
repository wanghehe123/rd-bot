package com.wish.rd.skill.model;

/**
 * Skill Hub 目录条目生命周期状态。
 *
 * <p>仅 {@link #ACTIVE} 且已绑定角色的 Skill 会进入 Pi 物化路径。
 */
public enum SkillCatalogStatus {
    ACTIVE,
    WAITING_APPROVAL,
    DISABLED,
    REJECTED
}
