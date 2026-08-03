package com.wish.rd.skill;

import com.wish.rd.skill.model.SkillCatalogEntry;
import com.wish.rd.skill.model.SkillRoleBinding;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill Hub 目录与角色绑定端口。
 *
 * <p>生产实现可落文件系统或 PostgreSQL；Pi 物化与管理面只依赖该端口，
 * 不直接耦合安装门禁（仍由 {@link SkillInstallationEngine} 负责）。
 */
public interface SkillCatalogPort {

    /**
     * 列出全部目录条目。
     *
     * @return 目录条目列表
     */
    List<SkillCatalogEntry> list();

    /**
     * 按 Skill ID 查询目录条目。
     *
     * @param skillId Skill ID
     * @return 目录条目
     */
    Optional<SkillCatalogEntry> findById(String skillId);

    /**
     * 创建或覆盖目录条目。
     *
     * @param entry 目录条目
     * @return 持久化后的条目
     */
    SkillCatalogEntry upsert(SkillCatalogEntry entry);

    /**
     * 列出指定角色的绑定（按 sortOrder 升序）。
     *
     * @param role Agent 角色
     * @return 绑定列表
     */
    List<SkillRoleBinding> listBindings(String role);

    /**
     * 全量替换指定角色的绑定列表。
     *
     * @param role     Agent 角色
     * @param bindings 新绑定列表
     * @return 持久化后的绑定
     */
    List<SkillRoleBinding> replaceBindings(String role, List<SkillRoleBinding> bindings);

    /**
     * 列出全部角色的绑定。
     *
     * @return role → bindings
     */
    Map<String, List<SkillRoleBinding>> listBindingsForAllRoles();
}
