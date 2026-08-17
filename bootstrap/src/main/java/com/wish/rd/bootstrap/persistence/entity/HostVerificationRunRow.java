package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one host BUILD/STATIC verification attempt snapshot. */
@TableName("rd_host_verification_runs")
public class HostVerificationRunRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public Long codingStageRunId;
    public Long parentRunId;
    public Integer attemptNo;
    public String status;
    public Boolean docsOnly;
    public String failureCategory;
    public String errorMessage;
    public Integer remediationCount;
    public OffsetDateTime createdAt;
    public OffsetDateTime startedAt;
    public OffsetDateTime finishedAt;
}
