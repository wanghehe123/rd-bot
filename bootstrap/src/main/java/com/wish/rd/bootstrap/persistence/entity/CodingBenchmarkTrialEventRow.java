package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL append-only event row for a coding benchmark trial state change. */
@TableName("rd_evaluation_trial_events")
public class CodingBenchmarkTrialEventRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long campaignId;
    public String trialId;
    public String fromStatus;
    public String toStatus;
    public Long version;
    public String message;
    public String errorCategory;
    public String errorMessage;
    public OffsetDateTime occurredAt;
}
