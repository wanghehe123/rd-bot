package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.CodingBenchmarkTrialRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/** MyBatis mapper for coding benchmark trial snapshots and atomic lease claims. */
@Mapper
public interface CodingBenchmarkTrialMapper extends BaseMapper<CodingBenchmarkTrialRow> {

    @Insert("""
            INSERT INTO rd_evaluation_trials (
                id, campaign_id, case_id, arm, replicate_no, status, verdict, attempt_no, version,
                lease_owner, lease_expires_at, error_category, error_message, created_at, updated_at
            ) VALUES (
                #{id}, #{campaignId}, #{caseId}, #{arm}, #{replicateNo}, #{status}, #{verdict}, #{attemptNo}, #{version},
                #{leaseOwner}, #{leaseExpiresAt}, #{errorCategory}, #{errorMessage}, #{createdAt}, #{updatedAt}
            )
            """)
    void insertTrial(CodingBenchmarkTrialRow row);

    @Select("""
            WITH active_cases AS (
                SELECT DISTINCT case_id
                  FROM rd_evaluation_trials
                 WHERE campaign_id = #{campaignId}
                   AND (
                        status IN ('RUNNING_AGENTS', 'RUNNING_ORACLE')
                        OR (status = 'PREPARING' AND lease_expires_at > #{now})
                   )
            ),
            candidate AS (
                SELECT id, status AS previous_status
                  FROM rd_evaluation_trials
                 WHERE campaign_id = #{campaignId}
                   AND (status = 'QUEUED'
                        OR (status = 'PREPARING' AND lease_expires_at <= #{now}))
                   AND case_id NOT IN (SELECT case_id FROM active_cases)
                 ORDER BY created_at, id
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
            )
            UPDATE rd_evaluation_trials trial
               SET status = 'PREPARING', lease_owner = #{leaseOwner}, lease_expires_at = #{leaseExpiresAt},
                   version = trial.version + 1, updated_at = #{now}
              FROM candidate
             WHERE trial.id = candidate.id
            RETURNING trial.*, candidate.previous_status
            """)
    CodingBenchmarkTrialRow claimNext(
            @Param("campaignId") Long campaignId,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") OffsetDateTime now,
            @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt
    );

    @Select("""
            UPDATE rd_evaluation_trials
               SET status = #{targetStatus}, verdict = #{verdict}, error_category = #{errorCategory},
                   error_message = #{errorMessage},
                   lease_owner = CASE WHEN #{targetStatus} IN ('QUEUED', 'RETRY_PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
                                      THEN '' ELSE lease_owner END,
                   lease_expires_at = CASE WHEN #{targetStatus} IN ('QUEUED', 'RETRY_PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
                                           THEN NULL ELSE lease_expires_at END,
                   version = version + 1, updated_at = #{now}
             WHERE id = #{trialId} AND status = #{expectedStatus} AND version = #{expectedVersion}
            RETURNING *
            """)
    CodingBenchmarkTrialRow transition(
            @Param("trialId") String trialId,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") Long expectedVersion,
            @Param("targetStatus") String targetStatus,
            @Param("verdict") String verdict,
            @Param("errorCategory") String errorCategory,
            @Param("errorMessage") String errorMessage,
            @Param("now") OffsetDateTime now
    );

    @Select("SELECT * FROM rd_evaluation_trials WHERE campaign_id = #{campaignId} ORDER BY created_at, id")
    List<CodingBenchmarkTrialRow> selectByCampaign(@Param("campaignId") Long campaignId);
}
