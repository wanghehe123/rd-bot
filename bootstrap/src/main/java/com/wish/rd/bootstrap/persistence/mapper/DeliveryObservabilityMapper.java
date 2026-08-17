package com.wish.rd.bootstrap.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * Read-only aggregates for delivery observability. Controllers must not call this mapper.
 */
@Mapper
public interface DeliveryObservabilityMapper {

    /**
     * Lists requirement tasks that were accepted or terminated inside the window, plus still-running tasks.
     *
     * @param projectId concrete project or null for all
     * @param windowStart window start
     * @param windowEnd window end
     * @return task rows
     */
    @Select("""
            SELECT id::text AS task_id,
                   project_id,
                   title,
                   status,
                   created_at,
                   updated_at,
                   pull_request_url
              FROM rd_tasks
             WHERE task_type = 'REQUIREMENT'
               AND status <> 'DELETED'
               AND (#{projectId,jdbcType=VARCHAR} IS NULL OR project_id = #{projectId,jdbcType=VARCHAR})
               AND (
                    (created_at >= #{windowStart,jdbcType=TIMESTAMP} AND created_at < #{windowEnd,jdbcType=TIMESTAMP})
                    OR (
                        status IN ('COMPLETED','COMMITTED','MERGED','REJECTED','FAILED_RETRYABLE',
                                   'FAILED_NEEDS_HUMAN','DEAD_LETTERED','CANCELLED')
                        AND updated_at >= #{windowStart,jdbcType=TIMESTAMP} AND updated_at < #{windowEnd,jdbcType=TIMESTAMP}
                    )
                    OR status NOT IN ('COMPLETED','COMMITTED','MERGED','REJECTED','FAILED_RETRYABLE',
                                      'FAILED_NEEDS_HUMAN','DEAD_LETTERED','CANCELLED','DELETED')
               )
            """)
    List<DeliveryObservabilityTaskRow> listTasks(
            @Param("projectId") String projectId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd
    );

    /**
     * Lists status events for tasks selected by the same window/project predicate.
     *
     * @param projectId concrete project or null
     * @param windowStart window start
     * @param windowEnd window end
     * @return events ordered by task and time
     */
    @Select("""
            SELECT e.task_id::text AS task_id, e.status, e.entered_at, e.duration_ms
              FROM rd_task_status_events e
              JOIN rd_tasks t ON t.id = e.task_id
             WHERE t.task_type = 'REQUIREMENT'
               AND t.status <> 'DELETED'
               AND (#{projectId,jdbcType=VARCHAR} IS NULL OR t.project_id = #{projectId,jdbcType=VARCHAR})
               AND (
                    (t.created_at >= #{windowStart,jdbcType=TIMESTAMP} AND t.created_at < #{windowEnd,jdbcType=TIMESTAMP})
                    OR (
                        t.status IN ('COMPLETED','COMMITTED','MERGED','REJECTED','FAILED_RETRYABLE',
                                     'FAILED_NEEDS_HUMAN','DEAD_LETTERED','CANCELLED')
                        AND t.updated_at >= #{windowStart,jdbcType=TIMESTAMP} AND t.updated_at < #{windowEnd,jdbcType=TIMESTAMP}
                    )
                    OR t.status NOT IN ('COMPLETED','COMMITTED','MERGED','REJECTED','FAILED_RETRYABLE',
                                        'FAILED_NEEDS_HUMAN','DEAD_LETTERED','CANCELLED','DELETED')
               )
             ORDER BY e.task_id, e.entered_at, e.id
            """)
    List<DeliveryObservabilityEventRow> listStatusEvents(
            @Param("projectId") String projectId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd
    );

    /**
     * Lists role stage runs for the same task predicate.
     *
     * @param projectId concrete project or null
     * @param windowStart window start
     * @param windowEnd window end
     * @return stage rows
     */
    @Select("""
            SELECT s.id::text AS stage_run_id,
                   s.task_id::text AS task_id,
                   s.role,
                   s.status,
                   s.attempt_no,
                   s.started_at,
                   s.finished_at,
                   s.error_category,
                   s.provider_attempts_json::text AS provider_attempts_json
              FROM rd_agent_stage_runs s
              JOIN rd_tasks t ON t.id = s.task_id
             WHERE t.task_type = 'REQUIREMENT'
               AND t.status <> 'DELETED'
               AND (#{projectId,jdbcType=VARCHAR} IS NULL OR t.project_id = #{projectId,jdbcType=VARCHAR})
               AND (
                    (t.created_at >= #{windowStart,jdbcType=TIMESTAMP} AND t.created_at < #{windowEnd,jdbcType=TIMESTAMP})
                    OR (
                        t.status IN ('COMPLETED','COMMITTED','MERGED','REJECTED','FAILED_RETRYABLE',
                                     'FAILED_NEEDS_HUMAN','DEAD_LETTERED','CANCELLED')
                        AND t.updated_at >= #{windowStart,jdbcType=TIMESTAMP} AND t.updated_at < #{windowEnd,jdbcType=TIMESTAMP}
                    )
                    OR t.status NOT IN ('COMPLETED','COMMITTED','MERGED','REJECTED','FAILED_RETRYABLE',
                                        'FAILED_NEEDS_HUMAN','DEAD_LETTERED','CANCELLED','DELETED')
               )
            """)
    List<DeliveryObservabilityStageRow> listStages(
            @Param("projectId") String projectId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd
    );

    /**
     * Lists durable waiting commands for backlog and oldest-age gauges.
     *
     * @param projectId concrete project or null
     * @return waiting commands
     */
    @Select("""
            SELECT id::text AS command_id,
                   status,
                   resource_class,
                   created_at
              FROM rd_requirement_stage_commands
             WHERE (#{projectId,jdbcType=VARCHAR} IS NULL OR project_id = #{projectId,jdbcType=VARCHAR})
               AND (
                    status IN ('PENDING','FAILED_RETRYABLE')
                    OR (status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until < now())
               )
            """)
    List<DeliveryObservabilityCommandRow> listWaitingCommands(@Param("projectId") String projectId);

    /** Task list row. */
    class DeliveryObservabilityTaskRow {
        public String taskId;
        public String projectId;
        public String title;
        public String status;
        public Instant createdAt;
        public Instant updatedAt;
        public String pullRequestUrl;
    }

    /** Status event row. {@code durationMs} is stored but must not be trusted when zero. */
    class DeliveryObservabilityEventRow {
        public String taskId;
        public String status;
        public Instant enteredAt;
        public long durationMs;
    }

    /** Stage run row. */
    class DeliveryObservabilityStageRow {
        public String stageRunId;
        public String taskId;
        public String role;
        public String status;
        public int attemptNo;
        public Instant startedAt;
        public Instant finishedAt;
        public String errorCategory;
        public String providerAttemptsJson;
    }

    /** Waiting command row. */
    class DeliveryObservabilityCommandRow {
        public String commandId;
        public String status;
        public String resourceClass;
        public Instant createdAt;
    }
}
