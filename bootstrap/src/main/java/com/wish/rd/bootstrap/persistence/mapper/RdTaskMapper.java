package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

/**
 * RD 任务 MyBatis-Plus mapper。
 *
 * <p>供 PostgreSQL 任务存储适配器执行 upsert 和查询。
 */
@Mapper
public interface RdTaskMapper extends BaseMapper<RdTaskRow> {

    /** Locks one task row after its policy ledger row has been locked by the transaction port. */
    @Select("SELECT * FROM rd_tasks WHERE id = #{id} FOR UPDATE")
    RdTaskRow lockByIdForUpdate(@Param("id") long id);

    /**
     * 插入或更新任务快照。
     *
     * @param row 任务行
     */
    @Insert("""
            INSERT INTO rd_tasks (
                id, task_type, ticket_id, ticket_title, priority, status,
                message_id, title, prompt_snapshot, execution_result_json,
                pull_request_url, error_message, paused, source_type, source_id, source_url,
                project_id, project_key, project_name, repository_url, repo_owner, repo_name, base_branch, work_branch,
                expected_result, acceptance_criteria_json, host_assertion_bundle_json,
                token_budget_override, version, fencing_token, created_at, updated_at
            )
            VALUES (
                #{id}, #{taskType}, #{ticketId}, #{ticketTitle}, #{priority}, #{status},
                #{messageId}, #{title}, #{promptSnapshot}, #{executionResultJson}::jsonb,
                #{pullRequestUrl}, #{errorMessage}, COALESCE(#{paused}, FALSE), #{sourceType}, #{sourceId}, #{sourceUrl},
                #{projectId}, #{projectKey}, #{projectName}, #{repositoryUrl}, #{repoOwner}, #{repoName}, #{baseBranch}, #{workBranch},
                #{expectedResult}, #{acceptanceCriteriaJson}::jsonb, CAST(#{hostAssertionBundleJson} AS jsonb),
                COALESCE(#{tokenBudgetOverride}, 0),
                COALESCE(#{version}, 0), COALESCE(#{fencingToken}, 1), #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO NOTHING
            """)
    void upsertTask(RdTaskRow row);

    /**
     * Updates a complete task snapshot under version, fencing-token and status CAS. This is the
     * only persistence path for metadata edits, pause/resume and logical deletion of an existing
     * task; creation retries use {@link #upsertTask(RdTaskRow)} and intentionally do not mutate a
     * conflicting row.
     *
     * @return number of rows updated (0 = stale / mismatch)
     */
    @Update("""
            UPDATE rd_tasks
               SET task_type = #{row.taskType},
                   ticket_id = #{row.ticketId},
                   ticket_title = #{row.ticketTitle},
                   priority = #{row.priority},
                   status = #{row.status},
                   message_id = #{row.messageId},
                   title = #{row.title},
                   prompt_snapshot = #{row.promptSnapshot},
                   execution_result_json = CAST(#{row.executionResultJson} AS jsonb),
                   pull_request_url = #{row.pullRequestUrl},
                   error_message = #{row.errorMessage},
                   paused = COALESCE(#{row.paused}, FALSE),
                   source_type = #{row.sourceType},
                   source_id = #{row.sourceId},
                   source_url = #{row.sourceUrl},
                   project_id = #{row.projectId},
                   project_key = #{row.projectKey},
                   project_name = #{row.projectName},
                   repository_url = #{row.repositoryUrl},
                   repo_owner = #{row.repoOwner},
                   repo_name = #{row.repoName},
                   base_branch = #{row.baseBranch},
                   work_branch = #{row.workBranch},
                   expected_result = #{row.expectedResult},
                   acceptance_criteria_json = CAST(#{row.acceptanceCriteriaJson} AS jsonb),
                   host_assertion_bundle_json = CAST(#{row.hostAssertionBundleJson} AS jsonb),
                   token_budget_override = COALESCE(#{row.tokenBudgetOverride}, 0),
                   version = version + 1,
                   fencing_token = fencing_token + 1,
                   updated_at = #{row.updatedAt}
             WHERE id = #{row.id}
               AND version = #{expectedVersion}
               AND fencing_token = #{expectedFencingToken}
               AND status = #{expectedStatus}
            """)
    int updateTaskWithExpectedVersionFenced(
            @Param("row") RdTaskRow row,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedFencingToken") long expectedFencingToken,
            @Param("expectedStatus") String expectedStatus
    );

    /** Fenced variant used by production status writers. */
    @Update("""
            UPDATE rd_tasks
               SET status = #{newStatus},
                   error_message = COALESCE(CAST(#{errorMessage} AS text), error_message),
                   execution_result_json = COALESCE(CAST(#{executionResultJson} AS jsonb), execution_result_json),
                   pull_request_url = COALESCE(CAST(#{pullRequestUrl} AS text), pull_request_url),
                   prompt_snapshot = COALESCE(CAST(#{promptSnapshot} AS text), prompt_snapshot),
                   version = version + 1,
                   fencing_token = fencing_token + 1,
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND version = #{expectedVersion}
               AND fencing_token = #{expectedFencingToken}
               AND status = #{expectedStatus}
            """)
    int advanceStatusWithExpectedVersionFenced(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedFencingToken") long expectedFencingToken,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("errorMessage") String errorMessage,
            @Param("executionResultJson") String executionResultJson,
            @Param("pullRequestUrl") String pullRequestUrl,
            @Param("promptSnapshot") String promptSnapshot,
            @Param("updatedAt") OffsetDateTime updatedAt
    );
}
