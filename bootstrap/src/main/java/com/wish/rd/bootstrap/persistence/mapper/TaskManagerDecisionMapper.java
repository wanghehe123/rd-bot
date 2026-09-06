package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.TaskManagerDecisionRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** MyBatis mapper for Host Manager decisions. */
@Mapper
public interface TaskManagerDecisionMapper {

    @Insert("""
            INSERT INTO rd_task_manager_decisions (
                task_id, round_no, source_command_id, state_version, state_hash, route,
                target_record_ids, bounded_contract, executor_route, rationale, decision_hash, created_at
            ) VALUES (
                #{taskId}, #{roundNo}, #{sourceCommandId}, #{stateVersion}, #{stateHash}, #{route},
                CAST(#{targetRecordIdsJson} AS jsonb), #{boundedContract}, #{executorRoute}, #{rationale},
                #{decisionHash}, #{createdAt}
            )
            ON CONFLICT (task_id, source_command_id) DO NOTHING
            """)
    int insertIfAbsent(TaskManagerDecisionRow row);

    @Select("""
            SELECT id,
                   task_id AS taskId,
                   round_no AS roundNo,
                   source_command_id AS sourceCommandId,
                   state_version AS stateVersion,
                   state_hash AS stateHash,
                   route,
                   target_record_ids::text AS targetRecordIdsJson,
                   bounded_contract AS boundedContract,
                   executor_route AS executorRoute,
                   rationale,
                   decision_hash AS decisionHash,
                   created_at AS createdAt
              FROM rd_task_manager_decisions
             WHERE task_id = #{taskId}
             ORDER BY round_no DESC
             LIMIT 1
            """)
    TaskManagerDecisionRow findLatest(@Param("taskId") long taskId);

    @Select("""
            SELECT id,
                   task_id AS taskId,
                   round_no AS roundNo,
                   source_command_id AS sourceCommandId,
                   state_version AS stateVersion,
                   state_hash AS stateHash,
                   route,
                   target_record_ids::text AS targetRecordIdsJson,
                   bounded_contract AS boundedContract,
                   executor_route AS executorRoute,
                   rationale,
                   decision_hash AS decisionHash,
                   created_at AS createdAt
              FROM rd_task_manager_decisions
             WHERE task_id = #{taskId}
               AND source_command_id = #{sourceCommandId}
            """)
    TaskManagerDecisionRow findBySourceCommand(
            @Param("taskId") long taskId,
            @Param("sourceCommandId") long sourceCommandId
    );

    @Select("""
            SELECT id,
                   task_id AS taskId,
                   round_no AS roundNo,
                   source_command_id AS sourceCommandId,
                   state_version AS stateVersion,
                   state_hash AS stateHash,
                   route,
                   target_record_ids::text AS targetRecordIdsJson,
                   bounded_contract AS boundedContract,
                   executor_route AS executorRoute,
                   rationale,
                   decision_hash AS decisionHash,
                   created_at AS createdAt
              FROM rd_task_manager_decisions
             WHERE task_id = #{taskId}
             ORDER BY round_no
            """)
    List<TaskManagerDecisionRow> listByTask(@Param("taskId") long taskId);

    @Select("""
            SELECT COALESCE(MAX(round_no), 0)
              FROM rd_task_manager_decisions
             WHERE task_id = #{taskId}
            """)
    int maxRound(@Param("taskId") long taskId);
}
