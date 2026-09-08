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
            SELECT *
              FROM rd_requirement_stage_commands
             WHERE task_id = #{taskId} AND role = #{role} AND stage = #{stage}
             ORDER BY created_at DESC, id DESC
             LIMIT 1
            """)
    RequirementStageCommandRow findLatestAnyGeneration(
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
                SELECT command.id
                FROM rd_requirement_stage_commands command
                JOIN rd_tasks task ON task.id = command.task_id
                WHERE command.attempt_no < command.max_attempts
                  AND command.fencing_token > 0
                  AND (command.deadline_at IS NULL OR command.deadline_at > #{now})
                  AND ((command.status IN ('PENDING', 'FAILED_RETRYABLE') AND command.next_visible_at <= #{now})
                   OR (command.status = 'RUNNING' AND command.lease_until <= #{now}))
                  AND COALESCE(task.paused, FALSE) = FALSE
                  AND (task.status IS DISTINCT FROM 'WAITING_USER_INPUT'
                       OR command.stage LIKE 'USER_ANSWER_RESUME:%')
                ORDER BY (command.priority_rank - FLOOR(
                              EXTRACT(EPOCH FROM (#{now} - command.created_at)) / 60.0
                          )) ASC,
                         command.created_at, command.id
                FOR UPDATE OF command SKIP LOCKED
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
            "  SELECT command.id FROM rd_requirement_stage_commands command",
            "  JOIN rd_tasks task ON task.id = command.task_id",
            "   WHERE command.id IN",
            "   <foreach collection='commandIds' item='commandId' open='(' separator=',' close=')'>",
            "     #{commandId}",
            "   </foreach>",
            "     AND command.attempt_no &lt; command.max_attempts",
            "     AND command.fencing_token &gt; 0",
            "     AND (command.deadline_at IS NULL OR command.deadline_at &gt; #{now})",
            "     AND ((command.status IN ('PENDING', 'FAILED_RETRYABLE') AND command.next_visible_at &lt;= #{now})",
            "       OR (command.status = 'RUNNING' AND command.lease_until &lt;= #{now}))",
            "     AND COALESCE(task.paused, FALSE) = FALSE",
            "     AND (task.status IS DISTINCT FROM 'WAITING_USER_INPUT'",
            "          OR command.stage LIKE 'USER_ANSWER_RESUME:%')",
            "   ORDER BY (command.priority_rank - FLOOR(EXTRACT(EPOCH FROM (#{now} - command.created_at)) / 60.0)) ASC,",
            "            command.created_at, command.id",
            "   FOR UPDATE OF command SKIP LOCKED",
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
            "SELECT command.* FROM rd_requirement_stage_commands command",
            " JOIN rd_tasks task ON task.id = command.task_id",
            " WHERE command.id IN",
            " <foreach collection='commandIds' item='commandId' open='(' separator=',' close=')'>",
            "   #{commandId}",
            " </foreach>",
            "   AND command.attempt_no &lt; command.max_attempts",
            "   AND command.fencing_token &gt; 0",
            "   AND (command.deadline_at IS NULL OR command.deadline_at &gt; #{now})",
            "   AND ((command.status IN ('PENDING', 'FAILED_RETRYABLE') AND command.next_visible_at &lt;= #{now})",
            "     OR (command.status = 'RUNNING' AND command.lease_until &lt;= #{now}))",
            "   AND COALESCE(task.paused, FALSE) = FALSE",
            "   AND (task.status IS DISTINCT FROM 'WAITING_USER_INPUT'",
            "        OR command.stage LIKE 'USER_ANSWER_RESUME:%')",
            " ORDER BY (command.priority_rank - FLOOR(EXTRACT(EPOCH FROM (#{now} - command.created_at)) / 60.0)) ASC,",
            "          command.created_at, command.id",
            " FOR UPDATE OF command SKIP LOCKED",
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
            UPDATE rd_requirement_stage_commands command
               SET status = 'RUNNING', attempt_no = command.attempt_no + 1,
                   lease_owner = #{leaseOwner}, lease_until = #{leaseUntil},
                   last_error = '', updated_at = #{now}
              FROM rd_tasks task
             WHERE command.id = #{id}
               AND task.id = command.task_id
               AND COALESCE(task.paused, FALSE) = FALSE
               AND (task.status IS DISTINCT FROM 'WAITING_USER_INPUT'
                    OR command.stage LIKE 'USER_ANSWER_RESUME:%')
               AND (command.deadline_at IS NULL OR command.deadline_at > #{now})
               AND ((command.status IN ('PENDING', 'FAILED_RETRYABLE') AND command.next_visible_at <= #{now})
                 OR (command.status = 'RUNNING' AND command.lease_until <= #{now}))
               AND command.attempt_no < command.max_attempts
               AND command.fencing_token > 0
             RETURNING command.*
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
                SELECT command.id,
                       COALESCE(NULLIF(BTRIM(command.project_id), ''), '_default') AS project_key,
                       command.priority_rank - FLOOR(
                           EXTRACT(EPOCH FROM (#{now} - command.created_at)) * 1000.0 / #{agingMillis}
                       ) AS effective_priority,
                       ROW_NUMBER() OVER (
                           PARTITION BY COALESCE(NULLIF(BTRIM(command.project_id), ''), '_default')
                           ORDER BY (command.priority_rank - FLOOR(
                                         EXTRACT(EPOCH FROM (#{now} - command.created_at)) * 1000.0 / #{agingMillis}
                                     )) ASC,
                                    command.created_at, command.id
                       ) AS project_rank
                  FROM rd_requirement_stage_commands command
                  JOIN rd_tasks task ON task.id = command.task_id
                 WHERE command.attempt_no < command.max_attempts
                   AND command.fencing_token > 0
                   AND (command.deadline_at IS NULL OR command.deadline_at > #{now})
                   AND ((command.status IN ('PENDING', 'FAILED_RETRYABLE') AND command.next_visible_at <= #{now})
                    OR (command.status = 'RUNNING' AND command.lease_until <= #{now}))
                   AND COALESCE(task.paused, FALSE) = FALSE
                   AND (task.status IS DISTINCT FROM 'WAITING_USER_INPUT'
                        OR command.stage LIKE 'USER_ANSWER_RESUME:%')
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

    /**
     * First page of task-scoped commands (no cursor).
     *
     * @param taskId owning task
     * @param limit maximum rows
     * @return ordered rows
     */
    @Select("""
            SELECT *
              FROM rd_requirement_stage_commands
             WHERE task_id = #{taskId}
             ORDER BY created_at ASC, id ASC
             LIMIT #{limit}
            """)
    List<RequirementStageCommandRow> listByTaskFirstPage(
            @Param("taskId") long taskId,
            @Param("limit") int limit
    );

    /**
     * Task-scoped command page after an exclusive (created_at, id) cursor.
     *
     * @param taskId owning task
     * @param afterCreatedAt exclusive created_at
     * @param afterId exclusive id when timestamps tie
     * @param limit maximum rows
     * @return ordered rows
     */
    @Select("""
            SELECT *
              FROM rd_requirement_stage_commands
             WHERE task_id = #{taskId}
               AND (
                    created_at > #{afterCreatedAt}
                    OR (created_at = #{afterCreatedAt} AND id > #{afterId})
               )
             ORDER BY created_at ASC, id ASC
             LIMIT #{limit}
            """)
    List<RequirementStageCommandRow> listByTaskAfter(
            @Param("taskId") long taskId,
            @Param("afterCreatedAt") OffsetDateTime afterCreatedAt,
            @Param("afterId") Long afterId,
            @Param("limit") int limit
    );

    /**
     * All command ids for one task.
     *
     * @param taskId owning task
     * @return ids
     */
    @Select("""
            SELECT id
              FROM rd_requirement_stage_commands
             WHERE task_id = #{taskId}
             ORDER BY created_at ASC, id ASC
            """)
    List<Long> listIdsByTask(@Param("taskId") long taskId);

    /**
     * Commands for one task bound to a retry binding.
     *
     * @param taskId owning task
     * @param bindingId retry binding id
     * @return matching rows
     */
    @Select("""
            SELECT *
              FROM rd_requirement_stage_commands
             WHERE task_id = #{taskId}
               AND target_retry_binding_id = #{bindingId}
             ORDER BY created_at ASC, id ASC
            """)
    List<RequirementStageCommandRow> listByTargetRetryBinding(
            @Param("taskId") long taskId,
            @Param("bindingId") long bindingId
    );
}
