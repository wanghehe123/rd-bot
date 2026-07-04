package com.wish.rd.skill;

/**
 * Skill 使用策略门禁。
 */
public interface SkillPolicyGate {

    /**
     * 判断指定角色是否允许使用 Skill。
     *
     * @param descriptor Skill 描述
     * @param role       Agent 角色
     * @return 策略决策
     */
    SkillPolicyDecision decide(AgentSkillDescriptor descriptor, String role);
}
