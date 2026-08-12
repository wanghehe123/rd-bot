package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one authoritative provider tool-operation observation. */
@TableName("rd_provider_tool_operations")
public class ProviderToolOperationRow {
    @TableId(type = IdType.INPUT)
    public String operationId;
    public Long taskId;
    public String stageRunId;
    public String attemptId;
    public String status;
    public String reason;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
