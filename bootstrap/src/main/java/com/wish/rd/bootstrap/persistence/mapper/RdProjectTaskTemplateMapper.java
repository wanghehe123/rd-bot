package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.RdProjectTaskTemplateRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** PostgreSQL mapper for project task templates. */
@Mapper
public interface RdProjectTaskTemplateMapper {
    @Insert("""
            INSERT INTO rd_project_task_templates (
              project_id, task_type, name, actual_behavior, expected_behavior,
              reproduction_steps, affected_scope, acceptance_criteria_json,
              requirement_body, expected_result, created_at, updated_at
            ) VALUES (
              #{projectId}, #{taskType}, #{name}, #{actualBehavior}, #{expectedBehavior},
              #{reproductionSteps}, #{affectedScope}, CAST(#{acceptanceCriteriaJson} AS jsonb),
              #{requirementBody}, #{expectedResult}, #{createdAt}, #{updatedAt}
            ) ON CONFLICT (project_id, task_type) DO UPDATE SET
              name=EXCLUDED.name, actual_behavior=EXCLUDED.actual_behavior,
              expected_behavior=EXCLUDED.expected_behavior, reproduction_steps=EXCLUDED.reproduction_steps,
              affected_scope=EXCLUDED.affected_scope, acceptance_criteria_json=EXCLUDED.acceptance_criteria_json,
              requirement_body=EXCLUDED.requirement_body, expected_result=EXCLUDED.expected_result,
              updated_at=EXCLUDED.updated_at
            """)
    int upsert(RdProjectTaskTemplateRow row);

    @Select("""
            SELECT project_id, task_type, name, actual_behavior, expected_behavior,
                   reproduction_steps, affected_scope,
                   acceptance_criteria_json::text AS acceptance_criteria_json,
                   requirement_body, expected_result, created_at, updated_at
            FROM rd_project_task_templates WHERE project_id=#{projectId} AND task_type=#{taskType}
            """)
    RdProjectTaskTemplateRow find(
            @Param("projectId") long projectId,
            @Param("taskType") String taskType
    );
}
