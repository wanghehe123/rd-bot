package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one prepared or finalized requirement stage execution attempt. */
@TableName("rd_requirement_stage_finalizations")
public class RequirementStageFinalizationRow {
    public Long commandId;
    public Integer attemptNo;
    public Long taskId;
    public Long expectedTaskVersion;
    public Long expectedFencingToken;
    public String expectedTaskStatus;
    public String stage;
    public String state;
    public String outcomeStatus;
    public String resultJson;
    public String errorMessage;
    public String outcomePlanJson;
    public String outcomePlanDigest;
    public Long nextCommandId;
    public OffsetDateTime preparedAt;
    public OffsetDateTime finalizedAt;
}
