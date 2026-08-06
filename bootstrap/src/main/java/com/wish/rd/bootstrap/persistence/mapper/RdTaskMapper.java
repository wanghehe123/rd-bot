package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

/**
 * RD 任务 MyBatis-Plus mapper。
 *
 * <p>供 PostgreSQL 任务存储适配器执行 upsert 和查询。
 */
@Mapper
public interface RdTaskMapper extends BaseMapper<RdTaskRow> {

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
                expected_result, acceptance_criteria_json, token_budget_override, version, created_at, updated_at
            )
            VALUES (
                #{id}, #{taskType}, #{ticketId}, #{ticketTitle}, #{priority}, #{status},
                #{messageId}, #{title}, #{promptSnapshot}, #{executionResultJson}::jsonb,
                #{pullRequestUrl}, #{errorMessage}, COALESCE(#{paused}, FALSE), #{sourceType}, #{sourceId}, #{sourceUrl},
                #{projectId}, #{projectKey}, #{projectName}, #{repositoryUrl}, #{repoOwner}, #{repoName}, #{baseBranch}, #{workBranch},
                #{expectedResult}, #{acceptanceCriteriaJson}::jsonb, COALESCE(#{tokenBudgetOverride}, 0),
                COALESCE(#{version}, 0), #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                task_type = EXCLUDED.task_type,
                ticket_id = EXCLUDED.ticket_id,
                ticket_title = EXCLUDED.ticket_title,
                priority = EXCLUDED.priority,
                status = EXCLUDED.status,
                message_id = EXCLUDED.message_id,
                title = EXCLUDED.title,
                prompt_snapshot = EXCLUDED.prompt_snapshot,
                execution_result_json = EXCLUDED.execution_result_json,
                pull_request_url = EXCLUDED.pull_request_url,
                error_message = EXCLUDED.error_message,
                paused = EXCLUDED.paused,
                source_type = EXCLUDED.source_type,
                source_id = EXCLUDED.source_id,
                source_url = EXCLUDED.source_url,
                project_id = EXCLUDED.project_id,
                project_key = EXCLUDED.project_key,
                project_name = EXCLUDED.project_name,
                repository_url = EXCLUDED.repository_url,
                repo_owner = EXCLUDED.repo_owner,
                repo_name = EXCLUDED.repo_name,
                base_branch = EXCLUDED.base_branch,
                work_branch = EXCLUDED.work_branch,
                expected_result = EXCLUDED.expected_result,
                acceptance_criteria_json = EXCLUDED.acceptance_criteria_json,
                token_budget_override = EXCLUDED.token_budget_override,
                updated_at = EXCLUDED.updated_at
            """)
    void upsertTask(RdTaskRow row);

    /**
     * Advances status only when {@code id}, {@code version}, and {@code status} all match.
     * Increments {@code version} on success. Blind upserts must not use this path for correctness.
     *
     * @return number of rows updated (0 = stale / mismatch)
     */
    @Update("""
            UPDATE rd_tasks
               SET status = #{newStatus},
                   error_message = COALESCE(CAST(#{errorMessage} AS text), error_message),
                   execution_result_json = COALESCE(
                       CAST(#{executionResultJson} AS jsonb),
                       execution_result_json
                   ),
                   pull_request_url = COALESCE(CAST(#{pullRequestUrl} AS text), pull_request_url),
                   prompt_snapshot = COALESCE(CAST(#{promptSnapshot} AS text), prompt_snapshot),
                   version = version + 1,
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND version = #{expectedVersion}
               AND status = #{expectedStatus}
            """)
    int advanceStatusWithExpectedVersion(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("errorMessage") String errorMessage,
            @Param("executionResultJson") String executionResultJson,
            @Param("pullRequestUrl") String pullRequestUrl,
            @Param("promptSnapshot") String promptSnapshot,
            @Param("updatedAt") OffsetDateTime updatedAt
    );
}
