package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.TaskFailureProvenanceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Mapper for exact, transaction-owned task failure provenance. */
@Mapper
public interface TaskFailureProvenanceMapper extends BaseMapper<TaskFailureProvenanceRow> {

    @Insert("""
            INSERT INTO rd_task_failure_provenance
                (id, task_id, failed_stage_command_id, failed_command_attempt_no, failed_stage,
                 failure_phase, outcome_status, failed_task_version, failed_task_fencing_token,
                 failed_stage_run_id, failed_retrieval_run_id, failed_ai_review_run_id, source_policy_run_id,
                 source_plan_digest, publication_operation_id, failure_kind, recorded_at,
                 failed_verification_run_id)
            VALUES (#{id}, #{taskId}, #{failedStageCommandId}, #{failedCommandAttemptNo}, #{failedStage},
                    #{failurePhase}, #{outcomeStatus}, #{failedTaskVersion}, #{failedTaskFencingToken},
                    #{failedStageRunId}, #{failedRetrievalRunId}, #{failedAiReviewRunId}, #{sourcePolicyRunId},
                    #{sourcePlanDigest}, #{publicationOperationId}, #{failureKind}, #{recordedAt},
                    #{failedVerificationRunId})
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(TaskFailureProvenanceRow row);

    @Select("""
            SELECT *
              FROM rd_task_failure_provenance
             WHERE task_id = #{taskId}
               AND outcome_status = #{outcomeStatus}
               AND failed_task_version = #{failedTaskVersion}
               AND failed_task_fencing_token = #{failedTaskFencingToken}
             ORDER BY recorded_at DESC, id DESC
             LIMIT 1
            """)
    TaskFailureProvenanceRow findExact(
            @Param("taskId") Long taskId,
            @Param("outcomeStatus") String outcomeStatus,
            @Param("failedTaskVersion") long failedTaskVersion,
            @Param("failedTaskFencingToken") long failedTaskFencingToken
    );

    /**
     * Locates provenance by the exhausted command identity for idempotent exhaustion resume.
     *
     * @param failedStageCommandId terminalized stage command id
     * @param failedCommandAttemptNo technical attempt number on that command
     * @return existing row when present
     */
    @Select("""
            SELECT *
              FROM rd_task_failure_provenance
             WHERE failed_stage_command_id = #{failedStageCommandId}
               AND failed_command_attempt_no = #{failedCommandAttemptNo}
             ORDER BY recorded_at DESC, id DESC
             LIMIT 1
            """)
    TaskFailureProvenanceRow findByCommandAttempt(
            @Param("failedStageCommandId") Long failedStageCommandId,
            @Param("failedCommandAttemptNo") int failedCommandAttemptNo
    );
}
