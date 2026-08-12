package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.ProviderToolOperationRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** MyBatis mapper for Host-owned provider tool-operation evidence. */
@Mapper
public interface ProviderToolOperationMapper extends BaseMapper<ProviderToolOperationRow> {

    /**
     * Inserts or advances one operation observation without allowing a stale writer to overwrite
     * a newer durable result.
     *
     * @param row immutable operation row projection
     * @return affected row count
     */
    @Insert("""
            INSERT INTO rd_provider_tool_operations (
                operation_id, task_id, stage_run_id, attempt_id, status, reason, created_at, updated_at
            ) VALUES (
                #{operationId}, #{taskId}, #{stageRunId}, #{attemptId}, #{status}, #{reason},
                #{createdAt}, #{updatedAt}
            ) ON CONFLICT (operation_id) DO UPDATE SET
                status = EXCLUDED.status,
                reason = EXCLUDED.reason,
                updated_at = EXCLUDED.updated_at
            WHERE rd_provider_tool_operations.task_id = EXCLUDED.task_id
              AND rd_provider_tool_operations.stage_run_id = EXCLUDED.stage_run_id
              AND rd_provider_tool_operations.attempt_id = EXCLUDED.attempt_id
              AND rd_provider_tool_operations.updated_at <= EXCLUDED.updated_at
            """)
    int record(ProviderToolOperationRow row);

    /**
     * Reads newest durable operation evidence for a task-stage boundary.
     *
     * @param taskId workflow task id
     * @param stageRunId stage-run id
     * @return newest matching row or null
     */
    @Select("""
            SELECT *
              FROM rd_provider_tool_operations
             WHERE task_id = #{taskId}
               AND stage_run_id = #{stageRunId}
             ORDER BY updated_at DESC, operation_id DESC
             LIMIT 1
            """)
    ProviderToolOperationRow findLatestByTaskAndStageRun(
            @Param("taskId") Long taskId,
            @Param("stageRunId") String stageRunId
    );

    /**
     * Reads a durable operation by its immutable id.
     *
     * @param operationId operation id
     * @return matching row or null
     */
    @Select("SELECT * FROM rd_provider_tool_operations WHERE operation_id = #{operationId}")
    ProviderToolOperationRow findByOperationId(@Param("operationId") String operationId);
}
