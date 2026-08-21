package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.AgentStageStateProjectionRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentStageStateProjectionMapper {

    @Insert("""
            INSERT INTO rd_agent_stage_state_latest (
              stage_run_id, task_id, role, attempt_no,
              state_sequence, state_hash, state_json, state_projected_at
            ) VALUES (
              #{stageRunId}, #{taskId}, #{role}, #{attemptNo},
              #{stateSequence}, #{stateHash}, #{stateJson}, #{stateProjectedAt}
            ) ON CONFLICT (stage_run_id) DO NOTHING
            """)
    int insertState(AgentStageStateProjectionRow row);

    @Insert("""
            INSERT INTO rd_agent_stage_state_latest (
              stage_run_id, task_id, role, attempt_no,
              injection_sequence, injected_state_sequence, injected_state_hash,
              prompt_hash, injected_block_hash, injected_block,
              injection_idempotency_key, injected_at
            ) VALUES (
              #{stageRunId}, #{taskId}, #{role}, #{attemptNo},
              #{injectionSequence}, #{injectedStateSequence}, #{injectedStateHash},
              #{promptHash}, #{injectedBlockHash}, #{injectedBlock},
              #{injectionIdempotencyKey}, #{injectedAt}
            ) ON CONFLICT (stage_run_id) DO NOTHING
            """)
    int insertInjection(AgentStageStateProjectionRow row);

    @Update("""
            UPDATE rd_agent_stage_state_latest SET
              state_sequence=#{stateSequence},
              state_hash=#{stateHash},
              state_json=#{stateJson},
              state_projected_at=#{stateProjectedAt},
              updated_at=now()
            WHERE stage_run_id=#{stageRunId}
              AND task_id=#{taskId} AND role=#{role} AND attempt_no=#{attemptNo}
              AND finalized=FALSE AND state_sequence < #{stateSequence}
            """)
    int updateState(AgentStageStateProjectionRow row);

    @Update("""
            UPDATE rd_agent_stage_state_latest SET
              injection_sequence=#{injectionSequence},
              injected_state_sequence=#{injectedStateSequence},
              injected_state_hash=#{injectedStateHash},
              prompt_hash=#{promptHash},
              injected_block_hash=#{injectedBlockHash},
              injected_block=#{injectedBlock},
              injection_idempotency_key=#{injectionIdempotencyKey},
              injected_at=#{injectedAt},
              updated_at=now()
            WHERE stage_run_id=#{stageRunId}
              AND task_id=#{taskId} AND role=#{role} AND attempt_no=#{attemptNo}
              AND finalized=FALSE AND injection_sequence < #{injectionSequence}
            """)
    int updateInjection(AgentStageStateProjectionRow row);

    @Update("""
            UPDATE rd_agent_stage_state_latest SET
              finalized=TRUE, finalized_at=#{finalizedAt}, updated_at=now()
            WHERE stage_run_id=#{stageRunId}
              AND task_id=#{taskId} AND role=#{role} AND attempt_no=#{attemptNo}
              AND finalized=FALSE
            """)
    int finalizeProjection(AgentStageStateProjectionRow row);

    @Select("""
            SELECT stage_run_id, task_id, role, attempt_no,
                   state_sequence, state_hash, state_json, state_projected_at,
                   injection_sequence, injected_state_sequence, injected_state_hash,
                   prompt_hash, injected_block_hash, injected_block,
                   injection_idempotency_key, injected_at,
                   finalized, finalized_at, updated_at
            FROM rd_agent_stage_state_latest
            WHERE stage_run_id=#{stageRunId}
            """)
    AgentStageStateProjectionRow find(@Param("stageRunId") long stageRunId);
}
