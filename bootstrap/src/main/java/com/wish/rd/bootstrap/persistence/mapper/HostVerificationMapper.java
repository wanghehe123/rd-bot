package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.HostVerificationArtifactRow;
import com.wish.rd.bootstrap.persistence.entity.HostVerificationRunRow;
import com.wish.rd.bootstrap.persistence.entity.HostVerificationStepRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** MyBatis mapper for host verification runs, steps, and artifacts. */
@Mapper
public interface HostVerificationMapper extends BaseMapper<HostVerificationRunRow> {

    /**
     * Inserts a new verification run snapshot.
     *
     * @param row run row
     */
    @Insert("""
            INSERT INTO rd_host_verification_runs (
                id, task_id, coding_stage_run_id, parent_run_id, attempt_no, status,
                docs_only, failure_category, error_message, remediation_count,
                created_at, started_at, finished_at
            ) VALUES (
                #{id}, #{taskId}, #{codingStageRunId}, #{parentRunId}, #{attemptNo}, #{status},
                #{docsOnly}, #{failureCategory}, #{errorMessage}, #{remediationCount},
                #{createdAt}, #{startedAt}, #{finishedAt}
            )
            """)
    void insertRun(HostVerificationRunRow row);

    /**
     * Loads one run by primary key.
     *
     * @param id run id
     * @return row or {@code null}
     */
    @Select("""
            SELECT id,
                   task_id AS taskId,
                   coding_stage_run_id AS codingStageRunId,
                   parent_run_id AS parentRunId,
                   attempt_no AS attemptNo,
                   status,
                   docs_only AS docsOnly,
                   failure_category AS failureCategory,
                   error_message AS errorMessage,
                   remediation_count AS remediationCount,
                   created_at AS createdAt,
                   started_at AS startedAt,
                   finished_at AS finishedAt
              FROM rd_host_verification_runs
             WHERE id = #{id}
            """)
    HostVerificationRunRow findById(@Param("id") long id);

    /**
     * Lists runs for a task ordered by attempt number.
     *
     * @param taskId owning task id
     * @return rows, possibly empty
     */
    @Select("""
            SELECT id,
                   task_id AS taskId,
                   coding_stage_run_id AS codingStageRunId,
                   parent_run_id AS parentRunId,
                   attempt_no AS attemptNo,
                   status,
                   docs_only AS docsOnly,
                   failure_category AS failureCategory,
                   error_message AS errorMessage,
                   remediation_count AS remediationCount,
                   created_at AS createdAt,
                   started_at AS startedAt,
                   finished_at AS finishedAt
              FROM rd_host_verification_runs
             WHERE task_id = #{taskId}
             ORDER BY attempt_no ASC, created_at ASC, id ASC
            """)
    List<HostVerificationRunRow> listByTaskId(@Param("taskId") long taskId);

    /**
     * CAS-updates a run only when the persisted status still matches.
     *
     * @param row             next snapshot
     * @param expectedStatus  status observed by the caller
     * @return {@code 1} when updated, {@code 0} on a stale write
     */
    @Update("""
            UPDATE rd_host_verification_runs
               SET status = #{row.status},
                   failure_category = #{row.failureCategory},
                   error_message = #{row.errorMessage},
                   started_at = #{row.startedAt},
                   finished_at = #{row.finishedAt}
             WHERE id = #{row.id}
               AND status = #{expectedStatus}
            """)
    int compareAndSet(
            @Param("row") HostVerificationRunRow row,
            @Param("expectedStatus") String expectedStatus
    );

    /**
     * Inserts or replaces one BUILD/STATIC step.
     *
     * @param row step row
     */
    @Insert("""
            INSERT INTO rd_host_verification_steps (
                run_id, step, status, commands_json, exit_code,
                duration_millis, log_artifact_id, error_message
            ) VALUES (
                #{runId}, #{step}, #{status}, #{commandsJson}::jsonb, #{exitCode},
                #{durationMillis}, #{logArtifactId}, #{errorMessage}
            )
            ON CONFLICT (run_id, step) DO UPDATE SET
                status = EXCLUDED.status,
                commands_json = EXCLUDED.commands_json,
                exit_code = EXCLUDED.exit_code,
                duration_millis = EXCLUDED.duration_millis,
                log_artifact_id = EXCLUDED.log_artifact_id,
                error_message = EXCLUDED.error_message
            """)
    void upsertStep(HostVerificationStepRow row);

    /**
     * Lists steps for a run in BUILD-then-STATIC name order.
     *
     * @param runId verification run id
     * @return rows, possibly empty
     */
    @Select("""
            SELECT run_id AS runId,
                   step,
                   status,
                   commands_json::text AS commandsJson,
                   exit_code AS exitCode,
                   duration_millis AS durationMillis,
                   log_artifact_id AS logArtifactId,
                   error_message AS errorMessage
              FROM rd_host_verification_steps
             WHERE run_id = #{runId}
             ORDER BY step ASC
            """)
    List<HostVerificationStepRow> listSteps(@Param("runId") long runId);

    /**
     * Inserts one evidence object.
     *
     * @param row artifact row
     */
    @Insert("""
            INSERT INTO rd_host_verification_artifacts (
                id, task_id, run_id, artifact_type, relative_path, object_uri,
                content_type, size_bytes, sha256, created_at
            ) VALUES (
                #{id}, #{taskId}, #{runId}, #{artifactType}, #{relativePath}, #{objectUri},
                #{contentType}, #{sizeBytes}, #{sha256}, #{createdAt}
            )
            """)
    void insertArtifact(HostVerificationArtifactRow row);

    /**
     * Lists artifacts for a run in created order.
     *
     * @param runId verification run id
     * @return rows, possibly empty
     */
    @Select("""
            SELECT id,
                   task_id AS taskId,
                   run_id AS runId,
                   artifact_type AS artifactType,
                   relative_path AS relativePath,
                   object_uri AS objectUri,
                   content_type AS contentType,
                   size_bytes AS sizeBytes,
                   sha256,
                   created_at AS createdAt
              FROM rd_host_verification_artifacts
             WHERE run_id = #{runId}
             ORDER BY created_at ASC, id ASC
            """)
    List<HostVerificationArtifactRow> listArtifacts(@Param("runId") long runId);
}
