package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.AgentExecutionProfileSnapshotRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** PostgreSQL mapper for append-only execution snapshots. */
@Mapper
public interface AgentExecutionProfileSnapshotMapper {

    @Insert("""
            INSERT INTO rd_agent_execution_profile_snapshots (
              snapshot_id, stage_run_id, task_id, role, attempt_no, runtime_type,
              snapshot_json, snapshot_hash, resolved_at
            ) VALUES (
              #{snapshotId}, #{stageRunId}, #{taskId}, #{role}, #{attemptNo}, #{runtimeType},
              #{snapshotJson}, #{snapshotHash}, #{resolvedAt}
            ) ON CONFLICT (stage_run_id) DO NOTHING
            """)
    int insertIfAbsent(AgentExecutionProfileSnapshotRow row);

    @Select("""
            SELECT snapshot_id, stage_run_id, task_id, role, attempt_no, runtime_type,
                   snapshot_json, snapshot_hash, resolved_at
            FROM rd_agent_execution_profile_snapshots
            WHERE stage_run_id=#{stageRunId}
            """)
    AgentExecutionProfileSnapshotRow findByStageRunId(@Param("stageRunId") long stageRunId);

    @Select("""
            SELECT snapshot_id, stage_run_id, task_id, role, attempt_no, runtime_type,
                   snapshot_json, snapshot_hash, resolved_at
            FROM rd_agent_execution_profile_snapshots
            WHERE snapshot_id=#{snapshotId}
            """)
    AgentExecutionProfileSnapshotRow findBySnapshotId(@Param("snapshotId") String snapshotId);
}
