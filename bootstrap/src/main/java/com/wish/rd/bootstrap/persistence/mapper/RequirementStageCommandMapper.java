package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageCommandRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/** MyBatis mapper for bounded stage command claims. */
@Mapper
public interface RequirementStageCommandMapper extends BaseMapper<RequirementStageCommandRow> {

    @Insert("""
            INSERT INTO rd_requirement_stage_commands
                (id, task_id, task_version, fencing_token, role, stage, policy_run_id, retry_checkpoint_id,
                 business_generation, target_retry_binding_id, attempt_no, max_attempts,
                 remediation_round_id, remediation_kind, remediation_no, remediation_source_stage_run_id,
                 remediation_request_json, remediation_request_hash,
                 deadline_at, resource_class, resource_requirements, project_id, provider_id, priority_rank, status,
                 lease_owner, lease_until, next_visible_at, last_error, created_at, updated_at)
            VALUES
                (#{id}, #{taskId}, #{taskVersion}, #{fencingToken}, #{role}, #{stage}, #{policyRunId}, #{retryCheckpointId},
                 #{businessGeneration}, #{targetRetryBindingId}, #{attemptNo}, #{maxAttempts},
                 #{remediationRoundId}, #{remediationKind}, #{remediationNo}, #{remediationSourceStageRunId},
                 CAST(#{remediationRequestJson} AS jsonb), #{remediationRequestHash},
                 #{deadlineAt}, #{resourceClass}, #{resourceRequirements}, #{projectId}, #{providerId}, #{priorityRank}, #{status},
                 #{leaseOwner}, #{leaseUntil}, #{nextVisibleAt}, #{lastError}, #{createdAt}, #{updatedAt})
            ON CONFLICT DO NOTHING
            """)
    int enqueue(RequirementStageCommandRow row);

    @Select("""
            SELECT *
             FROM rd_requirement_stage_commands
             WHERE task_id = #{taskId} AND role = #{role} AND stage = #{stage}
               AND retry_checkpoint_id IS NULL AND remediation_round_id IS NULL
             ORDER BY id
             LIMIT 1
            """)
    RequirementStageCommandRow find(
            @Param("taskId") long taskId,
            @Param("role") String role,
            @Param("stage") String stage
    );

    @Select("""
            SELECT * FROM rd_requirement_stage_commands
             WHERE task_id = #{taskId} AND role = #{role} AND stage = #{stage}
               AND remediation_round_id IS NULL
               AND ((#{retryCheckpointId} = '' AND retry_checkpoint_id IS NULL)
                    OR retry_checkpoint_id = CAST(NULLIF(#{retryCheckpointId}, '') AS BIGINT))
             ORDER BY id LIMIT 1
            """)
    RequirementStageCommandRow findByIdentity(
            @Param("taskId") long taskId, @Param("role") String role, @Param("stage") String stage,
            @Param("retryCheckpointId") String retryCheckpointId);

    @Select("""
            SELECT * FROM rd_requirement_stage_commands
             WHERE task_id = #{taskId} AND role = #{role} AND stage = #{stage}
               AND retry_checkpoint_id IS NULL AND remediation_round_id = #{remediationRoundId}
             ORDER BY id LIMIT 1
            """)
    RequirementStageCommandRow findByRemediationIdentity(
            @Param("taskId") long taskId,
            @Param("role") String role,
            @Param("stage") String stage,
            @Param("remediationRoundId") long remediationRoundId);

    @Select("""
            SELECT *
              FROM rd_requirement_stage_commands
             WHERE id = #{id}
            """)
    RequirementStageCommandRow findById(@Param("id") long id);

    /** Locks one known command after its ledger and task rows are locked by the transaction port. */
    @Select("SELECT * FROM rd_requirement_stage_commands WHERE id=#{id} FOR UPDATE")
    RequirementStageCommandRow lockByIdForUpdate(@Param("id") long id);

    /**
     * Claims a bounded batch under row locks. The SKIP LOCKED clause keeps one command from
     * being leased by two scheduler instances without holding a JVM lock.
     */
    @Select("""
            WITH candidates AS (
                SELECT id
                FROM rd_requirement_stage_commands
                WHERE attempt_no < max_attempts
                  AND fencing_token > 0
                  AND (deadline_at IS NULL OR deadline_at > #{now})
                  AND ((status IN ('PENDING', 'FAILED_RETRYABLE') AND next_visible_at <= #{now})
                   OR (status = 'RUNNING' AND lease_until <= #{now}))
                ORDER BY (priority_rank - FLOOR(
                              EXTRACT(EPOCH FROM (#{now} - created_at)) / 60.0
                          )) ASC,
                         created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT #{batchSize}
            )
            UPDATE rd_requirement_stage_commands command
               SET status = 'RUNNING',
                   attempt_no = command.attempt_no + 1,
                   lease_owner = #{leaseOwner},
                   lease_until = #{leaseUntil},
                   last_error = '',
                   updated_at = #{now}
              FROM candidates
             WHERE command.id = candidates.id
             RETURNING command.*
            """)
    List<RequirementStageCommandRow> claimBatch(
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil,
            @Param("batchSize") int batchSize
    );

    /** Claims only the ids selected by the Host fairness planner under row locks. */
    @Select({
            "<script>",
            "WITH candidates AS (",
            "  SELECT id FROM rd_requirement_stage_commands",
            "   WHERE id IN",
            "   <foreach collection='commandIds' item='commandId' open='(' separator=',' close=')'>",
            "     #{commandId}",
            "   </foreach>",
            "     AND attempt_no &lt; max_attempts",
            "     AND fencing_token &gt; 0",
            "     AND (deadline_at IS NULL OR deadline_at &gt; #{now})",
            "     AND ((status IN ('PENDING', 'FAILED_RETRYABLE') AND next_visible_at &lt;= #{now})",
            "       OR (status = 'RUNNING' AND lease_until &lt;= #{now}))",
            "   ORDER BY (priority_rank - FLOOR(EXTRACT(EPOCH FROM (#{now} - created_at)) / 60.0)) ASC,",
            "            created_at, id",
            "   FOR UPDATE SKIP LOCKED",
            "   LIMIT #{batchSize}",
            ")",
            "UPDATE rd_requirement_stage_commands command",
            "   SET status = 'RUNNING', attempt_no = command.attempt_no + 1,",
            "       lease_owner = #{leaseOwner}, lease_until = #{leaseUntil},",
            "       last_error = '', updated_at = #{now}",
            "  FROM candidates",
            " WHERE command.id = candidates.id",
            " RETURNING command.*",
            "</script>"
    })
    List<RequirementStageCommandRow> claimSelectedBatch(
            @Param("commandIds") List<Long> commandIds,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil,
            @Param("batchSize") int batchSize
    );

    /** Acquires the transaction-scoped lock that serializes fair quota admission across nodes. */
    @Select("""
            WITH fair_admission_lock AS (
                SELECT pg_advisory_xact_lock(#{lockKey})
            )
            SELECT 1 FROM fair_admission_lock
            """)
    int acquireFairAdmissionLock(@Param("lockKey") long lockKey);

    /** Locks the Host-planned candidate rows before their quotas are re-evaluated in Java. */
    @Select({
            "<script>",
            "SELECT * FROM rd_requirement_stage_commands",
            " WHERE id IN",
            " <foreach collection='commandIds' item='commandId' open='(' separator=',' close=')'>",
            "   #{commandId}",
            " </foreach>",
            "   AND attempt_no &lt; max_attempts",
            "   AND fencing_token &gt; 0",
            "   AND (deadline_at IS NULL OR deadline_at &gt; #{now})",
            "   AND ((status IN ('PENDING', 'FAILED_RETRYABLE') AND next_visible_at &lt;= #{now})",
            "     OR (status = 'RUNNING' AND lease_until &lt;= #{now}))",
            " ORDER BY (priority_rank - FLOOR(EXTRACT(EPOCH FROM (#{now} - created_at)) / 60.0)) ASC,",
            "          created_at, id",
            " FOR UPDATE SKIP LOCKED",
            "</script>"
    })
    List<RequirementStageCommandRow> lockFairCandidates(
            @Param("commandIds") List<Long> commandIds,
            @Param("now") OffsetDateTime now
    );

    /** Returns every currently active lease for an exact global quota snapshot. */
    @Select("""
            SELECT *
              FROM rd_requirement_stage_commands
             WHERE status = 'RUNNING' AND lease_until > #{now}
               AND fencing_token > 0
             ORDER BY updated_at, id
            """)
    List<RequirementStageCommandRow> inFlightForFairAdmission(@Param("now") OffsetDateTime now);

    @Select("""
            UPDATE rd_requirement_stage_commands
               SET status = 'RUNNING', attempt_no = attempt_no + 1,
                   lease_owner = #{leaseOwner}, lease_until = #{leaseUntil},
                   last_error = '', updated_at = #{now}
             WHERE id = #{id}
               AND (deadline_at IS NULL OR deadline_at > #{now})
               AND ((status IN ('PENDING', 'FAILED_RETRYABLE') AND next_visible_at <= #{now})
                 OR (status = 'RUNNING' AND lease_until <= #{now}))
               AND attempt_no < max_attempts
               AND fencing_token > 0
             RETURNING *
            """)
    RequirementStageCommandRow claimOne(
            @Param("id") long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil
    );

    @Select("""
            UPDATE rd_requirement_stage_commands
               SET lease_until = #{leaseUntil}, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND lease_owner = #{leaseOwner}
             RETURNING *
            """)
    RequirementStageCommandRow heartbeat(
            @Param("id") long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil
    );

    /** Extends only the exact unexpired attempt currently held by the caller. */
    @Select("""
            UPDATE rd_requirement_stage_commands
               SET lease_until = #{leaseUntil}, updated_at = #{now}
             WHERE id = #{id} AND attempt_no = #{attemptNo} AND status = 'RUNNING'
               AND lease_owner = #{leaseOwner} AND lease_until > #{now}
             RETURNING *
            """)
    RequirementStageCommandRow heartbeatAttempt(
            @Param("id") long id,
            @Param("attemptNo") int attemptNo,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil
    );

    @Select("""
            UPDATE rd_requirement_stage_commands
               SET status = 'SUCCEEDED', lease_owner = '', lease_until = NULL, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND lease_owner = #{leaseOwner}
             RETURNING *
            """)
    RequirementStageCommandRow complete(
            @Param("id") long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now
    );

    /** Completes only the exact unexpired attempt currently held by the caller. */
    @Select("""
            UPDATE rd_requirement_stage_commands
               SET status = 'SUCCEEDED', lease_owner = '', lease_until = NULL, updated_at = #{now}
             WHERE id = #{id} AND attempt_no = #{attemptNo} AND status = 'RUNNING'
               AND lease_owner = #{leaseOwner} AND lease_until > #{now}
             RETURNING *
            """)
    RequirementStageCommandRow completeAttempt(
            @Param("id") long id,
            @Param("attemptNo") int attemptNo,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now
    );

    @Select("""
            UPDATE rd_requirement_stage_commands
               SET status = CASE WHEN attempt_no >= max_attempts THEN 'DEAD_LETTERED' ELSE 'FAILED_RETRYABLE' END,
                   lease_owner = '', lease_until = NULL, next_visible_at = #{nextVisibleAt},
                   last_error = #{lastError}, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND lease_owner = #{leaseOwner}
             RETURNING *
            """)
    RequirementStageCommandRow fail(
            @Param("id") long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("lastError") String lastError,
            @Param("now") OffsetDateTime now,
            @Param("nextVisibleAt") OffsetDateTime nextVisibleAt
    );

    /** Retries only the exact unexpired attempt currently held by the caller. */
    @Select("""
            UPDATE rd_requirement_stage_commands
               SET status = CASE WHEN attempt_no >= max_attempts THEN 'DEAD_LETTERED' ELSE 'FAILED_RETRYABLE' END,
                   lease_owner = '', lease_until = NULL, next_visible_at = #{nextVisibleAt},
                   last_error = #{lastError}, updated_at = #{now}
             WHERE id = #{id} AND attempt_no = #{attemptNo} AND status = 'RUNNING'
               AND lease_owner = #{leaseOwner} AND lease_until > #{now}
             RETURNING *
            """)
    RequirementStageCommandRow failAttempt(
            @Param("id") long id,
            @Param("attemptNo") int attemptNo,
            @Param("leaseOwner") String leaseOwner,
            @Param("lastError") String lastError,
            @Param("now") OffsetDateTime now,
            @Param("nextVisibleAt") OffsetDateTime nextVisibleAt
    );

    @Select("""
            WITH ranked_candidates AS (
                SELECT id,
                       COALESCE(NULLIF(BTRIM(project_id), ''), '_default') AS project_key,
                       priority_rank - FLOOR(
                           EXTRACT(EPOCH FROM (#{now} - created_at)) * 1000.0 / #{agingMillis}
                       ) AS effective_priority,
                       ROW_NUMBER() OVER (
                           PARTITION BY COALESCE(NULLIF(BTRIM(project_id), ''), '_default')
                           ORDER BY (priority_rank - FLOOR(
                                         EXTRACT(EPOCH FROM (#{now} - created_at)) * 1000.0 / #{agingMillis}
                                     )) ASC,
                                    created_at, id
                       ) AS project_rank
                  FROM rd_requirement_stage_commands
                 WHERE attempt_no < max_attempts
                   AND fencing_token > 0
                   AND (deadline_at IS NULL OR deadline_at > #{now})
                   AND ((status IN ('PENDING', 'FAILED_RETRYABLE') AND next_visible_at <= #{now})
                    OR (status = 'RUNNING' AND lease_until <= #{now}))
            ),
            project_catalog AS (
                SELECT project_key,
                       ROW_NUMBER() OVER (ORDER BY project_key) - 1 AS project_position,
                       COUNT(*) OVER () AS project_count
                  FROM (
                      SELECT DISTINCT project_key
                        FROM ranked_candidates
                  ) project_keys
            ),
            rotated_candidates AS (
                SELECT candidate.*,
                       MOD(
                           catalog.project_position
                           - (FLOOR(EXTRACT(EPOCH FROM #{now}) * 1000.0 / #{agingMillis})::BIGINT
                              % catalog.project_count)
                           + catalog.project_count,
                           catalog.project_count
                       ) AS project_turn
                  FROM ranked_candidates candidate
                  JOIN project_catalog catalog ON catalog.project_key = candidate.project_key
            )
            SELECT command.*
              FROM rd_requirement_stage_commands command
              JOIN rotated_candidates candidate ON candidate.id = command.id
             ORDER BY candidate.project_rank, candidate.project_turn, candidate.effective_priority ASC,
                      command.created_at, command.id
             LIMIT #{limit}
            """)
    List<RequirementStageCommandRow> recoverable(
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit,
            @Param("agingMillis") long agingMillis
    );

    @Select("""
            SELECT *
              FROM rd_requirement_stage_commands
             WHERE status = 'RUNNING' AND lease_until > #{now}
               AND fencing_token > 0
             ORDER BY updated_at, id
             LIMIT #{limit}
            """)
    List<RequirementStageCommandRow> inFlight(
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit
    );

    @Select("""
            UPDATE rd_requirement_stage_commands
               SET status = 'DEAD_LETTERED', lease_owner = '', lease_until = NULL,
                   last_error = 'stage command deadline exceeded', updated_at = #{now}
             WHERE id IN (
                 SELECT id
                   FROM rd_requirement_stage_commands
                  WHERE status IN ('PENDING', 'FAILED_RETRYABLE', 'RUNNING')
                    AND deadline_at IS NOT NULL AND deadline_at <= #{now}
                  ORDER BY deadline_at, id
                  LIMIT #{limit}
                  FOR UPDATE SKIP LOCKED
             )
             RETURNING *
            """)
    List<RequirementStageCommandRow> deadLetterExpired(
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit
    );
}
