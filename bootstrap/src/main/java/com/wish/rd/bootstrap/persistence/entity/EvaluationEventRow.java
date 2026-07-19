package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for an append-only evaluation lifecycle event. */
@TableName("rd_evaluation_events")
public class EvaluationEventRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long runId;
    public String fromStatus;
    public String toStatus;
    public String message;
    public String errorCategory;
    public String errorMessage;
    public OffsetDateTime occurredAt;
}
