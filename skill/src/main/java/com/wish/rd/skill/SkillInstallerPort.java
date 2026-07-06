package com.wish.rd.skill;

import com.wish.rd.skill.model.AgentSkillDescriptor;
import com.wish.rd.skill.model.SkillInstallCommand;
import com.wish.rd.skill.model.SkillInstallResult;


/**
 * Skill 安装端口。
 *
 * <p>具体安装动作属于外部执行能力，生产实现必须由 bootstrap 适配并受策略门禁控制。
 */
public interface SkillInstallerPort {

    /**
     * 准备或安装 Skill。
     *
     * @param command    安装请求
     * @param descriptor Skill 描述
     * @return 安装结果
     */
    SkillInstallResult install(SkillInstallCommand command, AgentSkillDescriptor descriptor);
}
