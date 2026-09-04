package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.ProjectMemoryReconciliationEvidenceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectMemoryReconciliationEvidenceMapper {
    @Select("""
            SELECT f.command_id AS commandId,
                   c.task_id AS taskId,
                   t.project_id AS projectId,
                   f.outcome_plan_digest AS outcomePlanDigest,
                   f.finalized_at AS finalizedAt
              FROM rd_requirement_stage_finalizations f
              JOIN rd_requirement_stage_commands c ON c.id = f.command_id
              JOIN rd_tasks t ON t.id = c.task_id
             WHERE f.state = 'FINALIZED'
               AND f.finalized_at IS NOT NULL
               AND NULLIF(BTRIM(f.outcome_plan_digest), '') IS NOT NULL
               AND NULLIF(BTRIM(t.project_id), '') IS NOT NULL
             ORDER BY f.finalized_at ASC, f.command_id ASC
             LIMIT #{limit}
            """)
    List<ProjectMemoryReconciliationEvidenceRow> listRecentFinalizedTerminalEvidence(@Param("limit") int limit);
}
