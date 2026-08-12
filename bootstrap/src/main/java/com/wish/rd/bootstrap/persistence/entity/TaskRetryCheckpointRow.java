package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one durable task retry checkpoint. */
@TableName("rd_task_retry_checkpoints")
public class TaskRetryCheckpointRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public String failurePhase;
    public String retryFromRole;
    public Long failedStageRunId;
    public Long failedRetrievalRunId;
    public Long failedAiReviewRunId;
    public Integer attemptNo;
    public String idempotencyKey;
    public String sourceTaskStatus;
    public Long sourceTaskVersion;
    public Long sourceFencingToken;
    public Long failedStageCommandId;
    public String failedStage;
    public Long sourcePolicyRunId;
    public Long policyRunId;
    public String sourcePlanDigest;
    public String authorizationPlanDigest;
    public String publicationOperationId;
    public Long dispatchTaskVersion;
    public Long dispatchFencingToken;
    public Long dispatchCommandId;
    public Long businessGeneration;
    @TableField("operator_note")
    public String operatorNote;
    @TableField("evidence_material_ids")
    public String evidenceMaterialIdsJson;
    public String status;
    public String reason;
    public String errorMessage;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    @TableField(exist = false)
    public String expectedStatus;
}
