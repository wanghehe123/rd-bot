package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one structured terminal task-failure identity. */
@TableName("rd_task_failure_provenance")
public class TaskFailureProvenanceRow {
    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public Long failedStageCommandId;
    public Integer failedCommandAttemptNo;
    public String failedStage;
    public String failurePhase;
    public String outcomeStatus;
    public Long failedTaskVersion;
    public Long failedTaskFencingToken;
    public Long failedStageRunId;
    public Long failedRetrievalRunId;
    public Long failedAiReviewRunId;
    public Long sourcePolicyRunId;
    public String sourcePlanDigest;
    public String publicationOperationId;
    public String failureKind;
    public OffsetDateTime recordedAt;
    public Long failedVerificationRunId;
}
