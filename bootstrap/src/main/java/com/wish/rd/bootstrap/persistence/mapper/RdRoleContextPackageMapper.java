package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdRoleContextPackageRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色上下文包 MyBatis-Plus mapper。
 */
@Mapper
public interface RdRoleContextPackageMapper extends BaseMapper<RdRoleContextPackageRow> {

    /**
     * 插入或更新角色上下文包。
     *
     * @param row 角色上下文包行
     */
    @Insert("""
            INSERT INTO rd_role_context_packages (
                id, task_id, role, package_version, evidence_json, acceptance_json,
                risk_hints_json, context_budget_json, omitted_evidence_json, content_hash, created_at
            )
            VALUES (
                #{id}, #{taskId}, #{role}, #{packageVersion}, #{evidenceJson}::jsonb,
                #{acceptanceJson}::jsonb, #{riskHintsJson}::jsonb, #{contextBudgetJson}::jsonb,
                #{omittedEvidenceJson}::jsonb, #{contentHash}, #{createdAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                task_id = EXCLUDED.task_id,
                role = EXCLUDED.role,
                package_version = EXCLUDED.package_version,
                evidence_json = EXCLUDED.evidence_json,
                acceptance_json = EXCLUDED.acceptance_json,
                risk_hints_json = EXCLUDED.risk_hints_json,
                context_budget_json = EXCLUDED.context_budget_json,
                omitted_evidence_json = EXCLUDED.omitted_evidence_json,
                content_hash = EXCLUDED.content_hash
            """)
    void upsertContextPackage(RdRoleContextPackageRow row);
}
