package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairQueueDeadLetterRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RepairQueueDeadLetterMapper extends BaseMapper<RepairQueueDeadLetterRow> {

    /**
     * Inserts a dead letter exactly once using its deterministic primary key.
     *
     * @param row valid or malformed dead-letter row whose id is derived from its idempotency identity
     * @return one when this call inserted the row, zero when an already-committed row won the race
     */
    @Insert("""
            INSERT INTO repair_queue_dead_letters (
                id, ticket_id, trace_id, source, event_id, event_type,
                original_attempt, reason, message_json, replayed, created_at, replayed_at
            )
            VALUES (
                #{id}, #{ticketId}, #{traceId}, #{source}, #{eventId}, #{eventType},
                #{originalAttempt}, #{reason}, #{messageJson}::jsonb, #{replayed}, #{createdAt}, #{replayedAt}
            )
            ON CONFLICT (id) DO NOTHING
            """)
    int insertDeadLetterIfAbsent(RepairQueueDeadLetterRow row);

    @Update("""
            UPDATE repair_queue_dead_letters
            SET replayed = #{replayed},
                replayed_at = #{replayedAt}
            WHERE id = #{id}
            """)
    void updateReplayState(RepairQueueDeadLetterRow row);
}
