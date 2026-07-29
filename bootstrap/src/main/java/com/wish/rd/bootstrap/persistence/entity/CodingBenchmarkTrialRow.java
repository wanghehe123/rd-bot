package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one immutable logical coding benchmark trial. */
@TableName("rd_evaluation_trials")
public class CodingBenchmarkTrialRow {
    @TableId(type = IdType.INPUT)
    public String id;
    public Long campaignId;
    public String caseId;
    public String arm;
    public Integer replicateNo;
    public String status;
    public String verdict;
    public Integer attemptNo;
    public Long version;
    public String leaseOwner;
    public OffsetDateTime leaseExpiresAt;
    public String errorCategory;
    public String errorMessage;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    @TableField(exist = false)
    public String previousStatus;
}
