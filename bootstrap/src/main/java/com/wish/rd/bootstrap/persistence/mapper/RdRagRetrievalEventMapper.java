package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdRagRetrievalEventRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RdRagRetrievalEventMapper extends BaseMapper<RdRagRetrievalEventRow> {

    @Insert("""
            INSERT INTO rd_rag_retrieval_events (
                id, run_id, from_status, to_status, trigger, message, error_category, occurred_at
            ) VALUES (
                #{id}, #{runId}, #{fromStatus}, #{toStatus}, #{trigger}, #{message}, #{errorCategory}, #{occurredAt}
            )
            """)
    void insertEvent(RdRagRetrievalEventRow row);
}
