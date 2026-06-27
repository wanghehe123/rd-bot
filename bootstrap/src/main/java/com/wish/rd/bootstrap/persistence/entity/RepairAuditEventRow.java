package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("repair_audit_events")
public class RepairAuditEventRow {

    @TableId(type = IdType.AUTO)
    public Long id;
    public String repairRecordId;
    public String taskId;
    public String ticketId;
    public String eventType;
    public String externalSystem;
    public String summary;
    public String metadataJson;
    public OffsetDateTime createdAt;
}
