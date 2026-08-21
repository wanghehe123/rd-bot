package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageEventRow;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageRunRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Agent 阶段运行 MyBatis-Plus mapper。
 */
@Mapper
public interface RdAgentStageRunMapper extends BaseMapper<RdAgentStageRunRow> {

    @Insert("""
            INSERT INTO rd_agent_stage_runs (
                id, task_id, role, status, attempt_no, idempotency_key,
                provider_name, provider_attempts_json, review_result_json,
                error_category, error_message, created_at, updated_at
            ) VALUES (
                #{id}, #{taskId}, #{role}, #{status}, #{attemptNo}, #{idempotencyKey},
                #{providerName}, CAST(#{providerAttemptsJson} AS jsonb), CAST(#{reviewResultJson} AS jsonb),
                #{errorCategory}, #{errorMessage}, #{createdAt}, #{updatedAt}
            ) ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(RdAgentStageRunRow row);

    /**
     * 插入或更新阶段运行。
     *
     * @param row 阶段运行行
     */
    @Insert("""
            INSERT INTO rd_agent_stage_runs (
                id, task_id, role, status, attempt_no, idempotency_key,
                provider_name, provider_attempts_json, context_package_id,
                prompt_artifact_id, result_artifact_id, review_result_json,
                error_category, error_message, started_at, finished_at, created_at, updated_at
            )
            VALUES (
                #{id}, #{taskId}, #{role}, #{status}, #{attemptNo}, #{idempotencyKey},
                #{providerName}, #{providerAttemptsJson}::jsonb, #{contextPackageId},
                #{promptArtifactId}, #{resultArtifactId}, #{reviewResultJson}::jsonb,
                #{errorCategory}, #{errorMessage}, #{startedAt}, #{finishedAt}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                task_id = EXCLUDED.task_id,
                role = EXCLUDED.role,
                status = EXCLUDED.status,
                attempt_no = EXCLUDED.attempt_no,
                idempotency_key = EXCLUDED.idempotency_key,
                provider_name = EXCLUDED.provider_name,
                provider_attempts_json = EXCLUDED.provider_attempts_json,
                context_package_id = EXCLUDED.context_package_id,
                prompt_artifact_id = EXCLUDED.prompt_artifact_id,
                result_artifact_id = EXCLUDED.result_artifact_id,
                review_result_json = EXCLUDED.review_result_json,
                error_category = EXCLUDED.error_category,
                error_message = EXCLUDED.error_message,
                started_at = EXCLUDED.started_at,
                finished_at = EXCLUDED.finished_at,
                updated_at = EXCLUDED.updated_at
            """)
    void upsertStageRun(RdAgentStageRunRow row);

    /**
     * Advances a stage snapshot only when the persisted source status still matches.
     *
     * @param row next stage snapshot
     * @param expectedStatus source status observed by the caller
     * @return one when updated, zero when another writer already advanced the stage
     */
    @Update("""
            UPDATE rd_agent_stage_runs
            SET status = #{row.status},
                provider_name = #{row.providerName},
                provider_attempts_json = #{row.providerAttemptsJson}::jsonb,
                context_package_id = #{row.contextPackageId},
                prompt_artifact_id = #{row.promptArtifactId},
                result_artifact_id = #{row.resultArtifactId},
                review_result_json = #{row.reviewResultJson}::jsonb,
                error_category = #{row.errorCategory},
                error_message = #{row.errorMessage},
                started_at = #{row.startedAt},
                finished_at = #{row.finishedAt},
                updated_at = #{row.updatedAt}
            WHERE id = #{row.id}
              AND status = #{expectedStatus}
            """)
    int updateStageRunWhenStatusMatches(
            @Param("row") RdAgentStageRunRow row,
            @Param("expectedStatus") String expectedStatus
    );

    /**
     * 插入阶段状态事件。
     *
     * @param row 阶段状态事件行
     */
    @Insert("""
            INSERT INTO rd_agent_stage_events (
                id, stage_run_id, task_id, role, status, message,
                metadata_json, entered_at, duration_ms, trigger
            )
            VALUES (
                #{id}, #{stageRunId}, #{taskId}, #{role}, #{status}, #{message},
                #{metadataJson}::jsonb, #{enteredAt}, #{durationMs}, #{trigger}
            )
            """)
    void insertStageEvent(RdAgentStageEventRow row);
}
