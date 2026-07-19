package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one Web-triggered evaluation attempt snapshot. */
@TableName("rd_evaluation_runs")
public class EvaluationRunRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public String name;
    public Integer attemptNo;
    public Long parentRunId;
    public String status;
    public String phaseMessage;
    public Integer progressPercent;
    public String configJson;
    public Integer sampleCount;
    public Integer passedSampleCount;
    public Integer failedSampleCount;
    public Boolean overallPassed;
    public String metricsJson;
    public String errorCategory;
    public String errorMessage;
    public Long version;
    public OffsetDateTime createdAt;
    public OffsetDateTime startedAt;
    public OffsetDateTime finishedAt;
    public OffsetDateTime updatedAt;
    @TableField(exist = false)
    public String expectedStatus;
    @TableField(exist = false)
    public Long expectedVersion;
}
