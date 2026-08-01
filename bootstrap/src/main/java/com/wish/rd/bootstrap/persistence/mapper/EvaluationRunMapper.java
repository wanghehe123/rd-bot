package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.EvaluationRunOverviewRow;
import com.wish.rd.bootstrap.persistence.entity.EvaluationRunRow;
import com.wish.rd.engine.evaluation.model.EvaluationRunQuery;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** MyBatis mapper for evaluation run snapshots. */
@Mapper
public interface EvaluationRunMapper extends BaseMapper<EvaluationRunRow> {
    @Insert("""
            INSERT INTO rd_evaluation_runs (
                id, name, attempt_no, parent_run_id, status, phase_message, progress_percent,
                config_json, sample_count, passed_sample_count, failed_sample_count, overall_passed,
                metrics_json, error_category, error_message, dispatch_paused, version,
                created_at, started_at, finished_at, updated_at
            ) VALUES (
                #{id}, #{name}, #{attemptNo}, #{parentRunId}, #{status}, #{phaseMessage}, #{progressPercent},
                #{configJson}::jsonb, #{sampleCount}, #{passedSampleCount}, #{failedSampleCount}, #{overallPassed},
                #{metricsJson}::jsonb, #{errorCategory}, #{errorMessage}, #{dispatchPaused}, #{version},
                #{createdAt}, #{startedAt}, #{finishedAt}, #{updatedAt}
            )
            """)
    void insertRun(EvaluationRunRow row);

    @Update("""
            UPDATE rd_evaluation_runs
               SET status = #{status}, phase_message = #{phaseMessage}, progress_percent = #{progressPercent},
                   config_json = #{configJson}::jsonb,
                   sample_count = #{sampleCount}, passed_sample_count = #{passedSampleCount},
                   failed_sample_count = #{failedSampleCount}, overall_passed = #{overallPassed},
                   metrics_json = #{metricsJson}::jsonb,
                   error_category = #{errorCategory}, error_message = #{errorMessage},
                   dispatch_paused = #{dispatchPaused}, version = #{version},
                   started_at = #{startedAt}, finished_at = #{finishedAt}, updated_at = #{updatedAt}
             WHERE id = #{id} AND status = #{expectedStatus} AND version = #{expectedVersion}
            """)
    int compareAndSet(EvaluationRunRow row);

    @SelectProvider(type = EvaluationRunHistorySqlProvider.class, method = "selectHistoryPage")
    List<EvaluationRunRow> selectHistoryPage(
            @Param("query") EvaluationRunQuery query,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    @SelectProvider(type = EvaluationRunHistorySqlProvider.class, method = "countHistoryPage")
    long countHistoryPage(@Param("query") EvaluationRunQuery query);

    @Results(id = "evaluationRunHistoryOverview", value = {
            @Result(property = "total", column = "total"),
            @Result(property = "active", column = "active"),
            @Result(property = "gatePassed", column = "gatePassed"),
            @Result(property = "incomplete", column = "incomplete"),
            @Result(property = "failed", column = "failed")
    })
    @SelectProvider(type = EvaluationRunHistorySqlProvider.class, method = "selectHistoryOverview")
    EvaluationRunOverviewRow selectHistoryOverview(@Param("nonSmokeDatasetIds") List<String> nonSmokeDatasetIds);
}
