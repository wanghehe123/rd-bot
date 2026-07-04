package com.wish.rd.skill;

import java.util.List;
import java.util.Optional;

/**
 * Skill 注册表端口。
 *
 * <p>生产实现可读取数据库、配置中心或受控插件目录；核心编排只依赖该端口。
 */
public interface SkillRegistryPort {

    /**
     * 查询全部可用 Skill。
     *
     * @return Skill 描述列表
     */
    List<AgentSkillDescriptor> listAvailable();

    /**
     * 按 Skill ID 查询。
     *
     * @param skillId Skill ID
     * @return Skill 描述
     */
    Optional<AgentSkillDescriptor> findById(String skillId);
}
