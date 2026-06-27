package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairAuditEventRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RepairAuditEventMapper extends BaseMapper<RepairAuditEventRow> {

    @Insert("""
            INSERT INTO repair_audit_events (
                repair_record_id, task_id, ticket_id, event_type, external_system,
                summary, metadata_json, created_at
            )
            VALUES (
                #{repairRecordId}, #{taskId}, #{ticketId}, #{eventType}, #{externalSystem},
                #{summary}, #{metadataJson}::jsonb, #{createdAt}
            )
            """)
    void insertEvent(RepairAuditEventRow row);
}
