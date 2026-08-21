package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.AgentRemediationRoundRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentRemediationRoundMapper {

    @Insert("""
            INSERT INTO rd_agent_remediation_rounds (
              id, task_id, kind, remediation_no, source_stage_run_id, source_command_id,
              source_result_hash, source_task_version, source_fencing_token,
              target_coding_stage_run_id, target_qa_stage_run_id, first_command_id,
              coding_profile_snapshot_id, qa_profile_snapshot_id, request_json, request_hash,
              status, row_version, lease_owner, lease_until, created_at, updated_at, claimed_at
            ) VALUES (
              #{id}, #{taskId}, #{kind}, #{remediationNo}, #{sourceStageRunId}, #{sourceCommandId},
              #{sourceResultHash}, #{sourceTaskVersion}, #{sourceFencingToken},
              #{targetCodingStageRunId}, #{targetQaStageRunId}, NULL,
              #{codingProfileSnapshotId}, #{qaProfileSnapshotId}, CAST(#{requestJson} AS jsonb), #{requestHash},
              'CLAIMED', 0, '', NULL, #{createdAt}, #{updatedAt}, #{claimedAt}
            ) ON CONFLICT DO NOTHING
            """)
    int insertClaimed(AgentRemediationRoundRow row);

    /**
     * Serializes same-task remediation claims across instances without taking the task row lock
     * that {@code finalize} already acquires after the command row.
     */
    @Select("""
            WITH remediation_task_lock AS (
                SELECT pg_advisory_xact_lock(#{lockKey})
            )
            SELECT 1 FROM remediation_task_lock
            """)
    int acquireTaskLock(@Param("lockKey") long lockKey);

    /**
     * Locks the unique source-stage row for this kind, if it already exists.
     *
     * @param sourceStageRunId originating QA stage
     * @param kind remediation kind
     * @return existing round, or {@code null}
     */
    @Select("""
            SELECT * FROM rd_agent_remediation_rounds
             WHERE source_stage_run_id=#{sourceStageRunId} AND kind=#{kind}
             FOR UPDATE
            """)
    AgentRemediationRoundRow findBySourceForUpdate(
            @Param("sourceStageRunId") long sourceStageRunId,
            @Param("kind") String kind);

    /**
     * Locks the unique {@code (task_id, kind, remediation_no)} identity before target objects exist.
     *
     * @param taskId owning task
     * @param kind remediation kind
     * @param remediationNo proposed round number
     * @return existing occupant, or {@code null} when the number is still free
     */
    @Select("""
            SELECT * FROM rd_agent_remediation_rounds
             WHERE task_id=#{taskId} AND kind=#{kind} AND remediation_no=#{remediationNo}
             FOR UPDATE
            """)
    AgentRemediationRoundRow findByTaskKindNoForUpdate(
            @Param("taskId") long taskId,
            @Param("kind") String kind,
            @Param("remediationNo") int remediationNo);

    /**
     * Lists claimed rounds for occupancy scanning during {@code recordOutcome}.
     *
     * @param taskId owning task
     * @return rounds in creation order
     */
    @Select("""
            SELECT * FROM rd_agent_remediation_rounds
             WHERE task_id=#{taskId}
             ORDER BY created_at ASC, id ASC
            """)
    java.util.List<AgentRemediationRoundRow> listByTask(@Param("taskId") long taskId);

    @Update("""
            UPDATE rd_agent_remediation_rounds
               SET first_command_id=#{firstCommandId}, status='DISPATCHED', dispatched_at=#{now},
                   row_version=row_version+1, updated_at=#{now}
             WHERE id=#{id} AND first_command_id IS NULL AND status='CLAIMED'
            """)
    int markDispatched(
            @Param("id") long id,
            @Param("firstCommandId") long firstCommandId,
            @Param("now") java.time.OffsetDateTime now);
}
