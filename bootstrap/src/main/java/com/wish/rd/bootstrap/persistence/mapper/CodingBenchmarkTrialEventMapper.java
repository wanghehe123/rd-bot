package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.CodingBenchmarkTrialEventRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis mapper for append-only coding benchmark trial lifecycle events. */
@Mapper
public interface CodingBenchmarkTrialEventMapper extends BaseMapper<CodingBenchmarkTrialEventRow> {

    @Insert("""
            INSERT INTO rd_evaluation_trial_events (
                id, campaign_id, trial_id, from_status, to_status, version,
                message, error_category, error_message, occurred_at
            ) VALUES (
                #{id}, #{campaignId}, #{trialId}, #{fromStatus}, #{toStatus}, #{version},
                #{message}, #{errorCategory}, #{errorMessage}, #{occurredAt}
            )
            """)
    void insertEvent(CodingBenchmarkTrialEventRow row);
}
