package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.TaskRetryAttemptBindingRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** MyBatis mapper for immutable checkpoint-local retry attempt bindings. */
@Mapper
public interface TaskRetryAttemptBindingMapper extends BaseMapper<TaskRetryAttemptBindingRow> {

    @Insert("""
            INSERT INTO rd_task_retry_attempt_bindings
                (id, checkpoint_id, binding_kind, role, stage_run_id, retrieval_run_id,
                 ai_review_run_id, parent_binding_id, attempt_no, ordinal, created_at)
            VALUES (#{id}, #{checkpointId}, #{bindingKind}, #{role}, #{stageRunId}, #{retrievalRunId},
                    #{aiReviewRunId}, #{parentBindingId}, #{attemptNo}, #{ordinal}, #{createdAt})
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(TaskRetryAttemptBindingRow row);

    @Select("SELECT * FROM rd_task_retry_attempt_bindings WHERE id = #{id}")
    TaskRetryAttemptBindingRow findById(@Param("id") long id);

    @Select("""
            SELECT * FROM rd_task_retry_attempt_bindings
             WHERE checkpoint_id = #{checkpointId}
             ORDER BY ordinal, id
            """)
    List<TaskRetryAttemptBindingRow> listByCheckpoint(@Param("checkpointId") long checkpointId);

    @Select("""
            SELECT * FROM rd_task_retry_attempt_bindings
             WHERE checkpoint_id = #{checkpointId} AND binding_kind = #{kind} AND role = #{role}
               AND parent_binding_id IS NULL
             ORDER BY ordinal, id
            """)
    List<TaskRetryAttemptBindingRow> findPrimary(
            @Param("checkpointId") long checkpointId, @Param("kind") String kind, @Param("role") String role);

    @Select("""
            SELECT * FROM rd_task_retry_attempt_bindings
             WHERE checkpoint_id = #{checkpointId} AND parent_binding_id = #{parentBindingId}
               AND binding_kind = #{kind} AND ordinal = #{ordinal}
             ORDER BY id
            """)
    List<TaskRetryAttemptBindingRow> findChild(
            @Param("checkpointId") long checkpointId, @Param("parentBindingId") long parentBindingId,
            @Param("kind") String kind, @Param("ordinal") int ordinal);

    @Select("SELECT * FROM rd_task_retry_attempt_bindings WHERE stage_run_id = #{stageRunId} ORDER BY id")
    List<TaskRetryAttemptBindingRow> findByStageRunId(@Param("stageRunId") long stageRunId);
}
