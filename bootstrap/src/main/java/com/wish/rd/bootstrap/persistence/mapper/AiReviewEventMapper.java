package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.AiReviewEventRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis mapper for append-only AI review events. */
@Mapper
public interface AiReviewEventMapper extends BaseMapper<AiReviewEventRow> {
    @Insert("""
            INSERT INTO rd_ai_review_events (
                id, run_id, from_status, to_status, trigger, message, error_category, occurred_at
            ) VALUES (
                #{id}, #{runId}, #{fromStatus}, #{toStatus}, #{trigger}, #{message}, #{errorCategory}, #{occurredAt}
            )
            """)
    void insertEvent(AiReviewEventRow row);
}
