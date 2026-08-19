package com.wish.rd.bootstrap.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** PostgreSQL row for one durable requirement stage command. */
@TableName("rd_requirement_stage_commands")
public class RequirementStageCommandRow {

    @TableId(type = IdType.INPUT)
    public Long id;
    public Long taskId;
    public Long taskVersion;
    public Long fencingToken;
    public String role;
    public String stage;
    public Long policyRunId;
    public Long retryCheckpointId;
    public Long businessGeneration;
    public Long targetRetryBindingId;
    public Long remediationRoundId;
    public String remediationKind;
    public Integer remediationNo;
    public Long remediationSourceStageRunId;
    public String remediationRequestJson;
    public String remediationRequestHash;
    public Integer attemptNo;
    public Integer maxAttempts;
    public OffsetDateTime deadlineAt;
    public String resourceClass;
    public String resourceRequirements;
    public String projectId;
    public String providerId;
    public Integer priorityRank;
    public String status;
    public String leaseOwner;
    public OffsetDateTime leaseUntil;
    public OffsetDateTime nextVisibleAt;
    public String lastError;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
