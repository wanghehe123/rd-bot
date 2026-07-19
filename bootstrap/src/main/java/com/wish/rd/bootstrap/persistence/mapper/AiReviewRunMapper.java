package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.AiReviewRunRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/** MyBatis mapper for AI review attempt snapshots. */
@Mapper
public interface AiReviewRunMapper extends BaseMapper<AiReviewRunRow> {

    @Insert("""
            INSERT INTO rd_ai_review_runs (
                id, task_id, attempt_no, parent_run_id, status, model_name, package_hash,
                decision, score, retry_from_role, summary, error_category, error_message,
                version, created_at, updated_at
            ) VALUES (
                #{id}, #{taskId}, #{attemptNo}, #{parentRunId}, #{status}, #{modelName}, #{packageHash},
                #{decision}, #{score}, #{retryFromRole}, #{summary}, #{errorCategory}, #{errorMessage},
                #{version}, #{createdAt}, #{updatedAt}
            )
            """)
    void insertRun(AiReviewRunRow row);

    @Update("""
            UPDATE rd_ai_review_runs
               SET status = #{status}, model_name = #{modelName}, package_hash = #{packageHash},
                   decision = #{decision}, score = #{score}, retry_from_role = #{retryFromRole},
                   summary = #{summary}, error_category = #{errorCategory}, error_message = #{errorMessage},
                   version = #{version}, updated_at = #{updatedAt}
             WHERE id = #{id} AND status = #{expectedStatus} AND version = #{expectedVersion}
            """)
    int compareAndSet(AiReviewRunRow row);
}
