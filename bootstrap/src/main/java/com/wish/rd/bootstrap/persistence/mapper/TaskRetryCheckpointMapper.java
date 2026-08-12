package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.TaskRetryCheckpointRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for durable task retry checkpoints. */
@Mapper
public interface TaskRetryCheckpointMapper extends BaseMapper<TaskRetryCheckpointRow> {

    @Insert("""
            INSERT INTO rd_task_retry_checkpoints (
                id, task_id, failure_phase, retry_from_role, failed_stage_run_id,
                failed_retrieval_run_id, failed_ai_review_run_id, attempt_no, idempotency_key,
                source_task_status, source_task_version, source_fencing_token, failed_stage_command_id,
                failed_stage, source_policy_run_id, policy_run_id, source_plan_digest,
                authorization_plan_digest, publication_operation_id, dispatch_task_version,
                dispatch_fencing_token, dispatch_command_id, business_generation, operator_note, evidence_material_ids,
                status, reason, error_message, created_at, updated_at
            ) VALUES (
                #{id}, #{taskId}, #{failurePhase}, #{retryFromRole}, #{failedStageRunId},
                #{failedRetrievalRunId}, #{failedAiReviewRunId}, #{attemptNo}, #{idempotencyKey},
                #{sourceTaskStatus}, #{sourceTaskVersion}, #{sourceFencingToken}, #{failedStageCommandId},
                #{failedStage}, #{sourcePolicyRunId}, #{policyRunId}, #{sourcePlanDigest},
                #{authorizationPlanDigest}, #{publicationOperationId}, #{dispatchTaskVersion},
                #{dispatchFencingToken}, #{dispatchCommandId}, #{businessGeneration}, #{operatorNote},
                CAST(#{evidenceMaterialIdsJson} AS JSONB), #{status}, #{reason}, #{errorMessage}, #{createdAt}, #{updatedAt}
            ) ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertIfAbsent(TaskRetryCheckpointRow row);

    @Select("SELECT * FROM rd_task_retry_checkpoints WHERE idempotency_key = #{idempotencyKey} LIMIT 1")
    TaskRetryCheckpointRow selectByIdempotencyKey(String idempotencyKey);

    /**
     * Locks one checkpoint row for the exhaustion / settlement transaction.
     *
     * @param id durable checkpoint identity
     * @return locked row when present
     */
    @Select("SELECT * FROM rd_task_retry_checkpoints WHERE id = #{id} FOR UPDATE")
    TaskRetryCheckpointRow lockByIdForUpdate(@Param("id") long id);

    @Update("""
            UPDATE rd_task_retry_checkpoints
               SET status = #{status}, error_message = #{errorMessage}, updated_at = #{updatedAt}
             WHERE id = #{id} AND status = #{expectedStatus}
            """)
    int transition(TaskRetryCheckpointRow row);

    /** Binds the first command exactly once while making the checkpoint externally dispatchable. */
    @Update("""
            UPDATE rd_task_retry_checkpoints
               SET status = 'DISPATCHED', dispatch_task_version = #{dispatchTaskVersion},
                   dispatch_fencing_token = #{dispatchFencingToken}, dispatch_command_id = #{dispatchCommandId},
                   error_message = '', updated_at = #{updatedAt}
             WHERE id = #{id} AND status = 'CREATED'
               AND dispatch_task_version = 0 AND dispatch_fencing_token = 0 AND dispatch_command_id IS NULL
            """)
    int dispatch(TaskRetryCheckpointRow row);

    /** Binds the only permitted future authorization identity exactly once. */
    @Update("""
            UPDATE rd_task_retry_checkpoints
               SET policy_run_id = #{policyRunId}, authorization_plan_digest = #{authorizationPlanDigest},
                   updated_at = #{updatedAt}
             WHERE id = #{id} AND policy_run_id IS NULL
               AND COALESCE(authorization_plan_digest, '') = ''
            """)
    int bindAuthorizationIfBlank(TaskRetryCheckpointRow row);
}
