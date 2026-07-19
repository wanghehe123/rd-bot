package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.TaskRetryCheckpointRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for durable task retry checkpoints. */
@Mapper
public interface TaskRetryCheckpointMapper extends BaseMapper<TaskRetryCheckpointRow> {

    @Insert("""
            INSERT INTO rd_task_retry_checkpoints (
                id, task_id, failure_phase, retry_from_role, failed_stage_run_id,
                failed_retrieval_run_id, failed_ai_review_run_id, attempt_no, idempotency_key,
                source_task_status, source_task_version, operator_note, evidence_material_ids,
                status, reason, error_message, created_at, updated_at
            ) VALUES (
                #{id}, #{taskId}, #{failurePhase}, #{retryFromRole}, #{failedStageRunId},
                #{failedRetrievalRunId}, #{failedAiReviewRunId}, #{attemptNo}, #{idempotencyKey},
                #{sourceTaskStatus}, #{sourceTaskVersion}, #{operatorNote},
                CAST(#{evidenceMaterialIdsJson} AS JSONB), #{status}, #{reason}, #{errorMessage}, #{createdAt}, #{updatedAt}
            ) ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertIfAbsent(TaskRetryCheckpointRow row);

    @Select("SELECT * FROM rd_task_retry_checkpoints WHERE idempotency_key = #{idempotencyKey} LIMIT 1")
    TaskRetryCheckpointRow selectByIdempotencyKey(String idempotencyKey);

    @Update("""
            UPDATE rd_task_retry_checkpoints
               SET status = #{status}, error_message = #{errorMessage}, updated_at = #{updatedAt}
             WHERE id = #{id} AND status = #{expectedStatus}
            """)
    int transition(TaskRetryCheckpointRow row);
}
