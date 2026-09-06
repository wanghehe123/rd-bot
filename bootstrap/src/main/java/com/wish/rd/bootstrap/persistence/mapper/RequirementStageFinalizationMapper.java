package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageFinalizationRow;
import com.wish.rd.bootstrap.persistence.entity.TaskFailureProvenanceRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

/** MyBatis mapper for the stage task-mutation to command-finalization recovery ledger. */
@Mapper
public interface RequirementStageFinalizationMapper extends BaseMapper<RequirementStageFinalizationRow> {

    /**
     * Corroborates a failure-provenance row against the exact durable command attempt and every
     * optional ledger identity carried by that row. Generic SUCCEEDED commands require a FINALIZED
     * marker on the exact attempt; DEAD_LETTERED technical failures accept a PREPARED /
     * OUTCOME_RECORDED marker, or a FINALIZED marker whose outcome matches, from the exhausted
     * attempt or an earlier reclaim (dispatch reuses markers via command identity, and last-attempt
     * publication finalize stamps that reused row FINALIZED). Checkpoint-bound commands may corroborate policy through
     * {@code checkpoint.source_policy_run_id} when {@code command.policy_run_id} is still blank.
     * Policy-control commands are owned by the policy transaction and have no generic marker.
     */
    @Select("""
            SELECT COUNT(*)
              FROM rd_requirement_stage_commands command
             WHERE command.id = #{row.failedStageCommandId}
               AND command.task_id = #{row.taskId}
               AND command.attempt_no = #{row.failedCommandAttemptNo}
               AND (
                    command.stage = #{row.failedStage}
                    OR (
                        #{row.failurePhase} = 'PR_PUBLICATION'
                        AND NULLIF(BTRIM(#{row.publicationOperationId}), '') IS NOT NULL
                        AND (
                            command.stage = 'PUBLICATION'
                            OR command.stage = 'PUBLICATION:' || #{row.publicationOperationId}
                        )
                    )
                    OR (
                        #{row.failurePhase} = 'HOST_VERIFY'
                        AND (
                            command.stage = #{row.failedStage}
                            OR command.stage = 'ROLE_EXECUTION:CODING_AGENT'
                            OR command.stage = 'HOST_VERIFY'
                        )
                    )
               )
               AND (
                    (
                        (command.stage IN ('POLICY_EVALUATE', 'POLICY_APPLY', 'APPROVAL_RESUME')
                         OR command.stage LIKE 'USER_ANSWER_RESUME:%'
                         OR command.stage LIKE 'MANAGER_DECIDE:%')
                        AND command.status IN ('SUCCEEDED', 'DEAD_LETTERED')
                    )
                    OR EXISTS (
                        SELECT 1
                          FROM rd_requirement_stage_finalizations marker
                         WHERE marker.command_id = command.id
                           AND marker.task_id = command.task_id
                           AND marker.stage = command.stage
                           AND (
                                (marker.state = 'FINALIZED'
                                 AND marker.attempt_no = #{row.failedCommandAttemptNo}
                                 AND command.status = 'SUCCEEDED'
                                 AND marker.outcome_status = #{row.outcomeStatus})
                                OR (marker.state = 'FINALIZED'
                                    AND command.status = 'DEAD_LETTERED'
                                    AND marker.attempt_no <= #{row.failedCommandAttemptNo}
                                    AND marker.outcome_status = #{row.outcomeStatus})
                                OR (marker.state IN ('PREPARED', 'OUTCOME_RECORDED')
                                    AND command.status = 'DEAD_LETTERED'
                                    AND marker.attempt_no <= #{row.failedCommandAttemptNo})
                           )
                    )
               )
               AND (
                    (#{row.failurePhase} = 'MATERIAL'
                     AND command.stage IN ('MATERIAL_COLLECTING', 'MATERIAL_READY'))
                    OR (#{row.failurePhase} = 'CONTEXT'
                        AND command.stage IN ('CONTEXT_BUILDING', 'CONTEXT_READY'))
                    OR (#{row.failurePhase} = 'PLAN'
                        AND command.stage IN ('PLAN_GENERATING', 'PLAN_GENERATED'))
                    OR (#{row.failurePhase} = 'POLICY'
                        AND command.stage IN ('POLICY_EVALUATE', 'POLICY_APPLY', 'APPROVAL_RESUME'))
                    OR (#{row.failurePhase} = 'MANAGER'
                        AND (command.stage LIKE 'MANAGER_DECIDE:%'
                             OR command.stage LIKE 'USER_ANSWER_RESUME:%'))
                    OR (#{row.failurePhase} = 'RAG'
                        AND #{row.failedRetrievalRunId,jdbcType=BIGINT} IS NOT NULL
                        AND EXISTS (
                            SELECT 1
                              FROM rd_rag_retrieval_runs route_retrieval
                             WHERE route_retrieval.id = #{row.failedRetrievalRunId}
                               AND route_retrieval.task_id = command.task_id
                               AND command.role = route_retrieval.role
                               AND command.stage = 'ROLE_EXECUTION:' || route_retrieval.role
                        ))
                    OR (#{row.failurePhase} = 'AGENT_ROLE'
                        AND #{row.failedStageRunId,jdbcType=BIGINT} IS NOT NULL
                        AND EXISTS (
                            SELECT 1
                              FROM rd_agent_stage_runs route_stage
                             WHERE route_stage.id = #{row.failedStageRunId}
                               AND route_stage.task_id = command.task_id
                               AND command.role = route_stage.role
                               AND command.stage = 'ROLE_EXECUTION:' || route_stage.role
                        ))
                    OR (#{row.failurePhase} = 'DETERMINISTIC_REVIEW'
                        AND command.stage = 'DETERMINISTIC_REVIEW')
                    OR (#{row.failurePhase} = 'AI_REVIEW'
                        AND command.stage = 'AI_REVIEW')
                    OR (#{row.failurePhase} = 'PR_PUBLICATION'
                        AND NULLIF(BTRIM(#{row.publicationOperationId}), '') IS NOT NULL
                        AND (
                            command.stage = 'PUBLICATION'
                            OR command.stage = 'PUBLICATION:' || #{row.publicationOperationId}
                        )
                        AND EXISTS (
                            SELECT 1
                              FROM rd_requirement_publications publication
                             WHERE publication.operation_id = #{row.publicationOperationId}
                               AND publication.task_id = command.task_id
                        ))
                    OR (#{row.failurePhase} = 'HOST_VERIFY'
                        AND #{row.failedVerificationRunId,jdbcType=BIGINT} IS NOT NULL
                        AND EXISTS (
                            SELECT 1
                              FROM rd_host_verification_runs verify
                             WHERE verify.id = #{row.failedVerificationRunId}
                               AND verify.task_id = command.task_id
                        ))
               )
               AND (
                    (#{row.failurePhase} IN ('MATERIAL', 'CONTEXT', 'PLAN')
                     AND #{row.sourcePolicyRunId,jdbcType=BIGINT} IS NULL
                     AND command.policy_run_id IS NULL)
                    OR (#{row.failurePhase} NOT IN ('MATERIAL', 'CONTEXT', 'PLAN')
                        AND #{row.sourcePolicyRunId,jdbcType=BIGINT} IS NOT NULL
                        AND (
                             command.policy_run_id = #{row.sourcePolicyRunId}
                             OR (
                                 command.policy_run_id IS NULL
                                 AND command.retry_checkpoint_id IS NOT NULL
                                 AND EXISTS (
                                     SELECT 1
                                       FROM rd_task_retry_checkpoints checkpoint
                                      WHERE checkpoint.id = command.retry_checkpoint_id
                                        AND checkpoint.task_id = command.task_id
                                        AND checkpoint.source_policy_run_id = #{row.sourcePolicyRunId}
                                 )
                             )
                        ))
               )
               AND (
                    #{row.failedStageRunId,jdbcType=BIGINT} IS NULL
                    OR EXISTS (
                        SELECT 1
                          FROM rd_agent_stage_runs stage_run
                         WHERE stage_run.id = #{row.failedStageRunId}
                           AND stage_run.task_id = command.task_id
                           AND (#{row.failurePhase} <> 'AGENT_ROLE'
                                OR command.stage = 'ROLE_EXECUTION:' || stage_run.role)
                    )
               )
               AND (
                    #{row.failedRetrievalRunId,jdbcType=BIGINT} IS NULL
                    OR EXISTS (
                        SELECT 1
                          FROM rd_rag_retrieval_runs retrieval_run
                         WHERE retrieval_run.id = #{row.failedRetrievalRunId}
                           AND retrieval_run.task_id = command.task_id
                           AND (#{row.failedStageRunId,jdbcType=BIGINT} IS NULL
                                OR retrieval_run.stage_run_id = #{row.failedStageRunId})
                    )
               )
               AND (
                    #{row.failedAiReviewRunId,jdbcType=BIGINT} IS NULL
                    OR EXISTS (
                        SELECT 1
                          FROM rd_ai_review_runs review_run
                         WHERE review_run.id = #{row.failedAiReviewRunId}
                           AND review_run.task_id = command.task_id
                    )
               )
               AND (
                    #{row.failedVerificationRunId,jdbcType=BIGINT} IS NULL
                    OR EXISTS (
                        SELECT 1
                          FROM rd_host_verification_runs verify_run
                         WHERE verify_run.id = #{row.failedVerificationRunId}
                           AND verify_run.task_id = command.task_id
                    )
               )
               AND (
                    #{row.sourcePolicyRunId,jdbcType=BIGINT} IS NULL
                    OR EXISTS (
                        SELECT 1
                          FROM rd_requirement_policy_runs policy_run
                         WHERE policy_run.id = #{row.sourcePolicyRunId}
                           AND policy_run.task_id = command.task_id
                           AND policy_run.plan_digest = #{row.sourcePlanDigest}
                    )
               )
               AND (
                    NULLIF(BTRIM(#{row.publicationOperationId}), '') IS NULL
                    OR EXISTS (
                        SELECT 1
                          FROM rd_requirement_publications publication
                         WHERE publication.operation_id = #{row.publicationOperationId}
                           AND publication.task_id = command.task_id
                    )
               )
            """)
    int countCorroboratedFailureProvenance(@Param("row") TaskFailureProvenanceRow row);

    /**
     * Inserts a pre-execution marker only while the exact stage command attempt remains leased by
     * the caller.
     *
     * @param commandId command id
     * @param attemptNo claimed attempt number
     * @param leaseOwner current lease owner
     * @param preparedAt Host timestamp
     * @return inserted row count
     */
    @Insert("""
            INSERT INTO rd_requirement_stage_finalizations (
                command_id, attempt_no, task_id, expected_task_version, expected_fencing_token, expected_task_status,
                stage, state, outcome_status, result_json, error_message, outcome_plan_json, outcome_plan_digest, next_command_id,
                prepared_at, finalized_at
            )
            SELECT command.id, command.attempt_no, command.task_id, command.task_version,
                   command.fencing_token, #{expectedTaskStatus}, command.stage, 'PREPARED', '', '{}'::jsonb, '', NULL, '', NULL,
                   #{preparedAt}, NULL
              FROM rd_requirement_stage_commands command
             WHERE command.id = #{commandId}
               AND command.attempt_no = #{attemptNo}
               AND command.status = 'RUNNING'
               AND command.lease_owner = #{leaseOwner}
               AND command.lease_until > #{preparedAt}
            ON CONFLICT (command_id, attempt_no) DO NOTHING
            """)
    int insertPreparedIfOwned(
            @Param("commandId") Long commandId,
            @Param("attemptNo") int attemptNo,
            @Param("leaseOwner") String leaseOwner,
            @Param("expectedTaskStatus") String expectedTaskStatus,
            @Param("preparedAt") OffsetDateTime preparedAt
    );

    /**
     * Ensures a recoverable PREPARED marker exists for a technically exhausted DEAD_LETTERED
     * command attempt. Paths that never reached {@code prepare} (expired lease, rejected queue,
     * fail-before-plan) otherwise leave provenance uncorroboratable.
     *
     * @param commandId exhausted command id
     * @param attemptNo exhausted attempt number
     * @param expectedTaskStatus task status expected when the attempt was leased
     * @param preparedAt Host timestamp
     * @return inserted row count ({@code 0} when a marker already exists)
     */
    @Insert("""
            INSERT INTO rd_requirement_stage_finalizations (
                command_id, attempt_no, task_id, expected_task_version, expected_fencing_token, expected_task_status,
                stage, state, outcome_status, result_json, error_message, outcome_plan_json, outcome_plan_digest, next_command_id,
                prepared_at, finalized_at
            )
            SELECT command.id, command.attempt_no, command.task_id, command.task_version,
                   command.fencing_token, #{expectedTaskStatus}, command.stage, 'PREPARED', '', '{}'::jsonb, '', NULL, '', NULL,
                   #{preparedAt}, NULL
              FROM rd_requirement_stage_commands command
             WHERE command.id = #{commandId}
               AND command.attempt_no = #{attemptNo}
               AND command.status = 'DEAD_LETTERED'
            ON CONFLICT (command_id, attempt_no) DO NOTHING
            """)
    int insertPreparedForExhaustedAttempt(
            @Param("commandId") Long commandId,
            @Param("attemptNo") int attemptNo,
            @Param("expectedTaskStatus") String expectedTaskStatus,
            @Param("preparedAt") OffsetDateTime preparedAt
    );

    /**
     * Locks one marker while its command and continuation are finalized.
     *
     * @param commandId command id
     * @param attemptNo original marker attempt number
     * @return locked marker row or null
     */
    @Select("""
            SELECT *
              FROM rd_requirement_stage_finalizations
             WHERE command_id = #{commandId}
               AND attempt_no = #{attemptNo}
             FOR UPDATE
            """)
    RequirementStageFinalizationRow findForUpdate(
            @Param("commandId") Long commandId,
            @Param("attemptNo") int attemptNo
    );

    /**
     * Reads the newest unfinished marker for one command identity.
     *
     * @param commandId command id
     * @return latest prepared marker or null
     */
    @Select("""
            SELECT *
              FROM rd_requirement_stage_finalizations
             WHERE command_id = #{commandId}
              AND state IN ('PREPARED', 'OUTCOME_RECORDED')
             ORDER BY prepared_at DESC, attempt_no DESC
             LIMIT 1
            """)
    RequirementStageFinalizationRow findLatestPrepared(@Param("commandId") Long commandId);

    /**
     * Returns already-frozen outcome plans for one task so a later {@code recordOutcome} can assign
     * a unique remediation number before the ledger row exists.
     *
     * @param taskId owning task
     * @return recorded or finalized markers that carry an outcome plan
     */
    @Select("""
            SELECT *
              FROM rd_requirement_stage_finalizations
             WHERE task_id = #{taskId}
               AND state IN ('OUTCOME_RECORDED', 'FINALIZED')
               AND outcome_plan_json IS NOT NULL
               AND CAST(outcome_plan_json AS text) NOT IN ('', 'null')
             ORDER BY command_id ASC, attempt_no ASC
            """)
    java.util.List<RequirementStageFinalizationRow> listRecordedOutcomePlans(@Param("taskId") long taskId);

    /** Persists an immutable plan before task mutation can begin. */
    @Update("""
            UPDATE rd_requirement_stage_finalizations
               SET state = 'OUTCOME_RECORDED', outcome_status = #{outcomeStatus},
                   outcome_plan_json = CAST(#{outcomePlanJson} AS jsonb),
                   outcome_plan_digest = #{outcomePlanDigest}
             WHERE command_id = #{commandId} AND attempt_no = #{attemptNo} AND state = 'PREPARED'
            """)
    int recordOutcomePrepared(RequirementStageFinalizationRow row);

    /**
     * Closes a marker after command, job, and continuation writes have succeeded.
     *
     * @param row finalized marker projection
     * @return affected row count
     */
    @Update("""
            UPDATE rd_requirement_stage_finalizations
               SET state = 'FINALIZED',
                   outcome_status = #{outcomeStatus},
                   result_json = CAST(#{resultJson} AS jsonb),
                   error_message = #{errorMessage},
                   outcome_plan_json = CAST(#{outcomePlanJson} AS jsonb),
                   outcome_plan_digest = #{outcomePlanDigest},
                   next_command_id = #{nextCommandId},
                   finalized_at = #{finalizedAt}
             WHERE command_id = #{commandId}
               AND attempt_no = #{attemptNo}
               AND state = 'OUTCOME_RECORDED'
            """)
    int finalizePrepared(RequirementStageFinalizationRow row);
}
