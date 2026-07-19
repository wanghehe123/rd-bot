package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.RdProjectTokenBudgetRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** PostgreSQL mapper for project default token budgets. */
@Mapper
public interface RdProjectTokenBudgetMapper {

    @Insert("""
            INSERT INTO rd_project_token_budgets (project_id, default_token_budget, created_at, updated_at)
            VALUES (#{projectId}, #{defaultTokenBudget}, #{createdAt}, #{updatedAt})
            ON CONFLICT (project_id) DO UPDATE SET
                default_token_budget = EXCLUDED.default_token_budget,
                updated_at = EXCLUDED.updated_at
            """)
    int upsert(RdProjectTokenBudgetRow row);

    @Select("""
            SELECT project_id, default_token_budget, created_at, updated_at
            FROM rd_project_token_budgets WHERE project_id = #{projectId}
            """)
    RdProjectTokenBudgetRow findByProjectId(long projectId);
}
