package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one immutable AI review attempt snapshot. */
@TableName("rd_ai_review_runs")
public class AiReviewRunRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public Integer attemptNo;
    public Long parentRunId;
    public String status;
    public String modelName;
    public String packageHash;
    public String decision;
    public Integer score;
    public String retryFromRole;
    public String summary;
    public String errorCategory;
    public String errorMessage;
    public Long version;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    @TableField(exist = false)
    public String expectedStatus;
    @TableField(exist = false)
    public Long expectedVersion;
}
