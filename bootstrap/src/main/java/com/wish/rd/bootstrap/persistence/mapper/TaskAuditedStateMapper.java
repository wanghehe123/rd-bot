package com.wish.rd.bootstrap.persistence.mapper;

import com.wish.rd.bootstrap.persistence.entity.TaskAuditRunRow;
import com.wish.rd.bootstrap.persistence.entity.TaskAuditedStateHeadRow;
import com.wish.rd.bootstrap.persistence.entity.TaskAuditedStateRevisionRow;
import com.wish.rd.bootstrap.persistence.entity.TaskCompletionBindingRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** MyBatis mapper for Host audited-state heads, revisions, runs, and completion bindings. */
@Mapper
public interface TaskAuditedStateMapper {

    @Select("""
            SELECT task_id AS taskId,
                   state_version AS stateVersion,
                   state_hash AS stateHash,
                   last_audit_run_id AS lastAuditRunId,
                   updated_at AS updatedAt
              FROM rd_task_audited_state_heads
             WHERE task_id = #{taskId}
            """)
    TaskAuditedStateHeadRow findHead(@Param("taskId") long taskId);

    @Insert("""
            INSERT INTO rd_task_audited_state_heads (
                task_id, state_version, state_hash, last_audit_run_id, updated_at
            ) VALUES (
                #{taskId}, #{stateVersion}, #{stateHash}, #{lastAuditRunId}, #{updatedAt}
            )
            ON CONFLICT (task_id) DO NOTHING
            """)
    int insertHeadIfAbsent(TaskAuditedStateHeadRow row);

    @Update("""
            UPDATE rd_task_audited_state_heads
               SET state_version = #{nextVersion},
                   state_hash = #{stateHash},
                   last_audit_run_id = #{lastAuditRunId},
                   updated_at = #{updatedAt}
             WHERE task_id = #{taskId}
               AND state_version = #{expectedPreviousVersion}
            """)
    int casHead(
            @Param("taskId") long taskId,
            @Param("expectedPreviousVersion") long expectedPreviousVersion,
            @Param("nextVersion") long nextVersion,
            @Param("stateHash") String stateHash,
            @Param("lastAuditRunId") String lastAuditRunId,
            @Param("updatedAt") java.time.OffsetDateTime updatedAt
    );

    @Select("""
            SELECT task_id AS taskId,
                   state_version AS stateVersion,
                   state_hash AS stateHash,
                   state_json::text AS stateJson,
                   audit_run_id AS auditRunId,
                   command_id AS commandId,
                   created_at AS createdAt
              FROM rd_task_audited_state_revisions
             WHERE task_id = #{taskId}
               AND state_version = #{stateVersion}
            """)
    TaskAuditedStateRevisionRow findRevision(
            @Param("taskId") long taskId,
            @Param("stateVersion") long stateVersion
    );

    @Insert("""
            INSERT INTO rd_task_audited_state_revisions (
                task_id, state_version, state_hash, state_json, audit_run_id, command_id, created_at
            ) VALUES (
                #{taskId}, #{stateVersion}, #{stateHash}, CAST(#{stateJson} AS jsonb),
                #{auditRunId}, #{commandId}, #{createdAt}
            )
            ON CONFLICT (task_id, state_version) DO NOTHING
            """)
    int insertRevisionIfAbsent(TaskAuditedStateRevisionRow row);

    @Select("""
            SELECT audit_run_id AS auditRunId,
                   task_id AS taskId,
                   subject_stage_run_id AS subjectStageRunId,
                   subject_role AS subjectRole,
                   command_id AS commandId,
                   completion,
                   integrity,
                   contract_audit AS contractAudit,
                   report_json::text AS reportJson,
                   report_hash AS reportHash,
                   created_at AS createdAt
              FROM rd_task_audit_runs
             WHERE command_id = #{commandId}
            """)
    TaskAuditRunRow findRunByCommandId(@Param("commandId") long commandId);

    @Select("""
            SELECT audit_run_id AS auditRunId,
                   task_id AS taskId,
                   subject_stage_run_id AS subjectStageRunId,
                   subject_role AS subjectRole,
                   command_id AS commandId,
                   completion,
                   integrity,
                   contract_audit AS contractAudit,
                   report_json::text AS reportJson,
                   report_hash AS reportHash,
                   created_at AS createdAt
              FROM rd_task_audit_runs
             WHERE task_id = #{taskId}
             ORDER BY created_at, audit_run_id
            """)
    List<TaskAuditRunRow> listRunsByTaskId(@Param("taskId") long taskId);

    @Insert("""
            INSERT INTO rd_task_audit_runs (
                audit_run_id, task_id, subject_stage_run_id, subject_role, command_id,
                completion, integrity, contract_audit, report_json, report_hash, created_at
            ) VALUES (
                #{auditRunId}, #{taskId}, #{subjectStageRunId}, #{subjectRole}, #{commandId},
                #{completion}, #{integrity}, #{contractAudit}, CAST(#{reportJson} AS jsonb),
                #{reportHash}, #{createdAt}
            )
            ON CONFLICT (command_id) DO NOTHING
            """)
    int insertRunIfAbsent(TaskAuditRunRow row);

    @Select("""
            SELECT task_id AS taskId,
                   audit_run_id AS auditRunId,
                   state_version AS stateVersion,
                   state_hash AS stateHash,
                   bound_at AS boundAt
              FROM rd_task_completion_bindings
             WHERE task_id = #{taskId}
            """)
    TaskCompletionBindingRow findBinding(@Param("taskId") long taskId);

    @Insert("""
            INSERT INTO rd_task_completion_bindings (
                task_id, audit_run_id, state_version, state_hash, bound_at
            ) VALUES (
                #{taskId}, #{auditRunId}, #{stateVersion}, #{stateHash}, #{boundAt}
            )
            ON CONFLICT (task_id) DO NOTHING
            """)
    int insertBindingIfAbsent(TaskCompletionBindingRow row);
}
