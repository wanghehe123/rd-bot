package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.EvaluationEventRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis mapper for evaluation lifecycle events. */
@Mapper
public interface EvaluationEventMapper extends BaseMapper<EvaluationEventRow> {
    @Insert("""
            INSERT INTO rd_evaluation_events (
                id, run_id, from_status, to_status, message, error_category, error_message, occurred_at
            ) VALUES (
                #{id}, #{runId}, #{fromStatus}, #{toStatus}, #{message},
                #{errorCategory}, #{errorMessage}, #{occurredAt}
            )
            """)
    void insertEvent(EvaluationEventRow row);
}
