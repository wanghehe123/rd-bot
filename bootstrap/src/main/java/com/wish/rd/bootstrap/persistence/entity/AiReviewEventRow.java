package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** Append-only PostgreSQL row for one AI review transition. */
@TableName("rd_ai_review_events")
public class AiReviewEventRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long runId;
    public String fromStatus;
    public String toStatus;
    public String trigger;
    public String message;
    public String errorCategory;
    public OffsetDateTime occurredAt;
}
