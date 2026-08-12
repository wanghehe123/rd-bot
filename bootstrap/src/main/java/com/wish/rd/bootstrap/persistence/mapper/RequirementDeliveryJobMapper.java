package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RequirementDeliveryJobRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface RequirementDeliveryJobMapper extends BaseMapper<RequirementDeliveryJobRow> {

    @Insert("""
            INSERT INTO rd_requirement_delivery_jobs
                (id, task_id, status, attempt_no, max_attempts, lease_owner, lease_until,
                 error_message, created_at, updated_at)
            VALUES
                (#{id}, #{taskId}, #{status}, #{attemptNo}, #{maxAttempts}, #{leaseOwner}, #{leaseUntil},
                 #{errorMessage}, #{createdAt}, #{updatedAt})
            ON CONFLICT (task_id) DO UPDATE SET
                status = 'PENDING', attempt_no = 0, max_attempts = EXCLUDED.max_attempts,
                lease_owner = '', lease_until = NULL, error_message = '', updated_at = EXCLUDED.updated_at
            WHERE rd_requirement_delivery_jobs.status IN ('SUCCEEDED', 'DEAD_LETTERED', 'CANCELLED')
            """)
    int enqueue(RequirementDeliveryJobRow row);

    @Select("SELECT * FROM rd_requirement_delivery_jobs WHERE task_id = #{taskId}")
    RequirementDeliveryJobRow findByTask(@Param("taskId") Long taskId);

    @Select("""
            UPDATE rd_requirement_delivery_jobs
            SET status = 'RUNNING', attempt_no = attempt_no + 1, lease_owner = #{leaseOwner},
                lease_until = #{leaseUntil}, error_message = '', updated_at = #{now}
            WHERE task_id = #{taskId}
              AND attempt_no < max_attempts
              AND (status IN ('PENDING', 'FAILED_RETRYABLE')
                   OR (status = 'RUNNING' AND lease_until <= #{now}))
            RETURNING *
            """)
    RequirementDeliveryJobRow claim(
            @Param("taskId") Long taskId,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil
    );

    @Select("""
            UPDATE rd_requirement_delivery_jobs
            SET lease_until = #{leaseUntil}, updated_at = #{now}
            WHERE id = #{id} AND status = 'RUNNING' AND lease_owner = #{leaseOwner}
            RETURNING *
            """)
    RequirementDeliveryJobRow heartbeat(
            @Param("id") Long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil
    );

    @Select("""
            UPDATE rd_requirement_delivery_jobs
            SET status = 'SUCCEEDED', lease_owner = '', lease_until = NULL, error_message = '', updated_at = #{now}
            WHERE id = #{id} AND status = 'RUNNING' AND lease_owner = #{leaseOwner}
            RETURNING *
            """)
    RequirementDeliveryJobRow complete(
            @Param("id") Long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now
    );

    @Select("""
            UPDATE rd_requirement_delivery_jobs
            SET status = CASE WHEN attempt_no >= max_attempts THEN 'DEAD_LETTERED' ELSE 'FAILED_RETRYABLE' END,
                lease_owner = '', lease_until = NULL, error_message = #{errorMessage}, updated_at = #{now}
            WHERE id = #{id} AND status = 'RUNNING' AND lease_owner = #{leaseOwner}
            RETURNING *
            """)
    RequirementDeliveryJobRow fail(
            @Param("id") Long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("errorMessage") String errorMessage,
            @Param("now") OffsetDateTime now
    );

    @Select("""
            SELECT * FROM rd_requirement_delivery_jobs
            WHERE (status IN ('PENDING', 'FAILED_RETRYABLE') AND attempt_no < max_attempts)
               OR (status = 'RUNNING' AND lease_until <= #{now})
            ORDER BY updated_at, id
            LIMIT #{limit}
            """)
    List<RequirementDeliveryJobRow> recoverable(
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit
    );

    @Select("""
            SELECT * FROM rd_requirement_delivery_jobs
            WHERE status = 'RUNNING'
              AND lease_until > #{now}
            ORDER BY updated_at, id
            """)
    List<RequirementDeliveryJobRow> listInFlight(@Param("now") OffsetDateTime now);

    @Select("""
            UPDATE rd_requirement_delivery_jobs
            SET status = 'CANCELLED', lease_owner = '', lease_until = NULL,
                error_message = #{errorMessage}, updated_at = #{now}
            WHERE task_id = #{taskId}
              AND status IN ('PENDING', 'RUNNING', 'FAILED_RETRYABLE')
            RETURNING *
            """)
    RequirementDeliveryJobRow cancelByTask(
            @Param("taskId") Long taskId,
            @Param("errorMessage") String errorMessage,
            @Param("now") OffsetDateTime now
    );

    @Select("""
            UPDATE rd_requirement_delivery_jobs
            SET status = 'DEAD_LETTERED', lease_owner = '', lease_until = NULL,
                error_message = #{errorMessage}, updated_at = #{now}
            WHERE task_id = #{taskId}
              AND status = 'RUNNING'
              AND lease_until <= #{now}
              AND attempt_no >= max_attempts
            RETURNING *
            """)
    RequirementDeliveryJobRow deadLetterExpiredExhausted(
            @Param("taskId") Long taskId,
            @Param("now") OffsetDateTime now,
            @Param("errorMessage") String errorMessage
    );
}
