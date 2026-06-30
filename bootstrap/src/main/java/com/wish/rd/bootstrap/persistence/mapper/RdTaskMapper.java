package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

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
                repository_url, repo_owner, repo_name, base_branch, work_branch,
                expected_result, acceptance_criteria_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{taskType}, #{ticketId}, #{ticketTitle}, #{priority}, #{status},
                #{messageId}, #{title}, #{promptSnapshot}, #{executionResultJson}::jsonb,
                #{pullRequestUrl}, #{errorMessage}, COALESCE(#{paused}, FALSE), #{sourceType}, #{sourceId}, #{sourceUrl},
                #{repositoryUrl}, #{repoOwner}, #{repoName}, #{baseBranch}, #{workBranch},
                #{expectedResult}, #{acceptanceCriteriaJson}::jsonb, #{createdAt}, #{updatedAt}
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
                repository_url = EXCLUDED.repository_url,
                repo_owner = EXCLUDED.repo_owner,
                repo_name = EXCLUDED.repo_name,
                base_branch = EXCLUDED.base_branch,
                work_branch = EXCLUDED.work_branch,
                expected_result = EXCLUDED.expected_result,
                acceptance_criteria_json = EXCLUDED.acceptance_criteria_json,
                updated_at = EXCLUDED.updated_at
            """)
    void upsertTask(RdTaskRow row);
}
